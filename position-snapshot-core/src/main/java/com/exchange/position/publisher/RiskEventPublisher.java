package com.exchange.position.publisher;

import com.exchange.position.dto.RiskEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Risk Event Publisher（风险事件发布器）
 * 
 * 🔥 核心职责：
 * 1. 发布风险事件到Kafka
 * 2. 供Hard Risk / Soft Risk消费
 * 
 * Topic: risk-event-topic
 */
@Slf4j
@Component
public class RiskEventPublisher {
    
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    private static final String TOPIC = "risk-event-topic";
    
    /**
     * 发布风险事件
     */
    public void publishRiskEvent(RiskEvent event) {
        try {
            String key = event.getSymbol(); // 保证同Symbol顺序
            String message = objectMapper.writeValueAsString(event);
            
            kafkaTemplate.send(TOPIC, key, message);
            
            log.info("[RiskEventPublisher] ✅ Published risk event, eventType={}, userId={}, symbol={}, marginRatio={}",
                event.getEventType(), event.getUserId(), event.getSymbol(), event.getMarginRatio());
            
        } catch (Exception e) {
            log.error("[RiskEventPublisher] ❌ Publish risk event error, userId={}, symbol={}", 
                event.getUserId(), event.getSymbol(), e);
        }
    }
}

