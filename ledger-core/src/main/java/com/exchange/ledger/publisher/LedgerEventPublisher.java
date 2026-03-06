package com.exchange.ledger.publisher;

import com.exchange.ledger.dto.TradeEntryEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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

    @Value("${execution.topic-mode:DUAL_WRITE}")
    private String topicMode;

    @Value("${execution.topic.shared.trade-entry:acc.trade.entry.v1}")
    private String sharedTradeEntryTopic;

    @Value("${spring.application.name:ledger-core}")
    private String source;
    
    /**
     * 发布TradeEntryEvent
     */
    public void publishTradeEntry(TradeEntryEvent event) {
        try {
            String topic = resolveTopic(event);
            String key = event.getSymbol(); // 保证同Symbol顺序

            if (shouldSendLegacy()) {
                String message = objectMapper.writeValueAsString(event);
                sendAsync(topic, key, message, event, "legacy");
            }

            if (shouldSendShared()) {
                String sharedMessage = objectMapper.writeValueAsString(buildEnvelope(event));
                sendAsync(sharedTradeEntryTopic, key, sharedMessage, event, "shared");
            }
            
        } catch (Exception e) {
            log.error("[LedgerEventPublisher] ❌ Publish event error, tradeId={}", 
                event.getTradeId(), e);
            throw new RuntimeException("Publish ledger event failed", e);
        }
    }

    private Map<String, Object> buildEnvelope(TradeEntryEvent event) {
        long eventTime = event.getEventTime() != null ? event.getEventTime() : System.currentTimeMillis();
        String eventId = event.getTradeId();
        if (eventId == null || eventId.isBlank()) {
            eventId = "LEDGER:" + event.getSymbol() + ":" + event.getBizSeq();
        }

        String eventType = "SYSTEM".equalsIgnoreCase(event.getSymbol()) ? "ACCOUNT_ENTRY" : "TRADE_ENTRY";

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", "1.0");
        envelope.put("source", source);
        envelope.put("eventTime", eventTime);
        envelope.put("traceId", eventId);
        envelope.put("data", event);
        return envelope;
    }

    private void sendAsync(String topic, String key, String payload, TradeEntryEvent event, String route) {
        kafkaTemplate.send(topic, key, payload).whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("[LedgerEventPublisher] ✅ Published trade entry event, route={}, mode={}, topic={}, partition={}, offset={}, tradeId={}, symbol={}, sequence={}, bizSeq={}",
                    route,
                    normalizeMode(),
                    topic,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset(),
                    event.getTradeId(),
                    event.getSymbol(),
                    event.getSequence(),
                    event.getBizSeq());
            } else {
                log.error("[LedgerEventPublisher] ❌ Publish event failed, route={}, mode={}, topic={}, tradeId={}, symbol={}",
                    route,
                    normalizeMode(),
                    topic,
                    event.getTradeId(),
                    event.getSymbol(),
                    ex);
            }
        });
    }

    private String resolveTopic(TradeEntryEvent event) {
        if (event != null && "SYSTEM".equalsIgnoreCase(event.getSymbol())) {
            // SYSTEM 类账务事件不应进入持仓链路
            return "account-entry-SYSTEM";
        }
        return "trade-entry-" + event.getSymbol();
    }

    private boolean shouldSendLegacy() {
        return !"SHARED_ONLY".equals(normalizeMode());
    }

    private boolean shouldSendShared() {
        String mode = normalizeMode();
        return "DUAL_WRITE".equals(mode) || "SHARED_ONLY".equals(mode);
    }

    private String normalizeMode() {
        if (topicMode == null || topicMode.isBlank()) {
            return "DUAL_WRITE";
        }
        return topicMode.trim().toUpperCase(Locale.ROOT);
    }
}
