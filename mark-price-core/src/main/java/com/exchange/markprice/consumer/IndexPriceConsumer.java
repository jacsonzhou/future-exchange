package com.exchange.markprice.consumer;

import com.exchange.markprice.service.MarkPriceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 指数价格更新消费者
 */
@Slf4j
@Component
public class IndexPriceConsumer {

    @Autowired
    private MarkPriceService markPriceService;

    /**
     * 消费指数价格更新事件
     */
    @KafkaListener(topics = "${mark-price.kafka.index-topic:index-price-update}", 
                   groupId = "${spring.application.name}-index-consumer")
    public void onIndexPriceUpdate(Map<String, Object> message) {
        try {
            String symbol = (String) message.get("symbol");
            Long price = ((Number) message.get("price")).longValue();
            
            log.debug("Received index price update: symbol={}, price={}", symbol, price);
            
            // 触发标记价格重新计算
            markPriceService.onIndexPriceUpdate(symbol, price);
        } catch (Exception e) {
            log.error("Failed to process index price update", e);
        }
    }
}
