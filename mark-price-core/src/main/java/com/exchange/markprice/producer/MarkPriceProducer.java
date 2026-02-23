package com.exchange.markprice.producer;

import com.exchange.markprice.event.MarkPriceUpdateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 标记价格事件生产者
 */
@Slf4j
@Component
public class MarkPriceProducer {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${mark-price.kafka.topic:mark-price-update}")
    private String topicName;

    /**
     * 发布标记价格更新事件
     */
    public void publishMarkPriceUpdate(MarkPriceUpdateEvent event) {
        try {
            kafkaTemplate.send(topicName, event.getData().getSymbol(), event);
            log.debug("Published mark price update: symbol={}, markPrice={}",
                    event.getData().getSymbol(), event.getData().getMarkPrice());
        } catch (Exception e) {
            log.error("Failed to publish mark price update", e);
        }
    }
}
