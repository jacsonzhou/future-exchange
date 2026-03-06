package com.exchange.cfddealer.producer;

import com.exchange.cfddealer.config.CfdDealerProperties;
import com.exchange.cfddealer.dto.CfdOrderCommand;
import com.exchange.cfddealer.dto.MarketExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TradeEventProducer {

    private static final BigDecimal SCALE = new BigDecimal("100000000");

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CfdDealerProperties properties;

    @Value("${execution.topic-mode:LEGACY_ONLY}")
    private String topicMode;

    @Value("${execution.topic.shared.trade:ex.trade.v1}")
    private String sharedTradeTopic;

    @Value("${spring.application.name:cfd-dealer-core}")
    private String source;

    public void publishTrade(CfdOrderCommand command, MarketExecutionResult result) {
        try {
            long dealerAccountId = properties.getDealerAccountId();
            boolean userBuy = "BUY".equalsIgnoreCase(result.getSide());
            boolean isBuyerMaker = !userBuy;

            long makerUserId = dealerAccountId;
            long takerUserId = result.getUserId();
            long makerOrderId = result.getOrderId();
            long takerOrderId = result.getOrderId();

            int userLeverage = (command.getLeverage() == null || command.getLeverage() <= 0)
                    ? 10
                    : command.getLeverage();
            int dealerLeverage = 1;

            Map<String, Object> event = new HashMap<>();
            event.put("eventType", "TRADE");
            event.put("symbol", result.getSymbol());
            event.put("sequence", result.getMatchSequence());
            event.put("tradeId", result.getTradeId());
            event.put("price", toScaledLong(result.getVwapPrice()));
            event.put("quantity", toScaledLong(result.getFilledQuantity()));
            event.put("timestamp", result.getTradeTime());
            event.put("isBuyerMaker", isBuyerMaker);

            event.put("makerOrderId", makerOrderId);
            event.put("takerOrderId", takerOrderId);
            event.put("makerUserId", makerUserId);
            event.put("takerUserId", takerUserId);
            event.put("makerLeverage", dealerLeverage);
            event.put("takerLeverage", userLeverage);

            event.put("executionMode", result.getExecutionMode());
            event.put("liquiditySource", result.getLiquiditySource());
            event.put("referenceTopic", result.getReferenceTopic());
            event.put("referenceOffset", result.getReferenceOffset());
            event.put("referenceEventTime", result.getReferenceEventTime());
            event.put("dealerAccountId", dealerAccountId);

            String topic = properties.getTradeTopicPrefix() + result.getSymbol();
            publishWithMode(topic, result.getTradeId(), result.getSymbol(), event);
        } catch (Exception e) {
            throw new RuntimeException("publish trade-event failed", e);
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
            sendAsync(sharedTradeTopic, sharedKey, sharedPayload, event, "shared");
        }
    }

    private Map<String, Object> buildEnvelope(Map<String, Object> event) {
        long eventTime = resolveEventTime(event.get("timestamp"));
        Object tradeId = event.get("tradeId");
        Object eventType = event.get("eventType");

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("eventId", tradeId != null ? String.valueOf(tradeId) : "TRADE:" + eventTime);
        envelope.put("eventType", eventType != null ? String.valueOf(eventType) : "TRADE");
        envelope.put("schemaVersion", "1.0");
        envelope.put("source", source);
        envelope.put("eventTime", eventTime);
        envelope.put("traceId", tradeId != null ? String.valueOf(tradeId) : "TRADE_TRACE_" + eventTime);
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
        Object tradeId = event.get("tradeId");
        kafkaTemplate.send(topic, key, payload).whenComplete((sendResult, throwable) -> {
            if (throwable == null) {
                log.info("[CFD-DEALER] trade-event sent, route={}, mode={}, topic={}, key={}, tradeId={}, offset={}",
                    route,
                    normalizeMode(),
                    topic,
                    key,
                    tradeId,
                    sendResult.getRecordMetadata().offset());
            } else {
                log.error("[CFD-DEALER] trade-event send failed, route={}, mode={}, topic={}, key={}, tradeId={}",
                    route,
                    normalizeMode(),
                    topic,
                    key,
                    tradeId,
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

    private long toScaledLong(BigDecimal value) {
        if (value == null) {
            return 0L;
        }
        return value.multiply(SCALE).setScale(0, RoundingMode.HALF_UP).longValue();
    }
}
