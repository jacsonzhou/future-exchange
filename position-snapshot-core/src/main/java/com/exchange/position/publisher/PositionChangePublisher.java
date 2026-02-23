package com.exchange.position.publisher;

import com.exchange.common.core.IdGenerator;
import com.exchange.position.entity.PositionSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
 * 消息格式（兼容 Binance）：
 * {
 *   "userId": 12345,
 *   "symbol": "BTCUSDT",
 *   "eventType": "POSITION_UPDATE",
 *   "timestamp": 1708326400000,
 *   "positionSide": 1,  // 1=LONG, 2=SHORT
 *   "position": {
 *     "symbol": "BTCUSDT",
 *     "side": "LONG",
 *     "quantity": "1.00000000",
 *     "entryPrice": "50000.00000000",
 *     "markPrice": "51000.00000000",
 *     "unrealizedPnl": "1000.00000000",
 *     "realizedPnl": "0",
 *     "leverage": 10,
 *     "margin": "5000.00000000"
 *   },
 *   "change": {
 *     "changeType": "OPEN",  // OPEN, CLOSE, INCREASE, DECREASE
 *     "quantity": "1.00000000",
 *     "price": "50000.00000000"
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
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("userId", userId);
            event.put("symbol", position.getSymbol());
            event.put("eventType", "POSITION_UPDATE");
            event.put("timestamp", System.currentTimeMillis());
            event.put("positionSide", position.getPositionSide());
            
            // 持仓信息
            Map<String, Object> positionMap = new HashMap<>();
            positionMap.put("symbol", position.getSymbol());
            positionMap.put("side", position.isLong() ? "LONG" : "SHORT");
            positionMap.put("quantity", position.getSize().toPlainString());
            positionMap.put("entryPrice", position.getEntryPrice().toPlainString());
            positionMap.put("unrealizedPnl", position.getUnrealizedPnl() != null ? 
                position.getUnrealizedPnl().toPlainString() : "0");
            positionMap.put("realizedPnl", position.getRealizedPnl() != null ? 
                position.getRealizedPnl().toPlainString() : "0");
            positionMap.put("leverage", 10); // 默认10倍，实际应从配置获取
            positionMap.put("margin", calculateMargin(position));
            event.put("position", positionMap);
            
            // 变更信息
            Map<String, Object> changeMap = new HashMap<>();
            changeMap.put("changeType", changeType);
            changeMap.put("quantity", changeQty.toPlainString());
            changeMap.put("price", changePrice.toPlainString());
            event.put("change", changeMap);
            
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
        publishPositionChange(userId, position, changeType, changeQty, position.getEntryPrice());
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
            .divide(new BigDecimal(leverage), 8, BigDecimal.ROUND_HALF_UP);
        return margin.toPlainString();
    }
}
