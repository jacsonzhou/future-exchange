package com.exchange.tpsl.consumer;

import com.exchange.tpsl.service.impl.TpSlServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
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
    
    @KafkaListener(
            topics = "mark-price-update",
            groupId = "tp-sl-group",
            containerFactory = "kafkaManualAckListenerContainerFactory"
    )
    public void onMarkPriceUpdate(Map<String, Object> message, Acknowledgment ack) {
        try {
            String symbol = (String) message.get("symbol");
            Long markPrice = ((Number) message.get("price")).longValue();
            
            // 检查并触发TP/SL订单
            tpSlService.checkAndTrigger(symbol, markPrice);
            // 更新移动止损触发价
            tpSlService.updateTrailingStop(symbol, markPrice);
            
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process mark price update, message={}", message, e);
            // 不 ack，让 Kafka 重试
            throw new RuntimeException("Mark price processing failed", e);
        }
    }
}
