package com.exchange.position.service.impl;

import com.exchange.common.core.IdGenerator;
import com.exchange.position.dto.AccountUpnlSnapshot;
import com.exchange.position.dto.MarkPriceEvent;
import com.exchange.position.dto.RiskEvent;
import com.exchange.position.dto.TradeEntryEvent;
import com.exchange.position.dto.TradeEvent;
import com.exchange.position.entity.PositionSnapshot;
import com.exchange.position.enums.RiskEventType;
import com.exchange.position.mapper.AccountSnapshotMirrorMapper;
import com.exchange.position.mapper.PositionSnapshotMapper;
import com.exchange.position.publisher.PositionChangePublisher;
import com.exchange.position.publisher.RiskEventPublisher;
import com.exchange.position.service.PositionService;
import com.exchange.position.service.support.ActivePositionIndex;
import com.exchange.position.service.support.MarkPriceBatchUpdater;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Position Service 实现（生产级 - 双向持仓模式Hedge Mode）
 * 
 * 🔥 核心变化（双向持仓模式）：
 * 1. 同一个用户可以在同一交易对上同时持有多头和空头
 * 2. 根据订单方向（BUY/SELL）和当前持仓情况确定操作方向
 * 3. size 始终为正数，方向由 positionSide 决定
 * 
 * 双向持仓模式逻辑：
 * - BUY订单：增加LONG持仓（新开多仓或加仓多仓）
 * - SELL订单：增加SHORT持仓（新开空仓或加仓空仓）
 * - 平仓逻辑：需要指定平哪个方向的仓位
 * 
 * 对标：Binance Hedge Mode / OKX 双向持仓
 */
@Slf4j
@Service
public class PositionServiceImpl implements PositionService {
    private static final Integer USER_POSITION_MARGIN_ACCOUNT_TYPE = 3;
    private static final int ACCOUNT_UPNL_SYNC_MAX_RETRIES = 3;
    
    @Autowired
    private PositionSnapshotMapper positionSnapshotMapper;

    @Autowired(required = false)
    private AccountSnapshotMirrorMapper accountSnapshotMirrorMapper;
    
    @Autowired
    private RiskEventPublisher riskEventPublisher;
    
    @Autowired
    private PositionChangePublisher positionChangePublisher;

    @Autowired
    private ActivePositionIndex activePositionIndex;

    @Autowired
    private MarkPriceBatchUpdater markPriceBatchUpdater;
    
    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;
    
    // Redis Key前缀（双向持仓模式需要包含方向）
    private static final String REDIS_KEY_PREFIX = "position:";
    private static final long REDIS_TTL_HOURS = 24;
    private static final BigDecimal MAINTENANCE_MARGIN_RATE = new BigDecimal("0.005"); // 0.5%
    private static final BigDecimal DEFAULT_LEVERAGE = new BigDecimal("10");
    
    // 持仓方向常量
    private static final int POSITION_SIDE_LONG = 1;
    private static final int POSITION_SIDE_SHORT = 2;
    
    /**
     * 消费TradeEvent更新持仓（核心方法 - 已弃用）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @Deprecated
    public void onTrade(TradeEvent trade) {
        log.warn("[PositionService] ⚠️ onTrade is deprecated, use onTradeEntryEvent instead. tradeId={}", 
            trade.getTradeId());
        
        try {
            // 1. 更新Maker持仓
            updatePositionByTrade(
                trade.getMakerUserId(),
                trade.getSymbol(),
                trade.getPrice(),
                trade.getQuantity(),
                trade.getIsMakerBuy(),
                trade.getTradeId()
            );
            
            // 2. 更新Taker持仓
            updatePositionByTrade(
                trade.getTakerUserId(),
                trade.getSymbol(),
                trade.getPrice(),
                trade.getQuantity(),
                !trade.getIsMakerBuy(),
                trade.getTradeId()
            );
            
            log.info("[PositionService] ✅ Trade processed, tradeId={}", trade.getTradeId());
            
        } catch (Exception e) {
            log.error("[PositionService] ❌ On trade error, tradeId={}", trade.getTradeId(), e);
            throw new RuntimeException("Process trade failed", e);
        }
    }
    
    /**
     * 消费TradeEntryEvent更新持仓（核心方法 - 双向持仓模式）
     * 
     * 🔥 双向持仓模式逻辑：
     * - BUY订单 → 操作LONG持仓（正数size）
     * - SELL订单 → 操作SHORT持仓（正数size）
     * - 多空独立计算，互不干扰
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onTradeEntryEvent(TradeEntryEvent event) {
        log.info("[PositionService] ⬇️ On trade entry event, tradeId={}, symbol={}, bizSeq={}, price={}, qty={}",
            event.getTradeId(), event.getSymbol(), event.getBizSeq(),
            event.getPrice(), event.getQuantity());

        try {
            if (event.getEntries() == null || event.getEntries().isEmpty()) {
                log.warn("[PositionService] ⚠️ Empty entries, tradeId={}", event.getTradeId());
                return;
            }

            // SYSTEM事件（如冻结/解冻）不涉及持仓变更，直接跳过
            if ("SYSTEM".equalsIgnoreCase(event.getSymbol())) {
                log.debug("[PositionService] Skip non-position system event, tradeId={}", event.getTradeId());
                return;
            }

            boolean hasPositionEntry = event.getEntries().stream()
                .anyMatch(this::isPositionImpactEntry);
            if (!hasPositionEntry) {
                log.debug("[PositionService] Skip event without position entry, tradeId={}", event.getTradeId());
                return;
            }

            // 验证必要的成交信息
            if (event.getPrice() == null || event.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[PositionService] ⚠️ Invalid price in event, skip. tradeId={}, price={}",
                    event.getTradeId(), event.getPrice());
                return;
            }

            if (event.getQuantity() == null || event.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[PositionService] ⚠️ Invalid quantity in event, skip. tradeId={}, quantity={}",
                    event.getTradeId(), event.getQuantity());
                return;
            }

            boolean makerIsBuy = Boolean.TRUE.equals(event.getIsBuyerMaker());
            boolean takerIsBuy = !makerIsBuy;

            // 1. 处理Maker持仓
            if (event.getMakerUserId() != null) {
                updatePositionNetMode(
                    event.getMakerUserId(),
                    event.getSymbol(),
                    event.getPrice(),
                    event.getQuantity(),
                    makerIsBuy,
                    event.getTradeId(),
                    event.getBizSeq()
                );
                log.info("[PositionService] ✅ Maker position updated, userId={}, isBuy={}",
                    event.getMakerUserId(), makerIsBuy);
            }

            // 2. 处理Taker持仓
            if (event.getTakerUserId() != null) {
                updatePositionNetMode(
                    event.getTakerUserId(),
                    event.getSymbol(),
                    event.getPrice(),
                    event.getQuantity(),
                    takerIsBuy,
                    event.getTradeId(),
                    event.getBizSeq()
                );
                log.info("[PositionService] ✅ Taker position updated, userId={}, isBuy={}",
                    event.getTakerUserId(), takerIsBuy);
            }

            log.info("[PositionService] ✅ Trade entry event processed, tradeId={}", event.getTradeId());

        } catch (Exception e) {
            log.error("[PositionService] ❌ On trade entry event error, tradeId={}", event.getTradeId(), e);
            throw new RuntimeException("Process trade entry event failed", e);
        }
    }
    
    /**
     * 消费MarkPriceEvent更新估值（核心方法）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onMarkPrice(MarkPriceEvent markPrice) {
        String symbol = markPrice != null ? markPrice.getSymbol() : null;
        BigDecimal incomingMarkPrice = markPrice != null ? markPrice.getMarkPrice() : null;
        log.info("[PositionService] ⬇️ On mark price, symbol={}, markPrice={}",
            symbol, incomingMarkPrice);
        
        try {
            if (markPrice == null || symbol == null || symbol.isBlank()) {
                log.warn("[PositionService] ⚠️ Skip mark price update, invalid symbol");
                return;
            }

            if (incomingMarkPrice == null || incomingMarkPrice.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("[PositionService] ⚠️ Skip mark price update, invalid price, symbol={}, markPrice={}",
                    symbol, incomingMarkPrice);
                return;
            }

            List<PositionSnapshot> positions = activePositionIndex.getActivePositions(symbol);
            if (positions == null || positions.isEmpty()) {
                log.debug("[PositionService] No active position for symbol={}, skip mark update", symbol);
                return;
            }

            int updatedCount = 0;
            int skippedCount = 0;
            Set<Long> affectedUserIds = new HashSet<>();
            List<PositionSnapshot> pendingUpdates = new ArrayList<>(positions.size());
            String markPriceId = markPrice.getMarkPriceId();
            String indexPriceId = markPrice.getIndexPriceId();
            if (indexPriceId == null || indexPriceId.isBlank()) {
                indexPriceId = markPriceId;
            }

            for (PositionSnapshot position : positions) {
                if (position == null || !position.hasPosition()) {
                    skippedCount++;
                    continue;
                }

                if (markPrice.getMarkPriceId() != null
                    && markPrice.getMarkPriceId().equals(position.getLastMarkPriceId())) {
                    skippedCount++;
                    continue;
                }

                BigDecimal unrealizedPnl = calculateUnrealizedPnl(position, incomingMarkPrice);
                BigDecimal marginRatio = calculateMarginRatioByMarkPrice(position, incomingMarkPrice, unrealizedPnl);
                BigDecimal liquidationPrice = calculateLiquidationPriceByMarkPrice(position);

                position.setUnrealizedPnl(unrealizedPnl);
                position.setMarginRatio(marginRatio);
                position.setLiquidationPrice(liquidationPrice);
                if (markPrice.getMarkPriceId() != null && !markPrice.getMarkPriceId().isBlank()) {
                    position.setLastMarkPriceId(markPrice.getMarkPriceId());
                }
                position.setLastUpdateSeq(IdGenerator.generate());
                position.setUpdatedAt(System.currentTimeMillis());
                pendingUpdates.add(position);
            }

            if (pendingUpdates.isEmpty()) {
                log.info("[PositionService] ✅ Mark price processed, symbol={}, updated={}, skipped={}",
                    symbol, updatedCount, skippedCount);
                return;
            }

            int[] batchResults = markPriceBatchUpdater.batchUpdateMarkFields(pendingUpdates);
            for (int i = 0; i < pendingUpdates.size(); i++) {
                PositionSnapshot updatedSnapshot = pendingUpdates.get(i);
                int rowState = i < batchResults.length ? batchResults[i] : 0;
                if (!isBatchWriteSuccess(rowState)) {
                    log.warn("[PositionService] ⚠️ Skip mark update due to version conflict, userId={}, symbol={}, side={}",
                        updatedSnapshot.getUserId(), updatedSnapshot.getSymbol(), updatedSnapshot.getPositionSide());

                    PositionSnapshot latest = positionSnapshotMapper.selectById(updatedSnapshot.getId());
                    if (latest != null) {
                        activePositionIndex.upsert(latest);
                    } else {
                        activePositionIndex.remove(updatedSnapshot.getId(), updatedSnapshot.getSymbol());
                    }
                    skippedCount++;
                    continue;
                }

                updatedSnapshot.setVersion((updatedSnapshot.getVersion() == null ? 0 : updatedSnapshot.getVersion()) + 1);
                activePositionIndex.upsert(updatedSnapshot);

                updateRedisSnapshot(updatedSnapshot);
                publishPositionChangeEvent(
                    updatedSnapshot.getUserId(),
                    updatedSnapshot,
                    PositionChangePublisher.CHANGE_TYPE_MARK_PRICE_UPDATE,
                    BigDecimal.ZERO,
                    incomingMarkPrice,
                    incomingMarkPrice,
                    markPriceId,
                    indexPriceId
                );
                checkRiskAndPublishEvent(updatedSnapshot);
                affectedUserIds.add(updatedSnapshot.getUserId());
                updatedCount++;
            }

            syncAccountUnrealizedPnl(affectedUserIds);

            log.info("[PositionService] ✅ Mark price processed, symbol={}, updated={}, skipped={}",
                symbol, updatedCount, skippedCount);
            
        } catch (Exception e) {
            log.error("[PositionService] ❌ On mark price error, symbol={}", symbol, e);
            throw new RuntimeException("Process mark price event failed", e);
        }
    }
    
    /**
     * 查询用户持仓（双向持仓模式）
     */
    @Override
    public PositionSnapshot queryPosition(Long userId, String symbol, Integer positionSide) {
        // 1. 先查Redis
        if (redisTemplate != null) {
            PositionSnapshot snapshot = getFromRedis(userId, symbol, positionSide);
            if (snapshot != null) {
                log.debug("[PositionService] ✅ Cache hit, userId={}, symbol={}, positionSide={}", 
                    userId, symbol, positionSide);
                return snapshot;
            }
        }
        
        // 2. 查MySQL
        PositionSnapshot snapshot = positionSnapshotMapper.selectByUserAndSymbolAndSide(userId, symbol, positionSide);
        if (snapshot != null) {
            activePositionIndex.upsert(snapshot);
        }
        
        if (snapshot != null && redisTemplate != null) {
            // 3. 回写Redis
            updateRedisSnapshot(snapshot);
        }
        
        return snapshot;
    }
    
    /**
     * 查询用户在某个交易对上的所有持仓（双向持仓模式）
     */
    @Override
    public List<PositionSnapshot> queryPositionsBySymbol(Long userId, String symbol) {
        return positionSnapshotMapper.selectByUserAndSymbol(userId, symbol);
    }
    
    /**
     * 查询用户所有持仓（双向持仓模式）
     */
    @Override
    public List<PositionSnapshot> queryAllPositions(Long userId) {
        return positionSnapshotMapper.selectAllByUser(userId);
    }
    
    /**
     * 查询用户的所有多头持仓
     */
    @Override
    public List<PositionSnapshot> queryLongPositions(Long userId) {
        return positionSnapshotMapper.selectLongPositions(userId);
    }
    
    /**
     * 查询用户的所有空头持仓
     */
    @Override
    public List<PositionSnapshot> queryShortPositions(Long userId) {
        return positionSnapshotMapper.selectShortPositions(userId);
    }
    
    /**
     * 计算未实现盈亏（双向持仓模式）
     */
    @Override
    public BigDecimal calculateUnrealizedPnl(PositionSnapshot position, BigDecimal markPrice) {
        if (position.getSize().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        
        if (position.isLong()) {
            // 多头：(markPrice - entryPrice) * size
            return markPrice.subtract(position.getEntryPrice())
                .multiply(position.getSize());
        } else {
            // 空头：(entryPrice - markPrice) * size
            return position.getEntryPrice().subtract(markPrice)
                .multiply(position.getSize());
        }
    }
    
    /**
     * 计算保证金率
     */
    @Override
    public BigDecimal calculateMarginRatio(PositionSnapshot position, BigDecimal equity, BigDecimal maintenanceMarginRate) {
        // maintenanceMargin = size * markPrice * maintenanceMarginRate
        BigDecimal maintenanceMargin = position.getSize()
            .multiply(position.getEntryPrice())
            .multiply(maintenanceMarginRate);
        
        if (maintenanceMargin.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        
        return equity.divide(maintenanceMargin, 4, RoundingMode.DOWN);
    }
    
    /**
     * 计算强平价
     */
    @Override
    public BigDecimal calculateLiquidationPrice(PositionSnapshot position, BigDecimal equity, BigDecimal maintenanceMarginRate) {
        if (position.getSize().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        
        BigDecimal maintenanceMargin = position.getSize()
            .multiply(position.getEntryPrice())
            .multiply(maintenanceMarginRate);
        
        if (position.isLong()) {
            // 多头：entryPrice - (equity - maintenanceMargin) / size
            return position.getEntryPrice().subtract(
                equity.subtract(maintenanceMargin).divide(position.getSize(), 8, RoundingMode.DOWN)
            );
        } else {
            // 空头：entryPrice + (equity - maintenanceMargin) / size
            return position.getEntryPrice().add(
                equity.subtract(maintenanceMargin).divide(position.getSize(), 8, RoundingMode.DOWN)
            );
        }
    }
    
    /**
     * 计算净持仓（双向持仓模式）
     * netPosition = LONG - SHORT
     */
    @Override
    public BigDecimal calculateNetPosition(Long userId, String symbol) {
        return positionSnapshotMapper.selectNetPosition(userId, symbol);
    }
    
    /**
     * 更新Redis快照（双向持仓模式）
     */
    @Override
    public void updateRedisSnapshot(PositionSnapshot snapshot) {
        if (redisTemplate == null) {
            return;
        }
        
        try {
            // 双向持仓模式：Redis Key包含positionSide
            String key = REDIS_KEY_PREFIX + snapshot.getUserId() + ":" + snapshot.getSymbol() + ":" + snapshot.getPositionSide();
            
            Map<String, Object> map = new HashMap<>();
            map.put("positionSide", snapshot.getPositionSide());
            map.put("size", snapshot.getSize().toString());
            map.put("entryPrice", snapshot.getEntryPrice().toString());
            map.put("unrealizedPnl", snapshot.getUnrealizedPnl() != null ? 
                snapshot.getUnrealizedPnl().toString() : "0");
            map.put("realizedPnl", snapshot.getRealizedPnl() != null ? 
                snapshot.getRealizedPnl().toString() : "0");
            map.put("marginRatio", snapshot.getMarginRatio() != null ? 
                snapshot.getMarginRatio().toString() : "0");
            map.put("liquidationPrice", snapshot.getLiquidationPrice() != null ? 
                snapshot.getLiquidationPrice().toString() : "0");
            map.put("lastUpdateSeq", snapshot.getLastUpdateSeq().toString());
            map.put("updatedAt", snapshot.getUpdatedAt().toString());
            
            redisTemplate.opsForHash().putAll(key, map);
            redisTemplate.expire(key, REDIS_TTL_HOURS, TimeUnit.HOURS);
            
            log.debug("[PositionService] ✅ Redis updated, userId={}, symbol={}, positionSide={}", 
                snapshot.getUserId(), snapshot.getSymbol(), snapshot.getPositionSide());
            
        } catch (Exception e) {
            log.error("[PositionService] ❌ Update Redis error, userId={}, symbol={}", 
                snapshot.getUserId(), snapshot.getSymbol(), e);
        }
    }
    
    /**
     * 从Redis获取快照（双向持仓模式）
     */
    @Override
    public PositionSnapshot getFromRedis(Long userId, String symbol, Integer positionSide) {
        if (redisTemplate == null) {
            return null;
        }
        
        try {
            // 双向持仓模式：Redis Key包含positionSide
            String key = REDIS_KEY_PREFIX + userId + ":" + symbol + ":" + positionSide;
            Map<Object, Object> map = redisTemplate.opsForHash().entries(key);
            
            if (map == null || map.isEmpty()) {
                return null;
            }
            
            PositionSnapshot snapshot = new PositionSnapshot();
            snapshot.setUserId(userId);
            snapshot.setSymbol(symbol);
            snapshot.setPositionSide(Integer.valueOf(map.get("positionSide").toString()));
            snapshot.setSize(new BigDecimal(map.get("size").toString()));
            snapshot.setEntryPrice(new BigDecimal(map.get("entryPrice").toString()));
            snapshot.setUnrealizedPnl(new BigDecimal(map.get("unrealizedPnl").toString()));
            snapshot.setRealizedPnl(new BigDecimal(map.get("realizedPnl").toString()));
            snapshot.setMarginRatio(new BigDecimal(map.get("marginRatio").toString()));
            snapshot.setLiquidationPrice(new BigDecimal(map.get("liquidationPrice").toString()));
            snapshot.setLastUpdateSeq(Long.valueOf(map.get("lastUpdateSeq").toString()));
            snapshot.setUpdatedAt(Long.valueOf(map.get("updatedAt").toString()));
            
            return snapshot;
            
        } catch (Exception e) {
            log.warn("[PositionService] ⚠️ Get from Redis error, userId={}, symbol={}, positionSide={}", 
                userId, symbol, positionSide, e);
            return null;
        }
    }
    
    // ==================== 私有辅助方法 ====================

    /**
     * 净持仓更新逻辑：
     * 1. 先冲减反向仓位（如果存在）
     * 2. 剩余数量再开/加同向仓位
     */
    private void updatePositionNetMode(Long userId, String symbol, BigDecimal price,
                                       BigDecimal quantity, boolean isBuy, String tradeId, Long bizSeq) {
        final int maxRetry = 3;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                doUpdatePositionNetMode(userId, symbol, price, quantity, isBuy, tradeId, bizSeq);
                return;
            } catch (RuntimeException ex) {
                if (!isVersionConflict(ex) || attempt == maxRetry) {
                    throw ex;
                }
                log.warn("[PositionService] ⚠️ Version conflict in net-mode update, retry {}/{}, userId={}, symbol={}, tradeId={}, bizSeq={}",
                    attempt, maxRetry, userId, symbol, tradeId, bizSeq);
            }
        }
    }

    private void doUpdatePositionNetMode(Long userId, String symbol, BigDecimal price,
                                         BigDecimal quantity, boolean isBuy, String tradeId, Long bizSeq) {
        int sameSide = isBuy ? POSITION_SIDE_LONG : POSITION_SIDE_SHORT;
        int oppositeSide = isBuy ? POSITION_SIDE_SHORT : POSITION_SIDE_LONG;
        BigDecimal remaining = quantity;

        PositionSnapshot sameSidePosition = positionSnapshotMapper.selectByUserAndSymbolAndSide(userId, symbol, sameSide);
        PositionSnapshot oppositePosition = positionSnapshotMapper.selectByUserAndSymbolAndSide(userId, symbol, oppositeSide);

        if (isDuplicateOrStaleTradeEvent(sameSidePosition, tradeId, bizSeq)
            || isDuplicateOrStaleTradeEvent(oppositePosition, tradeId, bizSeq)) {
            log.warn("[PositionService] ⚠️ Skip duplicate/stale trade event, userId={}, symbol={}, tradeId={}, bizSeq={}",
                userId, symbol, tradeId, bizSeq);
            return;
        }

        if (oppositePosition != null && oppositePosition.getSize().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal closeQty = remaining.min(oppositePosition.getSize());
            if (closeQty.compareTo(BigDecimal.ZERO) > 0) {
                decreaseOrClosePosition(oppositePosition, price, closeQty, tradeId, bizSeq);
                remaining = remaining.subtract(closeQty);
            }
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            increasePosition(userId, symbol, price, remaining, sameSide, tradeId, bizSeq);
        }
    }

    private boolean isDuplicateOrStaleTradeEvent(PositionSnapshot position, String tradeId, Long bizSeq) {
        if (position == null) {
            return false;
        }
        if (tradeId != null && tradeId.equals(position.getLastTradeId())) {
            return true;
        }
        if (bizSeq != null && bizSeq > 0 && position.getLastUpdateSeq() != null) {
            return position.getLastUpdateSeq() >= bizSeq;
        }
        return false;
    }

    private long resolveUpdateSeq(Long bizSeq) {
        if (bizSeq != null && bizSeq > 0) {
            return bizSeq;
        }
        return IdGenerator.generate();
    }

    private boolean isVersionConflict(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        String message = throwable.getMessage();
        if (message != null && message.toLowerCase().contains("version conflict")) {
            return true;
        }
        return isVersionConflict(throwable.getCause());
    }

    private boolean isBatchWriteSuccess(int rowState) {
        return rowState > 0 || rowState == Statement.SUCCESS_NO_INFO;
    }

    /**
     * 双向持仓模式：根据Trade更新持仓
     * 
     * 🔥 核心逻辑：
     * - positionSide = 1 (LONG): 买入开仓/加仓，卖出平仓
     * - positionSide = 2 (SHORT): 卖出开仓/加仓，买入平仓
     * 
     * @param userId 用户ID
     * @param symbol 交易对
     * @param price 成交价格
     * @param quantity 成交数量
     * @param positionSide 持仓方向（1=LONG, 2=SHORT）
     * @param tradeId 成交ID
     */
    private void updatePositionByTradeHedgeMode(Long userId, String symbol, BigDecimal price, 
                                                 BigDecimal quantity, int positionSide, String tradeId) {
        PositionSnapshot position = positionSnapshotMapper.selectByUserAndSymbolAndSide(userId, symbol, positionSide);
        
        if (position == null) {
            // 创建新持仓（开仓）
            position = new PositionSnapshot();
            position.setUserId(userId);
            position.setSymbol(symbol);
            position.setPositionSide(positionSide);
            position.setSize(quantity); // 双向持仓模式：size始终为正
            position.setEntryPrice(price);
            position.setUnrealizedPnl(BigDecimal.ZERO);
            position.setRealizedPnl(BigDecimal.ZERO);
            position.setLastTradeId(tradeId);
            position.setLastUpdateSeq(IdGenerator.generate());
            position.setVersion(0);
            position.setCreatedAt(System.currentTimeMillis());
            position.setUpdatedAt(System.currentTimeMillis());
            recalculateRiskFields(position, price);
            
            positionSnapshotMapper.insert(position);
            activePositionIndex.upsert(position);
            
            // 发布持仓变更事件（开仓）
            publishPositionChangeEvent(userId, position, PositionChangePublisher.CHANGE_TYPE_OPEN, quantity, price);
            
            log.info("[PositionService] ✅ Position created (Hedge Mode), userId={}, symbol={}, positionSide={}, size={}",
                userId, symbol, positionSide, position.getSize());
        } else {
            // 更新现有持仓
            BigDecimal oldSize = position.getSize();
            BigDecimal newSize = oldSize.add(quantity);
            String changeType;
            
            if (oldSize.compareTo(BigDecimal.ZERO) == 0) {
                // 重新开仓
                changeType = PositionChangePublisher.CHANGE_TYPE_OPEN;
                position.setSize(quantity);
                position.setEntryPrice(price);
            } else if (newSize.compareTo(BigDecimal.ZERO) > 0) {
                // 加仓（平均成本计算）
                changeType = PositionChangePublisher.CHANGE_TYPE_INCREASE;
                BigDecimal oldValue = position.getEntryPrice().multiply(oldSize);
                BigDecimal addValue = price.multiply(quantity);
                position.setEntryPrice(oldValue.add(addValue).divide(newSize, 8, RoundingMode.HALF_UP));
                position.setSize(newSize);
            } else if (newSize.compareTo(BigDecimal.ZERO) == 0) {
                // 完全平仓
                changeType = PositionChangePublisher.CHANGE_TYPE_CLOSE;
                BigDecimal closedSize = oldSize;
                BigDecimal realizedPnl = calculateRealizedPnl(positionSide, position.getEntryPrice(), price, closedSize);
                position.setRealizedPnl(position.getRealizedPnl().add(realizedPnl));
                position.setSize(BigDecimal.ZERO);
                position.setEntryPrice(BigDecimal.ZERO);
            } else {
                // 过度交易（先平掉现有仓位，再开反向仓）
                // 这里简化处理，实际应该拆分成两笔交易
                changeType = PositionChangePublisher.CHANGE_TYPE_DECREASE;
                BigDecimal closedSize = oldSize;
                BigDecimal realizedPnl = calculateRealizedPnl(positionSide, position.getEntryPrice(), price, closedSize);
                position.setRealizedPnl(position.getRealizedPnl().add(realizedPnl));
                
                // 反向开仓
                position.setSize(newSize.abs());
                position.setEntryPrice(price);
                position.setPositionSide(positionSide == POSITION_SIDE_LONG ? POSITION_SIDE_SHORT : POSITION_SIDE_LONG);
            }
            
            recalculateRiskFields(position, price);
            position.setLastTradeId(tradeId);
            position.setLastUpdateSeq(IdGenerator.generate());
            position.setUpdatedAt(System.currentTimeMillis());
            
            int updated = positionSnapshotMapper.updateWithOptimisticLock(position);
            
            if (updated == 0) {
                log.error("[PositionService] ❌ Update position failed (version conflict), userId={}, symbol={}, positionSide={}",
                    userId, symbol, positionSide);
                throw new RuntimeException("Update position failed, version conflict");
            }
            position.setVersion((position.getVersion() == null ? 0 : position.getVersion()) + 1);

            activePositionIndex.upsert(position);
            
            // 发布持仓变更事件
            publishPositionChangeEvent(userId, position, changeType, quantity, price);
            
            log.info("[PositionService] ✅ Position updated (Hedge Mode), userId={}, symbol={}, positionSide={}, oldSize={}, newSize={}",
                userId, symbol, positionSide, oldSize, position.getSize());
        }
        
        // 更新Redis
        updateRedisSnapshot(position);
        
        // 检查风险并发布事件
        checkRiskAndPublishEvent(position);
    }

    private void increasePosition(Long userId, String symbol, BigDecimal price,
                                  BigDecimal quantity, int positionSide, String tradeId, Long bizSeq) {
        PositionSnapshot position = positionSnapshotMapper.selectByUserAndSymbolAndSide(userId, symbol, positionSide);
        String changeType;
        long updateSeq = resolveUpdateSeq(bizSeq);

        if (position == null) {
            position = new PositionSnapshot();
            position.setUserId(userId);
            position.setSymbol(symbol);
            position.setPositionSide(positionSide);
            position.setSize(quantity);
            position.setEntryPrice(price);
            position.setUnrealizedPnl(BigDecimal.ZERO);
            position.setRealizedPnl(BigDecimal.ZERO);
            position.setLastTradeId(tradeId);
            position.setLastUpdateSeq(updateSeq);
            position.setVersion(0);
            position.setCreatedAt(System.currentTimeMillis());
            position.setUpdatedAt(System.currentTimeMillis());
            recalculateRiskFields(position, price);
            positionSnapshotMapper.insert(position);
            changeType = PositionChangePublisher.CHANGE_TYPE_OPEN;
        } else {
            BigDecimal oldSize = position.getSize();
            BigDecimal newSize = oldSize.add(quantity);

            if (oldSize.compareTo(BigDecimal.ZERO) == 0) {
                changeType = PositionChangePublisher.CHANGE_TYPE_OPEN;
                position.setEntryPrice(price);
            } else {
                changeType = PositionChangePublisher.CHANGE_TYPE_INCREASE;
                BigDecimal oldValue = position.getEntryPrice().multiply(oldSize);
                BigDecimal addValue = price.multiply(quantity);
                position.setEntryPrice(oldValue.add(addValue).divide(newSize, 8, RoundingMode.HALF_UP));
            }

            position.setSize(newSize);
            recalculateRiskFields(position, price);
            position.setLastTradeId(tradeId);
            position.setLastUpdateSeq(updateSeq);
            position.setUpdatedAt(System.currentTimeMillis());
            int updated = positionSnapshotMapper.updateWithOptimisticLock(position);
            if (updated == 0) {
                throw new RuntimeException("Increase position failed, version conflict");
            }
            position.setVersion((position.getVersion() == null ? 0 : position.getVersion()) + 1);
        }

        activePositionIndex.upsert(position);
        publishPositionChangeEvent(userId, position, changeType, quantity, price);
        updateRedisSnapshot(position);
        checkRiskAndPublishEvent(position);
    }

    private void decreaseOrClosePosition(PositionSnapshot position, BigDecimal price,
                                         BigDecimal closeQty, String tradeId, Long bizSeq) {
        BigDecimal oldSize = position.getSize();
        BigDecimal newSize = oldSize.subtract(closeQty);
        BigDecimal realizedPnl = calculateRealizedPnl(position.getPositionSide(), position.getEntryPrice(), price, closeQty);

        position.setRealizedPnl(position.getRealizedPnl().add(realizedPnl));
        position.setSize(newSize);
        if (newSize.compareTo(BigDecimal.ZERO) == 0) {
            position.setEntryPrice(BigDecimal.ZERO);
        }
        recalculateRiskFields(position, price);
        position.setLastTradeId(tradeId);
        position.setLastUpdateSeq(resolveUpdateSeq(bizSeq));
        position.setUpdatedAt(System.currentTimeMillis());

        int updated = positionSnapshotMapper.updateWithOptimisticLock(position);
        if (updated == 0) {
            throw new RuntimeException("Decrease position failed, version conflict");
        }
        position.setVersion((position.getVersion() == null ? 0 : position.getVersion()) + 1);

        activePositionIndex.upsert(position);
        String changeType = newSize.compareTo(BigDecimal.ZERO) == 0
            ? PositionChangePublisher.CHANGE_TYPE_CLOSE
            : PositionChangePublisher.CHANGE_TYPE_DECREASE;
        publishPositionChangeEvent(position.getUserId(), position, changeType, closeQty, price);
        updateRedisSnapshot(position);
        checkRiskAndPublishEvent(position);
    }
    
    /**
     * 发布持仓变更事件
     */
    private void publishPositionChangeEvent(Long userId, PositionSnapshot position, String changeType, BigDecimal quantity, BigDecimal price) {
        publishPositionChangeEvent(userId, position, changeType, quantity, price, price, null, null);
    }

    /**
     * 发布持仓变更事件（显式携带 markPrice）
     */
    private void publishPositionChangeEvent(Long userId, PositionSnapshot position, String changeType,
                                            BigDecimal quantity, BigDecimal price, BigDecimal markPrice) {
        publishPositionChangeEvent(userId, position, changeType, quantity, price, markPrice, null, null);
    }

    /**
     * 发布持仓变更事件（显式携带 mark/index 事件位点）
     */
    private void publishPositionChangeEvent(Long userId, PositionSnapshot position, String changeType,
                                            BigDecimal quantity, BigDecimal price, BigDecimal markPrice,
                                            String markPriceId, String indexPriceId) {
        try {
            if (positionChangePublisher != null) {
                positionChangePublisher.publishPositionChange(
                    userId,
                    position,
                    changeType,
                    quantity,
                    price,
                    markPrice,
                    markPriceId,
                    indexPriceId
                );
            }
        } catch (Exception e) {
            log.warn("[PositionService] ⚠️ Failed to publish position change event, userId={}, symbol={}", 
                userId, position.getSymbol(), e);
            // 不抛异常，避免影响主流程
        }
    }
    
    /**
     * 计算已实现盈亏
     * 
     * 多头：(exitPrice - entryPrice) * closedSize
     * 空头：(entryPrice - exitPrice) * closedSize
     */
    private BigDecimal calculateRealizedPnl(int positionSide, BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal closedSize) {
        if (positionSide == POSITION_SIDE_LONG) {
            return exitPrice.subtract(entryPrice).multiply(closedSize);
        } else {
            return entryPrice.subtract(exitPrice).multiply(closedSize);
        }
    }

    /**
     * 使用 markPrice 重算保证金率（简化模型：固定10x杠杆）
     */
    private BigDecimal calculateMarginRatioByMarkPrice(PositionSnapshot position, BigDecimal markPrice, BigDecimal unrealizedPnl) {
        if (position == null || position.getSize() == null || position.getSize().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (markPrice == null || markPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal initialMargin = position.getEntryPrice()
            .multiply(position.getSize())
            .divide(DEFAULT_LEVERAGE, 8, RoundingMode.HALF_UP);
        BigDecimal equity = initialMargin.add(unrealizedPnl);
        BigDecimal maintenanceMargin = position.getSize()
            .multiply(markPrice)
            .multiply(MAINTENANCE_MARGIN_RATE);
        if (maintenanceMargin.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return equity.divide(maintenanceMargin, 8, RoundingMode.HALF_UP);
    }

    /**
     * 使用固定杠杆近似计算强平价（long/short 各自闭式解）。
     */
    private BigDecimal calculateLiquidationPriceByMarkPrice(PositionSnapshot position) {
        if (position == null
            || position.getEntryPrice() == null
            || position.getEntryPrice().compareTo(BigDecimal.ZERO) <= 0
            || position.getSize() == null
            || position.getSize().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal one = BigDecimal.ONE;
        BigDecimal leverageFactor = one.divide(DEFAULT_LEVERAGE, 8, RoundingMode.HALF_UP);

        if (position.isLong()) {
            BigDecimal denominator = one.subtract(MAINTENANCE_MARGIN_RATE);
            if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal numerator = position.getEntryPrice().multiply(one.subtract(leverageFactor));
            return numerator.divide(denominator, 8, RoundingMode.HALF_UP).max(BigDecimal.ZERO);
        }

        BigDecimal denominator = one.add(MAINTENANCE_MARGIN_RATE);
        BigDecimal numerator = position.getEntryPrice().multiply(one.add(leverageFactor));
        return numerator.divide(denominator, 8, RoundingMode.HALF_UP).max(BigDecimal.ZERO);
    }

    /**
     * 成交后同步刷新风险字段，避免在 mark 事件到来前出现 liquidationPrice=0 的窗口期。
     */
    private void recalculateRiskFields(PositionSnapshot position, BigDecimal markPrice) {
        if (position == null) {
            return;
        }
        if (!position.hasPosition()) {
            position.setUnrealizedPnl(BigDecimal.ZERO);
            position.setMarginRatio(BigDecimal.ZERO);
            position.setLiquidationPrice(BigDecimal.ZERO);
            return;
        }
        BigDecimal safeMark = markPrice;
        if (safeMark == null || safeMark.compareTo(BigDecimal.ZERO) <= 0) {
            safeMark = position.getEntryPrice();
        }
        BigDecimal unrealizedPnl = calculateUnrealizedPnl(position, safeMark);
        BigDecimal marginRatio = calculateMarginRatioByMarkPrice(position, safeMark, unrealizedPnl);
        BigDecimal liquidationPrice = calculateLiquidationPriceByMarkPrice(position);
        position.setUnrealizedPnl(unrealizedPnl);
        position.setMarginRatio(marginRatio);
        position.setLiquidationPrice(liquidationPrice);
    }

    /**
     * 持仓侧与账户侧同事务联动：
     * 在持仓 mark 更新后，立即按用户汇总回写 account_snapshot.unrealized_pnl/equity，
     * 缩短 position->account 异步窗口导致的对账偏差。
     */
    private void syncAccountUnrealizedPnl(Set<Long> userIds) {
        if (accountSnapshotMirrorMapper == null || userIds == null || userIds.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (Long userId : userIds) {
            if (userId == null || userId <= 0) {
                continue;
            }
            syncSingleAccountUnrealizedPnl(userId, now);
        }
    }

    private void syncSingleAccountUnrealizedPnl(Long userId, long now) {
        BigDecimal totalUpnl = positionSnapshotMapper.sumOpenUnrealizedPnl(userId);
        if (totalUpnl == null) {
            totalUpnl = BigDecimal.ZERO;
        }

        for (int attempt = 1; attempt <= ACCOUNT_UPNL_SYNC_MAX_RETRIES; attempt++) {
            AccountUpnlSnapshot snapshot = accountSnapshotMirrorMapper.selectByUserId(userId);
            if (snapshot == null) {
                return;
            }

            BigDecimal available = snapshot.getAvailable() == null ? BigDecimal.ZERO : snapshot.getAvailable();
            BigDecimal frozen = snapshot.getFrozen() == null ? BigDecimal.ZERO : snapshot.getFrozen();
            BigDecimal positionMargin = snapshot.getPositionMargin() == null ? BigDecimal.ZERO : snapshot.getPositionMargin();
            BigDecimal equity = available.add(frozen).add(positionMargin).add(totalUpnl);
            BigDecimal oldUpnl = snapshot.getUnrealizedPnl() == null ? BigDecimal.ZERO : snapshot.getUnrealizedPnl();
            BigDecimal oldEquity = snapshot.getEquity() == null ? BigDecimal.ZERO : snapshot.getEquity();

            if (oldUpnl.compareTo(totalUpnl) == 0 && oldEquity.compareTo(equity) == 0) {
                return;
            }

            int updated = accountSnapshotMirrorMapper.updateUnrealizedPnlWithOptimisticLock(
                userId,
                totalUpnl,
                equity,
                now,
                snapshot.getVersion() == null ? 0 : snapshot.getVersion()
            );
            if (updated > 0) {
                return;
            }
        }

        log.debug("[PositionService] account unrealized pnl sync conflict, userId={}", userId);
    }
    
    /**
     * 旧版方法（兼容）- 用于净持仓模式
     */
    private void updatePositionByTrade(Long userId, String symbol, BigDecimal price, 
                                       BigDecimal quantity, Boolean isBuy, String tradeId) {
        updatePositionNetMode(userId, symbol, price, quantity, Boolean.TRUE.equals(isBuy), tradeId, null);
    }

    private boolean isPositionImpactEntry(TradeEntryEvent.LedgerEntry entry) {
        if (entry == null) {
            return false;
        }

        if (entry.isPositionAssetEntry()) {
            return true;
        }

        return "TRADE".equalsIgnoreCase(entry.getBusinessType())
            && USER_POSITION_MARGIN_ACCOUNT_TYPE.equals(entry.getAccountType());
    }
    
    /**
     * 检查风险并发布事件
     */
    private void checkRiskAndPublishEvent(PositionSnapshot position) {
        if (position == null || !position.hasPosition()) {
            return;
        }
        if (position.getMarginRatio() == null) {
            return;
        }
        
        if (position.shouldLiquidate()) {
            // 触发强平告警
            RiskEvent event = new RiskEvent();
            event.setRiskEventId("R_" + IdGenerator.generate());
            event.setUserId(position.getUserId());
            event.setSymbol(position.getSymbol());
            event.setEventType(RiskEventType.LIQUIDATION_ALERT.getCode());
            event.setMarginRatio(position.getMarginRatio());
            event.setLiquidationPrice(position.getLiquidationPrice());
            event.setUnrealizedPnl(position.getUnrealizedPnl());
            event.setTimestamp(System.currentTimeMillis());
            
            riskEventPublisher.publishRiskEvent(event);
            
            log.warn("[PositionService] ⚠️ LIQUIDATION ALERT! userId={}, symbol={}, positionSide={}, marginRatio={}",
                position.getUserId(), position.getSymbol(), position.getPositionSide(), position.getMarginRatio());
            
        } else if (position.isMarginWarning()) {
            // 保证金预警
            RiskEvent event = new RiskEvent();
            event.setRiskEventId("R_" + IdGenerator.generate());
            event.setUserId(position.getUserId());
            event.setSymbol(position.getSymbol());
            event.setEventType(RiskEventType.MARGIN_WARNING.getCode());
            event.setMarginRatio(position.getMarginRatio());
            event.setLiquidationPrice(position.getLiquidationPrice());
            event.setUnrealizedPnl(position.getUnrealizedPnl());
            event.setTimestamp(System.currentTimeMillis());
            
            riskEventPublisher.publishRiskEvent(event);
            
            log.warn("[PositionService] ⚠️ MARGIN WARNING! userId={}, symbol={}, positionSide={}, marginRatio={}",
                position.getUserId(), position.getSymbol(), position.getPositionSide(), position.getMarginRatio());
        }
    }
}
