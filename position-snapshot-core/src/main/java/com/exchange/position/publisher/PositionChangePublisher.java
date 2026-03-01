package com.exchange.position.publisher;

import com.exchange.position.entity.PositionSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Position Change Publisher（持仓变更事件发布器）
 * 
 * 🔥 核心职责：
 * 1. 持仓变更时发布事件到 Kafka
 * 2. 供 Private Push Service 消费并推送给前端
 * 3. 支持双向持仓模式（Hedge Mode）
 * 
 * Topic: private-position-change
 * 
 * 消息格式（统一事件封装）：
 * {
 *   "eventType": "POSITION_UPDATE",
 *   "eventTime": 1708326400000,
 *   "data": {
 *     "userId": 12345,
 *     "symbol": "BTCUSDT",
 *     "positionSide": 1,  // 1=LONG, 2=SHORT
 *     "position": {
 *       "symbol": "BTCUSDT",
 *       "side": "LONG",
 *       "quantity": "1.00000000",
 *       "entryPrice": "50000.00000000",
 *       "markPrice": "51000.00000000",
 *       "unrealizedPnl": "1000.00000000",
 *       "realizedPnl": "0",
 *       "leverage": 10,
 *       "margin": "5000.00000000"
 *     },
 *     "change": {
 *       "changeType": "OPEN",  // OPEN, CLOSE, INCREASE, DECREASE, MARK_PRICE_UPDATE
 *       "quantity": "1.00000000",
 *       "price": "50000.00000000"
 *     }
 *   }
 * }
 * 
 * 对标：Binance Hedge Mode / OKX 双向持仓
 */
@Slf4j
@Component
public class PositionChangePublisher {
    
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Value("${position.kafka.position-change-topic:private-position-change}")
    private String positionChangeTopic;
    
    // 变更类型常量
    public static final String CHANGE_TYPE_OPEN = "OPEN";           // 开仓
    public static final String CHANGE_TYPE_CLOSE = "CLOSE";         // 平仓
    public static final String CHANGE_TYPE_INCREASE = "INCREASE";   // 加仓
    public static final String CHANGE_TYPE_DECREASE = "DECREASE";   // 减仓
    public static final String CHANGE_TYPE_MARK_PRICE_UPDATE = "MARK_PRICE_UPDATE"; // 估值刷新
    
    /**
     * 发布持仓变更事件
     * 
     * @param userId 用户ID
     * @param position 持仓快照
     * @param changeType 变更类型
     * @param changeQty 变更数量
     * @param changePrice 变更价格
     */
    public void publishPositionChange(Long userId, PositionSnapshot position, 
                                      String changeType, BigDecimal changeQty, BigDecimal changePrice) {
        publishPositionChange(userId, position, changeType, changeQty, changePrice,
            position.getEntryPrice(), null, null);
    }

    /**
     * 发布持仓变更事件（包含 markPrice）
     */
    public void publishPositionChange(Long userId, PositionSnapshot position,
                                      String changeType, BigDecimal changeQty, BigDecimal changePrice, BigDecimal markPrice) {
        publishPositionChange(userId, position, changeType, changeQty, changePrice, markPrice, null, null);
    }

    /**
     * 发布持仓变更事件（包含 mark/index 事件位点）
     */
    public void publishPositionChange(Long userId, PositionSnapshot position,
                                      String changeType, BigDecimal changeQty, BigDecimal changePrice, BigDecimal markPrice,
                                      String markPriceId, String indexPriceId) {
        try {
            long eventTime = System.currentTimeMillis();

            Map<String, Object> data = new HashMap<>();
            data.put("userId", userId);
            data.put("symbol", position.getSymbol());
            data.put("positionSide", position.getPositionSide());
            if (markPriceId != null && !markPriceId.isBlank()) {
                data.put("markPriceId", markPriceId);
            }
            if (indexPriceId != null && !indexPriceId.isBlank()) {
                data.put("indexPriceId", indexPriceId);
            }

            BigDecimal safeMarkPrice = markPrice != null ? markPrice : position.getEntryPrice();
            BigDecimal safeChangeQty = changeQty != null ? changeQty : BigDecimal.ZERO;
            BigDecimal safeChangePrice = changePrice != null ? changePrice : position.getEntryPrice();

            // 持仓信息
            Map<String, Object> positionMap = new HashMap<>();
            positionMap.put("symbol", position.getSymbol());
            positionMap.put("side", position.isLong() ? "LONG" : "SHORT");
            positionMap.put("quantity", position.getSize().toPlainString());
            positionMap.put("entryPrice", position.getEntryPrice().toPlainString());
            positionMap.put("markPrice", safeMarkPrice.toPlainString());
            positionMap.put("unrealizedPnl", position.getUnrealizedPnl() != null ?
                position.getUnrealizedPnl().toPlainString() : "0");
            positionMap.put("realizedPnl", position.getRealizedPnl() != null ?
                position.getRealizedPnl().toPlainString() : "0");
            positionMap.put("marginRatio", position.getMarginRatio() != null ?
                position.getMarginRatio().toPlainString() : "0");
            positionMap.put("liquidationPrice", position.getLiquidationPrice() != null ?
                position.getLiquidationPrice().toPlainString() : "0");
            positionMap.put("leverage", 10); // 默认10倍，实际应从配置获取
            positionMap.put("margin", calculateMargin(position));
            data.put("position", positionMap);

            // 变更信息
            Map<String, Object> changeMap = new HashMap<>();
            changeMap.put("changeType", changeType);
            changeMap.put("quantity", safeChangeQty.toPlainString());
            changeMap.put("price", safeChangePrice.toPlainString());
            if (markPriceId != null && !markPriceId.isBlank()) {
                changeMap.put("markPriceId", markPriceId);
            }
            if (indexPriceId != null && !indexPriceId.isBlank()) {
                changeMap.put("indexPriceId", indexPriceId);
            }
            data.put("change", changeMap);

            Map<String, Object> event = new HashMap<>();
            event.put("eventType", "POSITION_UPDATE");
            event.put("eventTime", eventTime);
            event.put("data", data);
            
            String message = objectMapper.writeValueAsString(event);
            String key = String.valueOf(userId); // 按 userId 分区，确保同用户的顺序
            
            kafkaTemplate.send(positionChangeTopic, key, message);
            
            log.info("[PositionChangePublisher] ✅ Published position change, userId={}, symbol={}, positionSide={}, changeType={}",
                userId, position.getSymbol(), position.getPositionSide(), changeType);
            
        } catch (Exception e) {
            log.error("[PositionChangePublisher] ❌ Failed to publish position change, userId={}, symbol={}",
                userId, position.getSymbol(), e);
            // 不抛异常，避免影响主流程
        }
    }
    
    /**
     * 简化的持仓变更发布（使用默认价格）
     */
    public void publishPositionChange(Long userId, PositionSnapshot position, String changeType, BigDecimal changeQty) {
        publishPositionChange(userId, position, changeType, changeQty, position.getEntryPrice(), position.getEntryPrice());
    }
    
    /**
     * 计算保证金（简化版）
     */
    private String calculateMargin(PositionSnapshot position) {
        // 保证金 = 持仓数量 * 开仓价 / 杠杆
        // 这里假设杠杆为10倍
        int leverage = 10;
        BigDecimal margin = position.getSize()
            .multiply(position.getEntryPrice())
            .divide(new BigDecimal(leverage), 8, RoundingMode.HALF_UP);
        return margin.toPlainString();
    }
}
