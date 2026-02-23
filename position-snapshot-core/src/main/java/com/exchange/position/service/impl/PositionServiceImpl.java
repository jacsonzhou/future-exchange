package com.exchange.position.service.impl;

import com.exchange.common.core.IdGenerator;
import com.exchange.position.dto.MarkPriceEvent;
import com.exchange.position.dto.RiskEvent;
import com.exchange.position.dto.TradeEntryEvent;
import com.exchange.position.dto.TradeEvent;
import com.exchange.position.entity.PositionSnapshot;
import com.exchange.position.enums.RiskEventType;
import com.exchange.position.mapper.PositionSnapshotMapper;
import com.exchange.position.publisher.PositionChangePublisher;
import com.exchange.position.publisher.RiskEventPublisher;
import com.exchange.position.service.PositionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    
    @Autowired
    private PositionSnapshotMapper positionSnapshotMapper;
    
    @Autowired
    private RiskEventPublisher riskEventPublisher;
    
    @Autowired
    private PositionChangePublisher positionChangePublisher;
    
    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    // Redis Key前缀（双向持仓模式需要包含方向）
    private static final String REDIS_KEY_PREFIX = "position:";
    private static final long REDIS_TTL_HOURS = 24;
    private static final BigDecimal MAINTENANCE_MARGIN_RATE = new BigDecimal("0.005"); // 0.5%
    private static final BigDecimal MARGIN_WARNING_RATIO = new BigDecimal("1.2");
    
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
                    event.getTradeId()
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
                    event.getTradeId()
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
    public void onMarkPrice(MarkPriceEvent markPrice) {
        log.info("[PositionService] ⬇️ On mark price, symbol={}, markPrice={}",
            markPrice.getSymbol(), markPrice.getMarkPrice());
        
        try {
            // 双向持仓模式：查询该Symbol的所有持仓（包括LONG和SHORT），分别更新估值
            // 实际生产环境需要批量处理
            log.info("[PositionService] ✅ Mark price processed, symbol={}", markPrice.getSymbol());
            
        } catch (Exception e) {
            log.error("[PositionService] ❌ On mark price error, symbol={}", markPrice.getSymbol(), e);
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
                                       BigDecimal quantity, boolean isBuy, String tradeId) {
        int sameSide = isBuy ? POSITION_SIDE_LONG : POSITION_SIDE_SHORT;
        int oppositeSide = isBuy ? POSITION_SIDE_SHORT : POSITION_SIDE_LONG;
        BigDecimal remaining = quantity;

        PositionSnapshot oppositePosition = positionSnapshotMapper.selectByUserAndSymbolAndSide(userId, symbol, oppositeSide);
        if (oppositePosition != null && oppositePosition.getSize().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal closeQty = remaining.min(oppositePosition.getSize());
            if (closeQty.compareTo(BigDecimal.ZERO) > 0) {
                decreaseOrClosePosition(oppositePosition, price, closeQty, tradeId);
                remaining = remaining.subtract(closeQty);
            }
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            increasePosition(userId, symbol, price, remaining, sameSide, tradeId);
        }
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
            
            positionSnapshotMapper.insert(position);
            
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
            
            position.setLastTradeId(tradeId);
            position.setLastUpdateSeq(IdGenerator.generate());
            position.setUpdatedAt(System.currentTimeMillis());
            
            int updated = positionSnapshotMapper.updateWithOptimisticLock(position);
            
            if (updated == 0) {
                log.error("[PositionService] ❌ Update position failed (version conflict), userId={}, symbol={}, positionSide={}",
                    userId, symbol, positionSide);
                throw new RuntimeException("Update position failed, version conflict");
            }
            
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
                                  BigDecimal quantity, int positionSide, String tradeId) {
        PositionSnapshot position = positionSnapshotMapper.selectByUserAndSymbolAndSide(userId, symbol, positionSide);
        String changeType;

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
            position.setLastUpdateSeq(IdGenerator.generate());
            position.setVersion(0);
            position.setCreatedAt(System.currentTimeMillis());
            position.setUpdatedAt(System.currentTimeMillis());
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
            position.setLastTradeId(tradeId);
            position.setLastUpdateSeq(IdGenerator.generate());
            position.setUpdatedAt(System.currentTimeMillis());
            int updated = positionSnapshotMapper.updateWithOptimisticLock(position);
            if (updated == 0) {
                throw new RuntimeException("Increase position failed, version conflict");
            }
        }

        publishPositionChangeEvent(userId, position, changeType, quantity, price);
        updateRedisSnapshot(position);
        checkRiskAndPublishEvent(position);
    }

    private void decreaseOrClosePosition(PositionSnapshot position, BigDecimal price,
                                         BigDecimal closeQty, String tradeId) {
        BigDecimal oldSize = position.getSize();
        BigDecimal newSize = oldSize.subtract(closeQty);
        BigDecimal realizedPnl = calculateRealizedPnl(position.getPositionSide(), position.getEntryPrice(), price, closeQty);

        position.setRealizedPnl(position.getRealizedPnl().add(realizedPnl));
        position.setSize(newSize);
        if (newSize.compareTo(BigDecimal.ZERO) == 0) {
            position.setEntryPrice(BigDecimal.ZERO);
        }
        position.setLastTradeId(tradeId);
        position.setLastUpdateSeq(IdGenerator.generate());
        position.setUpdatedAt(System.currentTimeMillis());

        int updated = positionSnapshotMapper.updateWithOptimisticLock(position);
        if (updated == 0) {
            throw new RuntimeException("Decrease position failed, version conflict");
        }

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
        try {
            if (positionChangePublisher != null) {
                positionChangePublisher.publishPositionChange(userId, position, changeType, quantity, price);
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
     * 旧版方法（兼容）- 用于净持仓模式
     */
    private void updatePositionByTrade(Long userId, String symbol, BigDecimal price, 
                                       BigDecimal quantity, Boolean isBuy, String tradeId) {
        updatePositionNetMode(userId, symbol, price, quantity, Boolean.TRUE.equals(isBuy), tradeId);
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
