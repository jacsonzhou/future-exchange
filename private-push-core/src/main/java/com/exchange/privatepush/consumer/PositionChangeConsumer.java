package com.exchange.privatepush.consumer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.privatepush.service.MessageDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 持仓变化 Kafka 消费者
 * 
 * 🔥 核心职责：
 * 1. 消费 private-position-change Topic
 * 2. 按 userId 路由消息
 * 3. 调用 MessageDispatcher 发送到用户会话
 * 
 * Topic: private-position-change
 * 分区: userId % partitionCount
 * 
 * 事件来源：
 * - Position Snapshot Core: 持仓快照更新
 * - Liquidation Core: 强平触发
 * - ADL Core: ADL触发
 * 
 * @author Exchange Team
 * @version 1.0.0
 */
@Slf4j
@Component
public class PositionChangeConsumer {
    
    @Autowired
    private MessageDispatcher messageDispatcher;
    
    /**
     * 消费持仓变化事件
     * 
     * 消息格式:
     * {
     *   "userId": 12345,
     *   "symbol": "BTCUSDT",
     *   "eventType": "POSITION_UPDATE",
     *   "timestamp": 1708326400000,
     *   "position": {
     *     "symbol": "BTCUSDT",
     *     "side": "LONG",
     *     "quantity": "1.00000000",
     *     "entryPrice": "50000.00000000",
     *     "markPrice": "51000.00000000",
     *     "unrealizedPnl": "1000.00000000",
     *     "leverage": 10,
     *     "margin": "5000.00000000"
     *   },
     *   "change": {
     *     "changeType": "OPEN",
     *     "quantity": "1.00000000",
     *     "price": "50000.00000000"
     *   }
     * }
     */
    @KafkaListener(
        topics = "${private.push.kafka.position-change-topic:private-position-change}",
        groupId = "${private.push.kafka.consumer-group-id:private-push-service}",
        concurrency = "5"  // 5个消费者并发处理
    )
    public void consumePositionChange(
            ConsumerRecord<String, String> record,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        String key = record.key();
        String value = record.value();
        
        log.debug("[PositionChangeConsumer] Received message, topic={}, partition={}, offset={}, key={}",
                topic, partition, offset, key);
        
        try {
            // 解析消息
            JSONObject event = JSON.parseObject(value);
            
            if (event == null || !event.containsKey("userId")) {
                log.warn("[PositionChangeConsumer] Invalid message format, offset={}", offset);
                return;
            }
            
            Long userId = event.getLong("userId");
            String eventType = event.getString("eventType");
            
            // 构建持仓更新消息（兼容Binance格式）
            JSONObject positionUpdate = buildPositionUpdate(event);
            
            // 分发消息
            messageDispatcher.sendToUser(userId, "position", positionUpdate);
            
            log.info("[PositionChangeConsumer] Processed position change, userId={}, symbol={}, eventType={}, seq={}",
                    userId, event.getString("symbol"), eventType, positionUpdate.getLong("seq"));
                    
        } catch (Exception e) {
            log.error("[PositionChangeConsumer] Failed to process message, topic={}, offset={}",
                    topic, offset, e);
            // 不抛异常，避免消息堆积
        }
    }
    
    /**
     * 构建持仓更新消息（兼容Binance格式）
     * 
     * 格式:
     * {
     *   "e": "position",
     *   "E": 1708326400000,
     *   "s": "BTCUSDT",
     *   "ps": "LONG",
     *   "pa": "1.00000000",
     *   "ep": "50000.00000000",
     *   "mp": "51000.00000000",
     *   "up": "1000.00000000",
     *   "l": 10,
     *   "m": "5000.00000000"
     * }
     */
    private JSONObject buildPositionUpdate(JSONObject event) {
        JSONObject positionUpdate = new JSONObject();
        positionUpdate.put("e", "position");
        positionUpdate.put("E", System.currentTimeMillis());
        
        // 生成序列号
        long seq = System.currentTimeMillis() * 1000 + (System.nanoTime() % 1000);
        positionUpdate.put("seq", seq);
        
        // 如果有position对象，提取字段
        if (event.containsKey("position")) {
            JSONObject position = event.getJSONObject("position");
            positionUpdate.put("s", position.getString("symbol"));
            positionUpdate.put("ps", position.getString("side"));
            positionUpdate.put("pa", position.getString("quantity"));
            positionUpdate.put("ep", position.getString("entryPrice"));
            positionUpdate.put("mp", position.getString("markPrice"));
            positionUpdate.put("up", position.getString("unrealizedPnl"));
            positionUpdate.put("l", position.getInteger("leverage"));
            positionUpdate.put("m", position.getString("margin"));
        } else {
            // 直接从event提取
            positionUpdate.put("s", event.getString("symbol"));
            if (event.containsKey("side")) {
                positionUpdate.put("ps", event.getString("side"));
            }
            if (event.containsKey("quantity")) {
                positionUpdate.put("pa", event.getString("quantity"));
            }
        }
        
        return positionUpdate;
    }
}

