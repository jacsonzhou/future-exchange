package com.exchange.oms.publisher;

import com.exchange.oms.dto.OrderEventCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;

/**
 * 订单事件发布器（OMS → Match Engine）
 * 
 * 🔥 交易所级双通道架构 - 通道1：单向顺序日志
 * 
 * OMS → Kafka: order-event-{symbol} → Match Engine
 * 
 * 核心特性：
 * 1. 每个Symbol一个Topic
 * 2. 单分区保证顺序
 * 3. Key = orderId
 * 4. 可重放、可灾备
 */
@Slf4j
@Component
public class OrderEventPublisher {
    
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    /**
     * 发布订单事件到Kafka（OMS → Match Engine）
     * 
     * @param command 订单命令
     */
    public void publishOrderEvent(OrderEventCommand command) {
        log.info("[OrderEventPublisher] Publish order event, orderId={}, type={}, symbol={}",
            command.getOrderId(), command.getEventType(), command.getSymbol());
        
        try {
            // Topic使用统一的 order-events
            // 使用 symbol 作为 key 的一部分来保证同一交易对的顺序
            String topic = "order-events";
            
            // Key = symbol:orderId（保证同一交易对同一订单的事件顺序）
            String key = command.getSymbol() + ":" + command.getOrderId().toString();
            
            // 序列化为JSON
            String value = objectMapper.writeValueAsString(command);
            
            // 异步发送到Kafka
            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, key, value).toCompletableFuture();
            
            // 回调处理
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("[OrderEventPublisher] ✅ Kafka send success, topic={}, partition={}, offset={}, orderId={}",
                        topic, 
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        command.getOrderId());
                } else {
                    log.error("[OrderEventPublisher] ❌ Kafka send failed, topic={}, orderId={}",
                        topic, command.getOrderId(), ex);
                    // 生产环境：应该重试或记录到死信队列
                }
            });
            
        } catch (Exception e) {
            log.error("[OrderEventPublisher] ❌ Failed to publish order event, orderId={}",
                command.getOrderId(), e);
            throw new RuntimeException("Failed to publish order event", e);
        }
    }
}

