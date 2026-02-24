package com.exchange.ledger.publisher;

import com.exchange.ledger.dto.TradeEntryEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Ledger Event Publisher（Ledger发布事件）
 * 
 * 🔥 核心职责：
 * 1. Ledger-Core 写完账后发布事件
 * 2. 发布到 Kafka: trade-entry-{symbol}
 * 3. AccountSnapshotService 消费此事件
 * 
 * Topic 设计：
 * - topic: trade-entry-{symbol}
 * - partition: 1（单Symbol单线程，保证顺序）
 * - key: symbol
 */
@Slf4j
@Component
public class LedgerEventPublisher {
    
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    /**
     * 发布TradeEntryEvent
     */
    public void publishTradeEntry(TradeEntryEvent event) {
        try {
            String topic = resolveTopic(event);
            String key = event.getSymbol(); // 保证同Symbol顺序
            
            String message = objectMapper.writeValueAsString(event);
            
            kafkaTemplate.send(topic, key, message);
            
            log.info("[LedgerEventPublisher] ✅ Published trade entry event, " +
                "tradeId={}, symbol={}, topic={}, sequence={}, bizSeq={}",
                event.getTradeId(), event.getSymbol(), topic, 
                event.getSequence(), event.getBizSeq());
            
        } catch (Exception e) {
            log.error("[LedgerEventPublisher] ❌ Publish event error, tradeId={}", 
                event.getTradeId(), e);
            throw new RuntimeException("Publish ledger event failed", e);
        }
    }

    private String resolveTopic(TradeEntryEvent event) {
        if (event != null && "SYSTEM".equalsIgnoreCase(event.getSymbol())) {
            // SYSTEM 类账务事件不应进入持仓链路
            return "account-entry-SYSTEM";
        }
        return "trade-entry-" + event.getSymbol();
    }
}
