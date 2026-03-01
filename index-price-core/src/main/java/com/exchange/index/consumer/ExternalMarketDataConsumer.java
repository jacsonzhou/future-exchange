package com.exchange.index.consumer;

import com.exchange.common.core.Money;
import com.exchange.index.service.support.ExternalMarketStateStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 外部行情消费者（Binance外部行情通道）
 */
@Slf4j
@Component
public class ExternalMarketDataConsumer {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ExternalMarketStateStore marketStateStore;

    @KafkaListener(
            topicPattern = "${index-price.input.depth-topic-pattern:market\\.ext\\.binance\\.depth\\..*}",
            groupId = "${index-price.input.consumer-group:index-price-ext-consumer}"
    )
    public void onDepth(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String symbol = resolveSymbol(root, topic);
            if (symbol == null || symbol.isBlank()) {
                return;
            }

            long eventTime = root.path("E").asLong(System.currentTimeMillis());
            long bestBid = extractBestLevelPrice(root.get("b"));
            long bestAsk = extractBestLevelPrice(root.get("a"));
            if (bestBid <= 0 && bestAsk <= 0) {
                return;
            }

            marketStateStore.updateDepth(symbol, bestBid, bestAsk, eventTime, topic, offset);
        } catch (Exception e) {
            log.warn("Failed to consume external depth topic={}, offset={}", topic, offset, e);
        }
    }

    @KafkaListener(
            topicPattern = "${index-price.input.trade-topic-pattern:market\\.ext\\.binance\\.trade\\..*}",
            groupId = "${index-price.input.consumer-group:index-price-ext-consumer}"
    )
    public void onTrade(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String symbol = resolveSymbol(root, topic);
            if (symbol == null || symbol.isBlank()) {
                return;
            }

            long eventTime = root.path("E").asLong(System.currentTimeMillis());
            long lastTradePrice = parseScaledPrice(root.get("p"));
            if (lastTradePrice <= 0) {
                return;
            }

            marketStateStore.updateTrade(symbol, lastTradePrice, eventTime, topic, offset);
        } catch (Exception e) {
            log.warn("Failed to consume external trade topic={}, offset={}", topic, offset, e);
        }
    }

    private String resolveSymbol(JsonNode root, String topic) {
        if (root != null && root.hasNonNull("s")) {
            String symbol = root.get("s").asText();
            if (symbol != null && !symbol.isBlank()) {
                return symbol.trim().toUpperCase();
            }
        }
        if (topic == null) {
            return null;
        }
        int index = topic.lastIndexOf('.');
        if (index < 0 || index >= topic.length() - 1) {
            return null;
        }
        return topic.substring(index + 1).trim().toUpperCase();
    }

    private long extractBestLevelPrice(JsonNode levels) {
        if (levels == null || !levels.isArray() || levels.isEmpty()) {
            return 0L;
        }
        JsonNode firstLevel = levels.get(0);
        if (firstLevel == null || !firstLevel.isArray() || firstLevel.isEmpty()) {
            return 0L;
        }
        return parseScaledPrice(firstLevel.get(0));
    }

    private long parseScaledPrice(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0L;
        }
        try {
            if (node.isIntegralNumber()) {
                long value = node.asLong();
                if (Math.abs(value) >= Money.SCALE) {
                    return value;
                }
            }

            String text = node.asText();
            if (text == null || text.isBlank()) {
                return 0L;
            }
            BigDecimal decimal = new BigDecimal(text.trim());
            return decimal.movePointRight(8)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
        } catch (Exception ignore) {
            return 0L;
        }
    }
}
