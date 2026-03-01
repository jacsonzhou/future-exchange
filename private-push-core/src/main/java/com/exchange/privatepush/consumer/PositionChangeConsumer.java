package com.exchange.privatepush.consumer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.privatepush.service.MessageDispatcher;
import com.exchange.privatepush.service.PositionDiffMergeService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
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

    @Autowired
    private PositionDiffMergeService positionDiffMergeService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${private.push.kafka.position-change-dlq-topic:private-position-change-dlq}")
    private String positionChangeDlqTopic;
    
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
            
            if (event == null) {
                log.warn("[PositionChangeConsumer] Invalid message format, offset={}", offset);
                publishDlq(topic, partition, offset, key, value, "Invalid message format: event is null");
                return;
            }

            // 统一 schema：eventType + eventTime + data（兼容老格式）
            JSONObject data = event.containsKey("data") ? event.getJSONObject("data") : event;
            if (data == null || !data.containsKey("userId")) {
                log.warn("[PositionChangeConsumer] Invalid message format, missing userId, offset={}", offset);
                publishDlq(topic, partition, offset, key, value, "Invalid message format: missing userId");
                return;
            }

            Long userId = data.getLong("userId");
            String eventType = event.getString("eventType");
            if (eventType == null) {
                eventType = data.getString("eventType");
            }
            
            // 构建持仓更新消息（兼容Binance格式）
            JSONObject positionUpdate = buildPositionUpdate(data);

            // 优先进行短窗 diff 合并推送，失败时回退为实时直推
            boolean merged = positionDiffMergeService.enqueue(userId, positionUpdate);
            if (!merged) {
                messageDispatcher.sendToUser(userId, "position", positionUpdate);
            }
            
            log.info("[PositionChangeConsumer] Processed position change, userId={}, symbol={}, eventType={}, seq={}",
                    userId, data.getString("symbol"), eventType, positionUpdate.getLong("seq"));
                    
        } catch (Exception e) {
            log.error("[PositionChangeConsumer] Failed to process message, topic={}, offset={}",
                    topic, offset, e);
            publishDlq(topic, partition, offset, key, value, e.getClass().getSimpleName() + ": " + e.getMessage());
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
    private JSONObject buildPositionUpdate(JSONObject payload) {
        JSONObject positionUpdate = new JSONObject();
        positionUpdate.put("e", "position");
        positionUpdate.put("E", System.currentTimeMillis());
        if (payload.containsKey("markPriceId")) {
            positionUpdate.put("mpi", payload.getString("markPriceId"));
        }
        if (payload.containsKey("indexPriceId")) {
            positionUpdate.put("ipi", payload.getString("indexPriceId"));
        }
        
        // 生成序列号
        long seq = System.currentTimeMillis() * 1000 + (System.nanoTime() % 1000);
        positionUpdate.put("seq", seq);
        
        // 如果有position对象，提取字段
        if (payload.containsKey("position")) {
            JSONObject position = payload.getJSONObject("position");
            positionUpdate.put("s", position.getString("symbol"));
            positionUpdate.put("ps", position.getString("side"));
            positionUpdate.put("pa", position.getString("quantity"));
            positionUpdate.put("ep", position.getString("entryPrice"));
            String markPrice = position.getString("markPrice");
            if (markPrice == null) {
                markPrice = position.getString("entryPrice");
            }
            positionUpdate.put("mp", markPrice);
            positionUpdate.put("up", position.getString("unrealizedPnl"));
            if (position.containsKey("marginRatio")) {
                positionUpdate.put("mr", position.getString("marginRatio"));
            }
            if (position.containsKey("liquidationPrice")) {
                positionUpdate.put("lp", position.getString("liquidationPrice"));
            }
            positionUpdate.put("l", position.getInteger("leverage"));
            positionUpdate.put("m", position.getString("margin"));
        } else {
            // 直接从event提取
            positionUpdate.put("s", payload.getString("symbol"));
            if (payload.containsKey("side")) {
                positionUpdate.put("ps", payload.getString("side"));
            }
            if (payload.containsKey("quantity")) {
                positionUpdate.put("pa", payload.getString("quantity"));
            }
        }
        
        return positionUpdate;
    }

    private void publishDlq(String topic, int partition, long offset, String key, String value, String error) {
        try {
            JSONObject dlq = new JSONObject();
            dlq.put("eventType", "POSITION_CHANGE_CONSUME_FAILED");
            dlq.put("eventTime", System.currentTimeMillis());

            JSONObject data = new JSONObject();
            data.put("sourceTopic", topic);
            data.put("partition", partition);
            data.put("offset", offset);
            data.put("key", key);
            data.put("rawMessage", value);
            data.put("error", error);
            dlq.put("data", data);

            kafkaTemplate.send(positionChangeDlqTopic, topic + ":" + partition, dlq.toJSONString());
        } catch (Exception ex) {
            log.error("[PositionChangeConsumer] Failed to publish DLQ message, topic={}, offset={}",
                    topic, offset, ex);
        }
    }
}
