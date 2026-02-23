package com.exchange.adl.producer;

import com.exchange.adl.event.AdlExecutedEvent;
import com.exchange.adl.event.AdlTriggerEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * ADL事件生产者
 *
 * 发布ADL相关事件到Kafka
 */
@Slf4j
@Component
public class AdlEventProducer {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String ADL_TRIGGER_TOPIC = "adl-trigger-topic";
    private static final String ADL_EXECUTED_TOPIC = "adl-executed-topic";

    /**
     * 发布ADL触发事件
     */
    public void publishAdlTrigger(AdlTriggerEvent event) {
        try {
            String message = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(ADL_TRIGGER_TOPIC, event.getSymbol(), message);

            log.info("Published ADL trigger event: adlTriggerId={}, symbol={}",
                    event.getAdlTriggerId(), event.getSymbol());

        } catch (Exception e) {
            log.error("Failed to publish ADL trigger event", e);
        }
    }

    /**
     * 发布ADL执行完成事件
     */
    public void publishAdlExecuted(AdlExecutedEvent event) {
        try {
            String message = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(ADL_EXECUTED_TOPIC, event.getSymbol(), message);

            log.info("Published ADL executed event: adlBatchId={}, symbol={}, affectedUsers={}",
                    event.getAdlBatchId(), event.getSymbol(), event.getAffectedUsers());

        } catch (Exception e) {
            log.error("Failed to publish ADL executed event", e);
        }
    }
}
