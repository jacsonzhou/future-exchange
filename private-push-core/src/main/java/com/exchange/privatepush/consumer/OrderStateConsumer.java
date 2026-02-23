package com.exchange.privatepush.consumer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.common.proto.event.PrivatePushEvent;
import com.exchange.privatepush.service.MessageDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 订单状态 Kafka 消费者
 * 
 * 🔥 核心职责：
 * 1. 消费 private-order-state Topic
 * 2. 按 userId 路由消息
 * 3. 调用 MessageDispatcher 发送到用户会话
 * 
 * Topic: private-order-state
 * 分区: userId % partitionCount
 * 
 * @author Exchange Team
 * @version 1.0.0
 */
@Slf4j
@Component
public class OrderStateConsumer {
    
    @Autowired
    private MessageDispatcher messageDispatcher;
    
    /**
     * 消费订单状态事件
     * 
     * 消息格式: PrivatePushEvent (executionReport)
     */
    @KafkaListener(
        topics = "${private.push.kafka.order-state-topic:private-order-state}",
        groupId = "${private.push.kafka.consumer-group-id:private-push-service}",
        concurrency = "10"  // 10个消费者并发处理
    )
    public void consumeOrderState(
            ConsumerRecord<String, String> record,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        String key = record.key();
        String value = record.value();
        
        log.debug("[OrderStateConsumer] Received message, topic={}, partition={}, offset={}, key={}",
                topic, partition, offset, key);
        
        try {
            // 解析消息
            PrivatePushEvent event = JSON.parseObject(value, PrivatePushEvent.class);
            
            if (event == null || event.getUserId() == null) {
                log.warn("[OrderStateConsumer] Invalid message format, offset={}", offset);
                return;
            }
            
            Long userId = event.getUserId();
            String eventType = event.getE();
            
            // 只处理 executionReport 类型
            if (!PrivatePushEvent.EventType.EXECUTION_REPORT.equals(eventType)) {
                log.debug("[OrderStateConsumer] Skip non-execution event, type={}", eventType);
                return;
            }
            
            // 转换为 JSONObject
            JSONObject data = (JSONObject) JSON.toJSON(event);
            
            // 分发消息
            messageDispatcher.sendToUser(userId, "executionReport", data);
            
            log.info("[OrderStateConsumer] Processed execution report, userId={}, orderId={}, status={}, seq={}",
                    userId, event.getI(), event.getX(), event.getSeq());
                    
        } catch (Exception e) {
            log.error("[OrderStateConsumer] Failed to process message, topic={}, offset={}",
                    topic, offset, e);
            // 不抛异常，避免消息堆积
        }
    }
}
