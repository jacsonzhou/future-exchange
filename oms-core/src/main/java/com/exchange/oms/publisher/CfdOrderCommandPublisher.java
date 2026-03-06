package com.exchange.oms.publisher;

import com.exchange.oms.dto.CfdOrderCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * CFD 指令发布器（OMS -> CFD Dealer）
 */
@Slf4j
@Component
public class CfdOrderCommandPublisher {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${oms.kafka.cfd-order-command.topic-prefix:cfd-order-command-}")
    private String topicPrefix;

    @Value("${execution.topic-mode:LEGACY_ONLY}")
    private String topicMode;

    @Value("${execution.topic.shared.order-command:ex.order.command.v1}")
    private String sharedOrderCommandTopic;

    @Value("${spring.application.name:oms-core}")
    private String source;

    public void publish(CfdOrderCommand command) {
        try {
            String symbol = command.getSymbol();
            String legacyTopic = topicPrefix + symbol;
            String legacyKey = symbol + ":" + command.getOrderId();
            String legacyPayload = objectMapper.writeValueAsString(command);

            if (shouldSendLegacy()) {
                sendAsync(legacyTopic, legacyKey, legacyPayload, command, "legacy");
            }

            if (shouldSendShared()) {
                String sharedPayload = objectMapper.writeValueAsString(buildEnvelope(command));
                sendAsync(sharedOrderCommandTopic, symbol, sharedPayload, command, "shared");
            }
        } catch (Exception e) {
            log.error("[CfdOrderPublisher] ❌ build/send failed, orderId={}, type={}",
                    command.getOrderId(), command.getEventType(), e);
            throw new RuntimeException("Failed to publish CFD command", e);
        }
    }

    private Map<String, Object> buildEnvelope(CfdOrderCommand command) {
        long eventTime = command.getEventTime() != null ? command.getEventTime() : System.currentTimeMillis();
        String eventType = command.getEventType() == null ? "UNKNOWN" : command.getEventType();
        String eventId = eventType + ":" + command.getOrderId() + ":" + eventTime;

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", "1.0");
        envelope.put("source", source);
        envelope.put("eventTime", eventTime);
        envelope.put("traceId", command.getClientOrderId() != null ? command.getClientOrderId() : eventId);
        envelope.put("data", command);
        return envelope;
    }

    private void sendAsync(String topic, String key, String value, CfdOrderCommand command, String route) {
        CompletableFuture<SendResult<String, String>> future =
            kafkaTemplate.send(topic, key, value).toCompletableFuture();

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("[CfdOrderPublisher] ✅ sent, route={}, mode={}, topic={}, partition={}, offset={}, orderId={}, type={}",
                    route,
                    normalizeMode(),
                    topic,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset(),
                    command.getOrderId(),
                    command.getEventType());
            } else {
                log.error("[CfdOrderPublisher] ❌ send failed, route={}, mode={}, topic={}, orderId={}, type={}",
                    route,
                    normalizeMode(),
                    topic,
                    command.getOrderId(),
                    command.getEventType(),
                    ex);
            }
        });
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
            return "LEGACY_ONLY";
        }
        return topicMode.trim().toUpperCase(Locale.ROOT);
    }
}
