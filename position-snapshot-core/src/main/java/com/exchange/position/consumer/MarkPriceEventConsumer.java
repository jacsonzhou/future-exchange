package com.exchange.position.consumer;

import com.exchange.position.dto.MarkPriceEvent;
import com.exchange.position.service.PositionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Mark Price Event Consumer（标记价格事件消费者）
 * 
 * 🔥 核心职责：
 * 1. 消费标记价格事件
 * 2. 调用PositionService更新估值和风控指标
 * 
 * Topic: mark-price-{symbol}
 * Partition: 1（单线程保证顺序）
 * Group: position-service
 */
@Slf4j
@Component
public class MarkPriceEventConsumer {
    
    @Autowired
    private PositionService positionService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    /**
     * 消费MarkPriceEvent
     */
    @KafkaListener(
        topicPattern = "mark-price-.*",
        groupId = "position-service",
        concurrency = "1" // 单线程保证顺序
    )
    public void consumeMarkPriceEvent(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        log.info("[MarkPriceConsumer] ⬇️ Receive event, topic={}, partition={}, offset={}",
            topic, partition, offset);
        
        try {
            // 1. 解析事件
            MarkPriceEvent event = objectMapper.readValue(message, MarkPriceEvent.class);
            
            log.info("[MarkPriceConsumer] Parse event, symbol={}, markPrice={}",
                event.getSymbol(), event.getMarkPrice());
            
            // 2. 调用Service处理
            positionService.onMarkPrice(event);
            
            log.info("[MarkPriceConsumer] ✅ Event processed, symbol={}, offset={}",
                event.getSymbol(), offset);
            
        } catch (Exception e) {
            log.error("[MarkPriceConsumer] ❌ Process event error, topic={}, offset={}",
                topic, offset, e);
        }
    }
}

