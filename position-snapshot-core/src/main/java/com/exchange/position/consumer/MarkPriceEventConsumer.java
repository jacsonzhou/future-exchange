package com.exchange.position.consumer;

import com.exchange.position.dto.MarkPriceEvent;
import com.exchange.position.service.PositionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Mark Price Event Consumer（标记价格事件消费者）
 * 
 * 🔥 核心职责：
 * 1. 消费标记价格事件
 * 2. 调用PositionService更新估值和风控指标
 * 
 * Topic: mark-price-{symbol}
 * Partition: 1（单线程保证顺序）
 * Group: position-service
 */
@Slf4j
@Component
public class MarkPriceEventConsumer {
    private static final BigDecimal SCALE = new BigDecimal("100000000");
    private static final int DLQ_RAW_MESSAGE_MAX_LEN = 4000;
    private static final String MARK_PRICE_UPDATE_TOPIC = "mark-price-update";
    
    @Autowired
    private PositionService positionService;
    
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${position.kafka.mark-price-dlq-topic:mark-price-update-dlq}")
    private String markPriceDlqTopic;
    
    /**
     * 消费MarkPriceEvent
     */
    @KafkaListener(
        // Exclude DLQ/system topics to avoid self-consuming the DLQ stream.
        topicPattern = "^mark-price-update$|^mark-price-(?!update$)(?!topic$)(?!.*-dlq$).+$",
        groupId = "${position.kafka.mark-price-group:position-mark-service-v2}",
        concurrency = "1" // 单线程保证顺序
    )
    public void consumeMarkPriceEvent(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment
    ) {
        if (shouldSkipTopic(topic)) {
            log.debug("[MarkPriceConsumer] Skip unsupported topic={}, offset={}", topic, offset);
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
            return;
        }

        log.info("[MarkPriceConsumer] ⬇️ Receive event, topic={}, partition={}, offset={}",
            topic, partition, offset);
        
        try {
            // 1. 解析事件（兼容 eventType/eventTime/data 新格式与旧平铺格式）
            MarkPriceEvent event = parseEvent(message);
            
            log.info("[MarkPriceConsumer] Parse event, symbol={}, markPrice={}",
                event.getSymbol(), event.getMarkPrice());
            
            // 2. 调用Service处理
            positionService.onMarkPrice(event);

            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
            
            log.info("[MarkPriceConsumer] ✅ Event processed, symbol={}, offset={}",
                event.getSymbol(), offset);
            
        } catch (Exception e) {
            log.error("[MarkPriceConsumer] ❌ Process event error, topic={}, offset={}",
                topic, offset, e);
            if (!isDlqTopic(topic)) {
                publishDlq(message, topic, partition, offset, e);
            }
            if (acknowledgment != null) {
                acknowledgment.acknowledge();
            }
        }
    }

    private MarkPriceEvent parseEvent(String message) throws Exception {
        JsonNode root = objectMapper.readTree(message);
        JsonNode dataNode = root.has("data") ? root.get("data") : root;

        MarkPriceEvent event = new MarkPriceEvent();
        event.setMarkPriceId(readText(dataNode, "markPriceId"));
        event.setIndexPriceId(readText(dataNode, "indexPriceId"));
        event.setSymbol(readText(dataNode, "symbol"));
        event.setMarkPrice(readDecimal(dataNode, "markPrice"));
        event.setIndexPrice(readDecimal(dataNode, "indexPrice"));
        event.setFundingRate(readDecimal(dataNode, "fundingRate"));

        Long timestamp = readLong(dataNode, "timestamp");
        if (timestamp == null) {
            timestamp = readLong(root, "eventTime");
        }
        event.setTimestamp(timestamp != null ? timestamp : System.currentTimeMillis());

        if (event.getSymbol() == null || event.getSymbol().isBlank()) {
            throw new IllegalArgumentException("Missing symbol in mark price event");
        }
        if (event.getMarkPrice() == null || event.getMarkPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Missing or invalid markPrice in mark price event");
        }
        if (event.getMarkPriceId() == null || event.getMarkPriceId().isBlank()) {
            event.setMarkPriceId(event.getSymbol() + "-" + event.getTimestamp());
        }
        if (event.getIndexPriceId() == null || event.getIndexPriceId().isBlank()) {
            event.setIndexPriceId(event.getMarkPriceId());
        }

        return event;
    }

    private String readText(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private Long readLong(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asLong();
    }

    private BigDecimal readDecimal(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode valueNode = node.get(field);
        BigDecimal parsed;
        if (valueNode.isNumber()) {
            parsed = valueNode.decimalValue();
        } else {
            String value = valueNode.asText();
            if (value == null || value.isBlank()) {
                return null;
            }
            parsed = new BigDecimal(value);
        }

        // 兼容上游 long(1e8 缩放)格式
        if (valueNode.isIntegralNumber() && parsed.abs().compareTo(SCALE) >= 0) {
            return parsed.divide(SCALE, 8, RoundingMode.HALF_UP);
        }
        return parsed;
    }

    private void publishDlq(String message, String topic, int partition, long offset, Exception e) {
        try {
            Map<String, Object> dlq = new HashMap<>();
            dlq.put("eventType", "MARK_PRICE_CONSUME_FAILED");
            dlq.put("eventTime", System.currentTimeMillis());
            Map<String, Object> data = new HashMap<>();
            data.put("sourceTopic", topic);
            data.put("partition", partition);
            data.put("offset", offset);
            data.put("rawMessage", safeRawMessage(message));
            data.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            dlq.put("data", data);

            kafkaTemplate.send(markPriceDlqTopic, topic + ":" + partition, objectMapper.writeValueAsString(dlq));
        } catch (Exception ex) {
            log.error("[MarkPriceConsumer] ❌ Failed to publish DLQ message, topic={}, offset={}", topic, offset, ex);
        }
    }

    private boolean shouldSkipTopic(String topic) {
        String normalized = normalizeTopic(topic);
        if (normalized == null) {
            return true;
        }
        if (isDlqTopic(normalized)) {
            return true;
        }
        if ("mark-price-topic".equals(normalized)) {
            return true;
        }
        return !(MARK_PRICE_UPDATE_TOPIC.equals(normalized) || normalized.startsWith("mark-price-"));
    }

    private boolean isDlqTopic(String topic) {
        String normalized = normalizeTopic(topic);
        return normalized != null && normalized.endsWith("-dlq");
    }

    private String normalizeTopic(String topic) {
        if (topic == null) {
            return null;
        }
        String normalized = topic.trim().toLowerCase();
        return normalized.isEmpty() ? null : normalized;
    }

    private String safeRawMessage(String message) {
        if (message == null) {
            return null;
        }
        if (message.length() <= DLQ_RAW_MESSAGE_MAX_LEN) {
            return message;
        }
        return message.substring(0, DLQ_RAW_MESSAGE_MAX_LEN) + "...(truncated)";
    }
}
