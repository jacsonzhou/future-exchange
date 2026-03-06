package com.exchange.cfddealer.producer;

import com.exchange.cfddealer.config.CfdDealerProperties;
import com.exchange.cfddealer.dto.MarketExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStateProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CfdDealerProperties properties;

    @Value("${execution.topic-mode:LEGACY_ONLY}")
    private String topicMode;

    @Value("${execution.topic.shared.order-state:ex.order.state.v1}")
    private String sharedOrderStateTopic;

    @Value("${spring.application.name:cfd-dealer-core}")
    private String source;

    public void publishFilled(MarketExecutionResult result) {
        try {
            String topic = properties.getOrderStateTopicPrefix() + result.getSymbol();
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", result.getTradeId());
            event.put("symbol", result.getSymbol());
            event.put("orderId", result.getOrderId());
            event.put("userId", result.getUserId());
            event.put("status", "FILLED");
            event.put("filledQuantityDelta", result.getFilledQuantity().toPlainString());
            event.put("lastFilledPrice", result.getVwapPrice().toPlainString());
            event.put("tradeId", result.getMatchSequence());
            event.put("fee", BigDecimal.ZERO.toPlainString());
            event.put("feeAsset", "USDT");
            event.put("tradeTime", result.getTradeTime());
            event.put("matchSequence", result.getMatchSequence());
            event.put("eventTime", System.currentTimeMillis());
            event.put("executionMode", result.getExecutionMode());
            event.put("liquiditySource", result.getLiquiditySource());
            event.put("referenceTopic", result.getReferenceTopic());
            event.put("referenceOffset", result.getReferenceOffset());
            event.put("referenceEventTime", result.getReferenceEventTime());
            event.put("referenceBestBid", result.getBestBid().toPlainString());
            event.put("referenceBestAsk", result.getBestAsk().toPlainString());
            event.put("referenceVwapPrice", result.getVwapPrice().toPlainString());
            event.put("slippageBps", result.getSlippageBps());

            publishWithMode(topic, String.valueOf(result.getOrderId()), result.getSymbol(), event);
        } catch (Exception e) {
            throw new RuntimeException("publish order-state failed", e);
        }
    }

    public void publishRejected(Long orderId, Long userId, String symbol, String reasonMsg, String traceId) {
        try {
            String topic = properties.getOrderStateTopicPrefix() + symbol;
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", traceId);
            event.put("symbol", symbol);
            event.put("orderId", orderId);
            event.put("userId", userId);
            event.put("status", "REJECTED");
            event.put("filledQuantityDelta", "0");
            event.put("lastFilledPrice", "0");
            event.put("tradeId", 0L);
            event.put("fee", BigDecimal.ZERO.toPlainString());
            event.put("feeAsset", "USDT");
            event.put("tradeTime", System.currentTimeMillis());
            event.put("matchSequence", 0L);
            event.put("eventTime", System.currentTimeMillis());
            event.put("rejectReason", reasonMsg);
            event.put("executionMode", "CFD_DEALER");
            event.put("liquiditySource", properties.getLiquiditySource());

            publishWithMode(topic, String.valueOf(orderId), symbol, event);
        } catch (Exception e) {
            throw new RuntimeException("publish rejected order-state failed", e);
        }
    }

    public void publishCanceled(Long orderId, Long userId, String symbol, String reasonMsg) {
        try {
            String topic = properties.getOrderStateTopicPrefix() + symbol;
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", "CFD_CANCEL_" + System.currentTimeMillis());
            event.put("symbol", symbol);
            event.put("orderId", orderId);
            event.put("userId", userId);
            event.put("status", "CANCELED");
            event.put("filledQuantityDelta", "0");
            event.put("lastFilledPrice", "0");
            event.put("tradeId", 0L);
            event.put("fee", BigDecimal.ZERO.toPlainString());
            event.put("feeAsset", "USDT");
            event.put("tradeTime", System.currentTimeMillis());
            event.put("matchSequence", 0L);
            event.put("eventTime", System.currentTimeMillis());
            event.put("cancelReason", reasonMsg);
            event.put("executionMode", "CFD_DEALER");
            event.put("liquiditySource", properties.getLiquiditySource());

            publishWithMode(topic, String.valueOf(orderId), symbol, event);
        } catch (Exception e) {
            throw new RuntimeException("publish canceled order-state failed", e);
        }
    }

    private void publishWithMode(String legacyTopic, String legacyKey, String symbol, Map<String, Object> event) throws Exception {
        if (shouldSendLegacy()) {
            String payload = objectMapper.writeValueAsString(event);
            sendAsync(legacyTopic, legacyKey, payload, event, "legacy");
        }

        if (shouldSendShared()) {
            String sharedKey = symbol == null || symbol.isBlank() ? legacyKey : symbol;
            String sharedPayload = objectMapper.writeValueAsString(buildEnvelope(event));
            sendAsync(sharedOrderStateTopic, sharedKey, sharedPayload, event, "shared");
        }
    }

    private Map<String, Object> buildEnvelope(Map<String, Object> event) {
        long eventTime = resolveEventTime(event.get("eventTime"));
        Object eventId = event.get("eventId");
        Object orderId = event.get("orderId");

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("eventId", eventId != null ? String.valueOf(eventId) : "ORDER_STATE:" + orderId + ":" + eventTime);
        envelope.put("eventType", "ORDER_STATE");
        envelope.put("schemaVersion", "1.0");
        envelope.put("source", source);
        envelope.put("eventTime", eventTime);
        envelope.put("traceId", eventId != null ? String.valueOf(eventId) : "ORDER_STATE_TRACE_" + eventTime);
        envelope.put("data", event);
        return envelope;
    }

    private long resolveEventTime(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw != null) {
            try {
                return Long.parseLong(String.valueOf(raw));
            } catch (NumberFormatException ignored) {
                // no-op
            }
        }
        return System.currentTimeMillis();
    }

    private void sendAsync(String topic, String key, String payload, Map<String, Object> event, String route) {
        Object orderId = event.get("orderId");
        Object eventId = event.get("eventId");
        kafkaTemplate.send(topic, key, payload).whenComplete((sendResult, throwable) -> {
            if (throwable == null) {
                log.info("[CFD-DEALER] order-state sent, route={}, mode={}, topic={}, key={}, orderId={}, eventId={}, offset={}",
                    route,
                    normalizeMode(),
                    topic,
                    key,
                    orderId,
                    eventId,
                    sendResult.getRecordMetadata().offset());
            } else {
                log.error("[CFD-DEALER] order-state send failed, route={}, mode={}, topic={}, key={}, orderId={}, eventId={}",
                    route,
                    normalizeMode(),
                    topic,
                    key,
                    orderId,
                    eventId,
                    throwable);
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
