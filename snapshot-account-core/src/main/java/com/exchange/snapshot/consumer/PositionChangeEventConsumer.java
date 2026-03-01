package com.exchange.snapshot.consumer;

import com.exchange.snapshot.service.AccountSnapshotService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 持仓变化消费者：接收 position-snapshot-core 发布的 private-position-change，
 * 并回写账户总未实现盈亏（unrealizedPnl）。
 */
@Slf4j
@Component
public class PositionChangeEventConsumer {

    private final AccountSnapshotService accountSnapshotService;
    private final ObjectMapper objectMapper;

    public PositionChangeEventConsumer(AccountSnapshotService accountSnapshotService, ObjectMapper objectMapper) {
        this.accountSnapshotService = accountSnapshotService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
        topics = "${snapshot.kafka.position-change-topic:private-position-change}",
        groupId = "${snapshot.kafka.position-change-group:snapshot-position-service-v2}",
        concurrency = "${snapshot.kafka.position-change-concurrency:8}"
    )
    public void consumePositionChange(
        @Payload String message,
        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
        @Header(KafkaHeaders.OFFSET) long offset,
        Acknowledgment acknowledgment
    ) {
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode data = root.has("data") ? root.get("data") : root;
            if (data == null || !data.has("userId")) {
                if (acknowledgment != null) {
                    acknowledgment.acknowledge();
                }
                return;
            }

            Long userId = asLong(data, "userId");
            JsonNode position = data.has("position") ? data.get("position") : data;
            String symbol = asText(data, "symbol");
            if ((symbol == null || symbol.isBlank()) && position != null) {
                symbol = asText(position, "symbol");
            }

            Integer positionSide = asInt(data, "positionSide");
            if ((positionSide == null || positionSide <= 0) && position != null) {
                String side = asText(position, "side");
                positionSide = "SHORT".equalsIgnoreCase(side) ? 2 : 1;
            }

            BigDecimal quantity = toDecimal(position, "quantity");
            BigDecimal unrealizedPnl = toDecimal(position, "unrealizedPnl");
            String changeType = null;
            String markPriceId = null;
            if (data.has("change") && data.get("change").has("changeType")) {
                changeType = asText(data.get("change"), "changeType");
            }
            if (data.has("change") && data.get("change").has("markPriceId")) {
                markPriceId = asText(data.get("change"), "markPriceId");
            }
            if ((markPriceId == null || markPriceId.isBlank()) && data.has("markPriceId")) {
                markPriceId = asText(data, "markPriceId");
            }

            if (userId == null || symbol == null || symbol.isBlank() || positionSide == null || positionSide <= 0) {
                if (acknowledgment != null) {
                    acknowledgment.acknowledge();
                }
                return;
            }
            if (quantity == null) {
                quantity = BigDecimal.ZERO;
            }
            if (unrealizedPnl == null) {
                unrealizedPnl = BigDecimal.ZERO;
            }

            accountSnapshotService.onPositionMarkUpdate(
                userId,
                symbol,
                positionSide,
                quantity,
                unrealizedPnl,
                changeType,
                markPriceId
            );

            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
        } catch (Exception e) {
            log.error("[PositionChangeEventConsumer] Failed to process message, topic={}, partition={}, offset={}",
                topic, partition, offset, e);
            throw new RuntimeException("Process position change event failed", e);
        }
    }

    private Long asLong(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asLong();
    }

    private Integer asInt(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asInt();
    }

    private String asText(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private BigDecimal toDecimal(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode valueNode = node.get(field);
        if (valueNode.isNumber()) {
            return valueNode.decimalValue();
        }
        String raw = valueNode.asText();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return new BigDecimal(raw);
    }
}
