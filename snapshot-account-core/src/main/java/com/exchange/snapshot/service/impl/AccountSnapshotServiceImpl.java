package com.exchange.snapshot.service.impl;

import com.exchange.snapshot.dto.AdlClearingApplyResult;
import com.exchange.snapshot.dto.AdlClearingRequest;
import com.exchange.snapshot.dto.PositionUpnlView;
import com.exchange.snapshot.dto.TradeEntryEvent;
import com.exchange.snapshot.entity.AccountSnapshot;
import com.exchange.snapshot.mapper.AccountSnapshotMapper;
import com.exchange.snapshot.mapper.PositionSnapshotMirrorMapper;
import com.exchange.snapshot.publisher.AccountChangePublisher;
import com.exchange.snapshot.service.AccountSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Account Snapshot Service 实现（生产级）
 * 
 * 🔥 核心职责：
 * 1. 消费Ledger Event
 * 2. 更新MySQL Snapshot
 * 3. 更新Redis缓存
 * 
 * 对标：Binance / OKX / Bybit级别
 */
@Slf4j
@Service
public class AccountSnapshotServiceImpl implements AccountSnapshotService {
    
    @Autowired
    private AccountSnapshotMapper accountSnapshotMapper;
    
    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountChangePublisher accountChangePublisher;

    @Autowired(required = false)
    private PositionSnapshotMirrorMapper positionSnapshotMirrorMapper;
    
    private static final String REDIS_KEY_PREFIX = "snapshot:";
    private static final String REDIS_POSITION_UPNL_PREFIX = "snapshot:position:upnl:";
    private static final String REDIS_ADL_CLEARING_PREFIX = "snapshot:adl:clearing:";
    private static final long REDIS_TTL_HOURS = 24;
    private static final String CURRENCY = "USDT";
    private static final int MAX_UNREALIZED_UPDATE_RETRIES = 3;
    private static final int MAX_ADL_CLEARING_UPDATE_RETRIES = 3;
    private static final BigDecimal UPNL_EPSILON = new BigDecimal("0.00000001");

    @Value("${snapshot.account.position-upnl.db-fallback-enabled:true}")
    private boolean positionUpnlDbFallbackEnabled;

    @Value("${snapshot.account.position-upnl.reconcile-interval-ms:3000}")
    private long positionUpnlReconcileIntervalMs;

    private final ConcurrentHashMap<Long, Long> lastPositionReconcileAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AdlClearingApplyResult> adlClearingMemo = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> adlClearingLocks = new ConcurrentHashMap<>();
    
    /**
     * 消费TradeEntryEvent（核心方法）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onTradeEntryEvent(TradeEntryEvent event) {
        log.info("[SnapshotService] ⬇️ Consume trade entry event, tradeId={}, bizSeq={}, entries={}",
            event.getTradeId(), event.getBizSeq(), event.getEntries().size());
        
        try {
            // 按userId聚合分录
            Map<Long, AccountSnapshot> snapshotMap = new HashMap<>();
            
            for (TradeEntryEvent.LedgerEntry entry : event.getEntries()) {
                Long userId = entry.getUserId();
                
                // 跳过系统账户
                if (userId == null || userId == 0) {
                    continue;
                }
                
                // 获取或创建Snapshot
                AccountSnapshot snapshot = snapshotMap.computeIfAbsent(userId, 
                    this::getOrCreateSnapshot);
                
                // 幂等性校验
                if (snapshot.getLastBizSeq() != null 
                    && entry.getBizSeq() <= snapshot.getLastBizSeq()) {
                    log.warn("[SnapshotService] ⚠️ Duplicate event, userId={}, lastBizSeq={}, currentBizSeq={}",
                        userId, snapshot.getLastBizSeq(), entry.getBizSeq());
                    continue;
                }
                
                // 应用分录
                applyEntryToSnapshot(entry, snapshot);
                
                // 更新同步位点
                snapshot.setLastBizSeq(entry.getBizSeq());
                snapshot.setLastEntryId(entry.getEntryId());
                snapshot.setLastTradeId(event.getTradeId());
            }
            
            // 批量更新MySQL和Redis
            for (AccountSnapshot snapshot : snapshotMap.values()) {
                // 计算equity
                snapshot.calculateEquity();
                snapshot.setUpdatedAt(System.currentTimeMillis());
                
                // 更新MySQL（乐观锁）
                int updated = accountSnapshotMapper.updateWithOptimisticLock(snapshot);
                
                if (updated == 0) {
                    log.error("[SnapshotService] ❌ Update snapshot failed (version conflict), userId={}, version={}",
                        snapshot.getUserId(), snapshot.getVersion());
                    throw new RuntimeException("Update snapshot failed, version conflict");
                }
                
                // 更新Redis
                updateRedisSnapshot(snapshot);

                accountChangePublisher.publishSnapshotUpdate(
                    snapshot,
                    "ACCOUNT_BALANCE_UPDATE",
                    "LEDGER_ENTRY",
                    event.getTradeId()
                );
                
                log.info("[SnapshotService] ✅ Updated snapshot, userId={}, available={}, frozen={}, position={}, equity={}",
                    snapshot.getUserId(), snapshot.getAvailable(), snapshot.getFrozen(), 
                    snapshot.getPositionMargin(), snapshot.getEquity());
            }
            
            log.info("[SnapshotService] ✅ Trade entry event processed, tradeId={}, affectedUsers={}",
                event.getTradeId(), snapshotMap.size());
            
        } catch (Exception e) {
            log.error("[SnapshotService] ❌ Process trade entry event error, tradeId={}", 
                event.getTradeId(), e);
            throw new RuntimeException("Process trade entry event failed", e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onPositionMarkUpdate(Long userId,
                                     String symbol,
                                     Integer positionSide,
                                     BigDecimal positionQty,
                                     BigDecimal unrealizedPnl,
                                     String changeType,
                                     String markPriceId) {
        if (userId == null || userId <= 0 || symbol == null || symbol.isBlank() || positionSide == null || positionSide <= 0) {
            return;
        }

        BigDecimal safeQty = positionQty == null ? BigDecimal.ZERO : positionQty;
        BigDecimal safeUnrealized = unrealizedPnl == null ? BigDecimal.ZERO : unrealizedPnl;
        BigDecimal totalUnrealized = refreshAndSumUserUnrealized(userId, symbol, positionSide, safeQty, safeUnrealized);
        String resolvedChangeType = (changeType == null || changeType.isBlank()) ? "MARK_PRICE_UPDATE" : changeType;
        String bizId = (markPriceId == null || markPriceId.isBlank()) ? (symbol + ":" + positionSide) : markPriceId;

        for (int attempt = 1; attempt <= MAX_UNREALIZED_UPDATE_RETRIES; attempt++) {
            AccountSnapshot snapshot = getOrCreateSnapshot(userId);
            BigDecimal oldUnrealized = snapshot.getUnrealizedPnl() == null ? BigDecimal.ZERO : snapshot.getUnrealizedPnl();
            BigDecimal newEquity = recomputeEquity(snapshot, totalUnrealized);
            BigDecimal oldEquity = snapshot.getEquity() == null ? recomputeEquity(snapshot, oldUnrealized) : snapshot.getEquity();

            // 去重：总UPNL/权益未变化时不做DB写入与下游推送，避免无效风暴。
            if (isSameMoney(oldUnrealized, totalUnrealized) && isSameMoney(oldEquity, newEquity)) {
                updateRedisSnapshot(snapshot);
                accountChangePublisher.publishSnapshotUpdate(
                    snapshot,
                    "ACCOUNT_MARK_UPDATE",
                    resolvedChangeType,
                    bizId
                );
                return;
            }

            snapshot.setUnrealizedPnl(totalUnrealized);
            snapshot.setEquity(newEquity);
            long now = System.currentTimeMillis();
            snapshot.setUpdatedAt(now);

            int updated = accountSnapshotMapper.updateUnrealizedPnlWithOptimisticLock(
                snapshot.getUserId(),
                snapshot.getUnrealizedPnl(),
                snapshot.getEquity(),
                snapshot.getUpdatedAt(),
                snapshot.getVersion()
            );
            if (updated > 0) {
                snapshot.setVersion((snapshot.getVersion() == null ? 0 : snapshot.getVersion()) + 1);
                updateRedisSnapshot(snapshot);
                accountChangePublisher.publishSnapshotUpdate(
                    snapshot,
                    "ACCOUNT_MARK_UPDATE",
                    resolvedChangeType,
                    bizId
                );
                return;
            }

            log.warn("[SnapshotService] Unrealized pnl update conflict, userId={}, attempt={}/{}",
                userId, attempt, MAX_UNREALIZED_UPDATE_RETRIES);
        }

        throw new RuntimeException("Update unrealized pnl failed after retries, userId=" + userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdlClearingApplyResult applyAdlClearing(AdlClearingRequest request) {
        if (request == null || request.getBizSeq() == null || request.getBizSeq().isBlank()) {
            throw new IllegalArgumentException("bizSeq is required");
        }
        String bizSeq = request.getBizSeq().trim();

        AdlClearingApplyResult cached = findCachedAdlResult(bizSeq);
        if (cached != null) {
            return cloneForIdempotent(cached);
        }

        Object lock = adlClearingLocks.computeIfAbsent(bizSeq, k -> new Object());
        try {
            synchronized (lock) {
                cached = findCachedAdlResult(bizSeq);
                if (cached != null) {
                    return cloneForIdempotent(cached);
                }

                Map<Long, AdlAccountDelta> deltaByUser = aggregateAdlDeltas(request.getEntries());
                for (Map.Entry<Long, AdlAccountDelta> entry : deltaByUser.entrySet()) {
                    applyAdlDeltaWithRetry(entry.getKey(), entry.getValue(), bizSeq);
                }

                AdlClearingApplyResult result = new AdlClearingApplyResult();
                result.setSuccess(true);
                result.setBizSeq(bizSeq);
                result.setIdempotent(false);
                result.setMessage("ADL clearing applied");
                result.setLedgerIds(buildLedgerIds(bizSeq, request.getEntries()));
                cacheAdlResult(result);
                return result;
            }
        } finally {
            adlClearingLocks.remove(bizSeq);
        }
    }

    @Override
    public boolean hasProcessedAdlClearing(String bizSeq) {
        if (bizSeq == null || bizSeq.isBlank()) {
            return false;
        }
        String normalized = bizSeq.trim();
        if (adlClearingMemo.containsKey(normalized)) {
            return true;
        }
        if (redisTemplate == null) {
            return false;
        }
        try {
            Boolean exists = redisTemplate.hasKey(REDIS_ADL_CLEARING_PREFIX + normalized);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("[SnapshotService] Check ADL clearing cache failed, bizSeq={}", normalized, e);
            return false;
        }
    }
    
    /**
     * 查询账户快照（风控读取）
     */
    @Override
    public AccountSnapshot queryAccount(Long userId) {
        // 1. 先查Redis
        if (redisTemplate != null) {
            AccountSnapshot snapshot = getFromRedis(userId);
            if (snapshot != null) {
                log.debug("[SnapshotService] ✅ Cache hit, userId={}", userId);
                return snapshot;
            }
        }
        
        // 2. 查MySQL
        AccountSnapshot snapshot = accountSnapshotMapper.selectById(userId);
        
        if (snapshot != null && redisTemplate != null) {
            // 3. 回写Redis
            updateRedisSnapshot(snapshot);
        }
        
        return snapshot;
    }
    
    /**
     * 更新Redis快照
     */
    @Override
    public void updateRedisSnapshot(AccountSnapshot snapshot) {
        if (redisTemplate == null) {
            return;
        }
        
        try {
            String key = REDIS_KEY_PREFIX + snapshot.getUserId();
            
            // 使用Hash存储（便于部分字段更新）
            Map<String, Object> map = new HashMap<>();
            map.put("available", snapshot.getAvailable().toString());
            map.put("frozen", snapshot.getFrozen().toString());
            map.put("positionMargin", snapshot.getPositionMargin().toString());
            map.put("unrealizedPnl", snapshot.getUnrealizedPnl() != null ? 
                snapshot.getUnrealizedPnl().toString() : "0");
            map.put("equity", snapshot.getEquity().toString());
            map.put("marginRatio", snapshot.getMarginRatio() != null ? 
                snapshot.getMarginRatio().toString() : "0");
            map.put("lastBizSeq", snapshot.getLastBizSeq().toString());
            map.put("updatedAt", snapshot.getUpdatedAt().toString());
            
            redisTemplate.opsForHash().putAll(key, map);
            redisTemplate.expire(key, REDIS_TTL_HOURS, TimeUnit.HOURS);
            
            log.debug("[SnapshotService] ✅ Redis updated, userId={}, key={}", 
                snapshot.getUserId(), key);
            
        } catch (Exception e) {
            log.error("[SnapshotService] ❌ Update Redis error, userId={}", 
                snapshot.getUserId(), e);
        }
    }
    
    /**
     * 从Redis获取快照
     */
    @Override
    public AccountSnapshot getFromRedis(Long userId) {
        if (redisTemplate == null) {
            return null;
        }
        
        try {
            String key = REDIS_KEY_PREFIX + userId;
            Map<Object, Object> map = redisTemplate.opsForHash().entries(key);
            
            if (map == null || map.isEmpty()) {
                return null;
            }
            
            AccountSnapshot snapshot = new AccountSnapshot();
            snapshot.setUserId(userId);
            snapshot.setCurrency(CURRENCY);
            snapshot.setAvailable(new BigDecimal(map.get("available").toString()));
            snapshot.setFrozen(new BigDecimal(map.get("frozen").toString()));
            snapshot.setPositionMargin(new BigDecimal(map.get("positionMargin").toString()));
            snapshot.setUnrealizedPnl(new BigDecimal(map.get("unrealizedPnl").toString()));
            snapshot.setEquity(new BigDecimal(map.get("equity").toString()));
            snapshot.setMarginRatio(map.get("marginRatio") != null ? 
                new BigDecimal(map.get("marginRatio").toString()) : null);
            snapshot.setLastBizSeq(Long.valueOf(map.get("lastBizSeq").toString()));
            snapshot.setUpdatedAt(Long.valueOf(map.get("updatedAt").toString()));
            
            return snapshot;
            
        } catch (Exception e) {
            log.warn("[SnapshotService] ⚠️ Get from Redis error, userId={}", userId, e);
            return null;
        }
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 获取或创建Snapshot
     */
    private AccountSnapshot getOrCreateSnapshot(Long userId) {
        AccountSnapshot snapshot = accountSnapshotMapper.selectById(userId);
        
        if (snapshot == null) {
            snapshot = new AccountSnapshot();
            snapshot.setUserId(userId);
            snapshot.setCurrency(CURRENCY);
            snapshot.setAvailable(BigDecimal.ZERO);
            snapshot.setFrozen(BigDecimal.ZERO);
            snapshot.setPositionMargin(BigDecimal.ZERO);
            snapshot.setUnrealizedPnl(BigDecimal.ZERO);
            snapshot.setRealizedPnl(BigDecimal.ZERO);
            snapshot.setEquity(BigDecimal.ZERO);
            snapshot.setLastBizSeq(0L);
            snapshot.setVersion(0);
            snapshot.setCreatedAt(System.currentTimeMillis());
            snapshot.setUpdatedAt(System.currentTimeMillis());
            
            accountSnapshotMapper.insert(snapshot);
        }
        
        return snapshot;
    }
    
    /**
     * 应用分录到Snapshot
     */
    private void applyEntryToSnapshot(TradeEntryEvent.LedgerEntry entry, AccountSnapshot snapshot) {
        // 计算借贷差额
        BigDecimal delta = entry.getDebit().subtract(entry.getCredit());
        
        // 根据账户类型更新对应字段
        Integer accountType = entry.getAccountType();
        
        if (accountType == 1) { // USER_AVAILABLE
            snapshot.setAvailable(snapshot.getAvailable().add(delta));
        } else if (accountType == 2) { // USER_FROZEN
            snapshot.setFrozen(snapshot.getFrozen().add(delta));
        } else if (accountType == 3) { // USER_POSITION_MARGIN
            snapshot.setPositionMargin(snapshot.getPositionMargin().add(delta));
        }
        
        log.debug("[SnapshotService] Apply entry, userId={}, accountType={}, delta={}, " +
            "available={}, frozen={}, position={}",
            entry.getUserId(), accountType, delta, 
            snapshot.getAvailable(), snapshot.getFrozen(), snapshot.getPositionMargin());
    }

    private BigDecimal refreshAndSumUserUnrealized(Long userId,
                                                   String symbol,
                                                   Integer positionSide,
                                                   BigDecimal positionQty,
                                                   BigDecimal unrealizedPnl) {
        BigDecimal redisTotal = unrealizedPnl;
        boolean redisAvailable = redisTemplate != null;
        String redisKey = REDIS_POSITION_UPNL_PREFIX + userId;
        String redisField = symbol + ":" + positionSide;

        if (redisAvailable) {
            try {
                if (positionQty == null || positionQty.compareTo(BigDecimal.ZERO) <= 0) {
                    redisTemplate.opsForHash().delete(redisKey, redisField);
                } else {
                    redisTemplate.opsForHash().put(redisKey, redisField, unrealizedPnl.toPlainString());
                }
                redisTemplate.expire(redisKey, REDIS_TTL_HOURS, TimeUnit.HOURS);
                redisTotal = sumRedisPositionUpnl(redisKey);
            } catch (Exception e) {
                log.warn("[SnapshotService] Recompute unrealized pnl from redis failed, userId={}, symbol={}, side={}",
                    userId, symbol, positionSide, e);
                redisAvailable = false;
                redisTotal = unrealizedPnl;
            }
        }

        // Redis 关闭/异常时直接走 DB 权威汇总回补；若 DB 也不可用，则退化为单仓位值。
        if (!positionUpnlDbFallbackEnabled || positionSnapshotMirrorMapper == null) {
            return redisTotal;
        }

        boolean shouldReconcile = !redisAvailable
            || shouldReconcileNow(userId)
            || positionQty == null
            || positionQty.compareTo(BigDecimal.ZERO) <= 0;

        if (!shouldReconcile) {
            return redisTotal;
        }

        BigDecimal dbTotal = queryUserPositionUpnlTotal(userId);
        if (dbTotal == null) {
            return redisTotal;
        }

        lastPositionReconcileAt.put(userId, System.currentTimeMillis());

        if (redisAvailable && dbTotal.subtract(redisTotal).abs().compareTo(UPNL_EPSILON) > 0) {
            rebuildRedisPositionUpnlCache(userId);
        }
        return dbTotal;
    }

    private boolean shouldReconcileNow(Long userId) {
        long now = System.currentTimeMillis();
        Long last = lastPositionReconcileAt.get(userId);
        if (last == null) {
            return true;
        }
        return now - last >= Math.max(1000L, positionUpnlReconcileIntervalMs);
    }

    private BigDecimal sumRedisPositionUpnl(String key) {
        Map<Object, Object> all = redisTemplate.opsForHash().entries(key);
        BigDecimal total = BigDecimal.ZERO;
        for (Object value : all.values()) {
            if (value == null) {
                continue;
            }
            try {
                total = total.add(new BigDecimal(value.toString()));
            } catch (Exception ignore) {
                // 脏值忽略，避免阻断主流程
            }
        }
        return total;
    }

    private BigDecimal queryUserPositionUpnlTotal(Long userId) {
        try {
            BigDecimal total = positionSnapshotMirrorMapper.sumOpenUnrealizedPnl(userId);
            return total == null ? BigDecimal.ZERO : total;
        } catch (Exception e) {
            log.warn("[SnapshotService] Query position upnl total from DB failed, userId={}", userId, e);
            return null;
        }
    }

    private void rebuildRedisPositionUpnlCache(Long userId) {
        if (redisTemplate == null || positionSnapshotMirrorMapper == null) {
            return;
        }

        try {
            String key = REDIS_POSITION_UPNL_PREFIX + userId;
            List<PositionUpnlView> rows = positionSnapshotMirrorMapper.selectOpenPositionUpnl(userId);
            if (rows == null) {
                rows = Collections.emptyList();
            }

            redisTemplate.delete(key);
            for (PositionUpnlView row : rows) {
                if (row == null || row.getSymbol() == null || row.getPositionSide() == null) {
                    continue;
                }
                BigDecimal qty = row.getQuantity() == null ? BigDecimal.ZERO : row.getQuantity();
                if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal upnl = row.getUnrealizedPnl() == null ? BigDecimal.ZERO : row.getUnrealizedPnl();
                String field = row.getSymbol() + ":" + row.getPositionSide();
                redisTemplate.opsForHash().put(key, field, upnl.toPlainString());
            }
            redisTemplate.expire(key, REDIS_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[SnapshotService] Rebuild redis position upnl cache failed, userId={}", userId, e);
        }
    }

    private Map<Long, AdlAccountDelta> aggregateAdlDeltas(List<AdlClearingRequest.LedgerEntry> entries) {
        Map<Long, AdlAccountDelta> deltas = new HashMap<>();
        if (entries == null || entries.isEmpty()) {
            return deltas;
        }

        for (AdlClearingRequest.LedgerEntry entry : entries) {
            if (entry == null || entry.getUserId() == null || entry.getUserId() <= 0) {
                continue;
            }
            String accountType = entry.getAccountType() == null ? "" : entry.getAccountType().trim().toUpperCase();
            BigDecimal amount = entry.getAmount() == null ? BigDecimal.ZERO : entry.getAmount();
            if (amount.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            AdlAccountDelta delta = deltas.computeIfAbsent(entry.getUserId(), key -> new AdlAccountDelta());
            if ("MARGIN".equals(accountType)) {
                // ADL平仓回收/返还保证金：可用余额增加、持仓保证金同步减少。
                delta.availableDelta = delta.availableDelta.add(amount);
                delta.positionMarginDelta = delta.positionMarginDelta.subtract(amount);
            } else if ("REALIZED_PNL".equals(accountType)) {
                delta.realizedPnlDelta = delta.realizedPnlDelta.add(amount);
                delta.availableDelta = delta.availableDelta.add(amount);
            }
        }
        return deltas;
    }

    private void applyAdlDeltaWithRetry(Long userId, AdlAccountDelta delta, String bizSeq) {
        if (delta == null || delta.isNoop()) {
            return;
        }

        for (int attempt = 1; attempt <= MAX_ADL_CLEARING_UPDATE_RETRIES; attempt++) {
            AccountSnapshot snapshot = getOrCreateSnapshot(userId);
            snapshot.setAvailable(nonNull(snapshot.getAvailable()).add(delta.availableDelta));
            snapshot.setPositionMargin(nonNegative(nonNull(snapshot.getPositionMargin()).add(delta.positionMarginDelta)));
            snapshot.setRealizedPnl(nonNull(snapshot.getRealizedPnl()).add(delta.realizedPnlDelta));
            snapshot.setLastTradeId("ADL:" + bizSeq);
            snapshot.setUpdatedAt(System.currentTimeMillis());
            snapshot.calculateEquity();

            int updated = accountSnapshotMapper.updateWithOptimisticLock(snapshot);
            if (updated > 0) {
                snapshot.setVersion((snapshot.getVersion() == null ? 0 : snapshot.getVersion()) + 1);
                updateRedisSnapshot(snapshot);
                accountChangePublisher.publishSnapshotUpdate(
                    snapshot,
                    "ACCOUNT_ADL_UPDATE",
                    "ADL",
                    bizSeq
                );
                return;
            }

            log.warn("[SnapshotService] ADL clearing update conflict, userId={}, bizSeq={}, attempt={}/{}",
                userId, bizSeq, attempt, MAX_ADL_CLEARING_UPDATE_RETRIES);
        }

        throw new RuntimeException("ADL clearing update failed after retries, userId=" + userId + ", bizSeq=" + bizSeq);
    }

    private List<String> buildLedgerIds(String bizSeq, List<AdlClearingRequest.LedgerEntry> entries) {
        List<String> ledgerIds = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            return ledgerIds;
        }
        int seq = 1;
        for (AdlClearingRequest.LedgerEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            ledgerIds.add(bizSeq + "-" + seq++);
        }
        return ledgerIds;
    }

    private AdlClearingApplyResult findCachedAdlResult(String bizSeq) {
        AdlClearingApplyResult memoResult = adlClearingMemo.get(bizSeq);
        if (memoResult != null) {
            return memoResult;
        }

        if (redisTemplate == null) {
            return null;
        }

        try {
            Object cached = redisTemplate.opsForValue().get(REDIS_ADL_CLEARING_PREFIX + bizSeq);
            if (cached == null) {
                return null;
            }
            AdlClearingApplyResult redisResult = objectMapper.readValue(cached.toString(), AdlClearingApplyResult.class);
            adlClearingMemo.put(bizSeq, redisResult);
            return redisResult;
        } catch (Exception e) {
            log.warn("[SnapshotService] Parse ADL clearing cache failed, bizSeq={}", bizSeq, e);
            return null;
        }
    }

    private void cacheAdlResult(AdlClearingApplyResult result) {
        if (result == null || result.getBizSeq() == null || result.getBizSeq().isBlank()) {
            return;
        }
        adlClearingMemo.put(result.getBizSeq(), result);
        if (redisTemplate == null) {
            return;
        }

        try {
            String payload = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(
                REDIS_ADL_CLEARING_PREFIX + result.getBizSeq(),
                payload,
                REDIS_TTL_HOURS,
                TimeUnit.HOURS
            );
        } catch (Exception e) {
            log.warn("[SnapshotService] Cache ADL clearing result failed, bizSeq={}", result.getBizSeq(), e);
        }
    }

    private AdlClearingApplyResult cloneForIdempotent(AdlClearingApplyResult source) {
        AdlClearingApplyResult copy = new AdlClearingApplyResult();
        copy.setSuccess(source.isSuccess());
        copy.setBizSeq(source.getBizSeq());
        copy.setIdempotent(true);
        copy.setMessage("ADL clearing already processed");
        if (source.getLedgerIds() != null) {
            copy.setLedgerIds(new ArrayList<>(source.getLedgerIds()));
        }
        return copy;
    }

    private BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }

    private boolean isSameMoney(BigDecimal left, BigDecimal right) {
        BigDecimal l = left == null ? BigDecimal.ZERO : left;
        BigDecimal r = right == null ? BigDecimal.ZERO : right;
        return l.subtract(r).abs().compareTo(UPNL_EPSILON) <= 0;
    }

    private BigDecimal recomputeEquity(AccountSnapshot snapshot, BigDecimal unrealizedPnl) {
        BigDecimal available = snapshot.getAvailable() == null ? BigDecimal.ZERO : snapshot.getAvailable();
        BigDecimal frozen = snapshot.getFrozen() == null ? BigDecimal.ZERO : snapshot.getFrozen();
        BigDecimal positionMargin = snapshot.getPositionMargin() == null ? BigDecimal.ZERO : snapshot.getPositionMargin();
        BigDecimal upnl = unrealizedPnl == null ? BigDecimal.ZERO : unrealizedPnl;
        return available.add(frozen).add(positionMargin).add(upnl);
    }

    private static class AdlAccountDelta {
        private BigDecimal availableDelta = BigDecimal.ZERO;
        private BigDecimal positionMarginDelta = BigDecimal.ZERO;
        private BigDecimal realizedPnlDelta = BigDecimal.ZERO;

        private boolean isNoop() {
            return availableDelta.compareTo(BigDecimal.ZERO) == 0
                && positionMarginDelta.compareTo(BigDecimal.ZERO) == 0
                && realizedPnlDelta.compareTo(BigDecimal.ZERO) == 0;
        }
    }
}
