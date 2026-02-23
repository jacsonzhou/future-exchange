package com.exchange.snapshot.service.impl;

import com.exchange.snapshot.dto.TradeEntryEvent;
import com.exchange.snapshot.entity.AccountSnapshot;
import com.exchange.snapshot.mapper.AccountSnapshotMapper;
import com.exchange.snapshot.service.AccountSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
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
    
    private static final String REDIS_KEY_PREFIX = "snapshot:";
    private static final long REDIS_TTL_HOURS = 24;
    private static final String CURRENCY = "USDT";
    
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
}
