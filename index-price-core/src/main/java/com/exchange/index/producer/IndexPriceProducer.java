package com.exchange.index.producer;

import com.exchange.index.event.IndexPriceUpdateEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 指数价格事件生产者
 */
@Slf4j
@Component
public class IndexPriceProducer {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${index-price.kafka.topic:index-price-update}")
    private String topicName;

    /**
     * 发布指数价格更新事件
     */
    public void publishIndexPriceUpdate(IndexPriceUpdateEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topicName, event.getData().getSymbol(), payload);
            log.debug("Published index price update: symbol={}, price={}",
                    event.getData().getSymbol(), event.getData().getPrice());
        } catch (Exception e) {
            log.error("Failed to publish index price update", e);
        }
    }
}
