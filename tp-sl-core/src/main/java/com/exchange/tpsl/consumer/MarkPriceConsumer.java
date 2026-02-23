package com.exchange.tpsl.consumer;

import com.exchange.tpsl.service.impl.TpSlServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 标记价格消费者
 * 
 * 监听标记价格更新，检查TP/SL触发条件
 */
@Slf4j
@Component
public class MarkPriceConsumer {
    
    @Autowired
    private TpSlServiceImpl tpSlService;
    
    @KafkaListener(topics = "mark-price-update", groupId = "tp-sl-group")
    public void onMarkPriceUpdate(Map<String, Object> message) {
        try {
            String symbol = (String) message.get("symbol");
            Long markPrice = ((Number) message.get("price")).longValue();
            
            // 检查并触发TP/SL订单
            tpSlService.onMarkPrice(symbol, markPrice);
            
        } catch (Exception e) {
            log.error("Failed to process mark price update", e);
        }
    }
}
