package com.exchange.cfddealer.consumer;

import com.exchange.cfddealer.config.CfdDealerProperties;
import com.exchange.cfddealer.dto.ReferenceBookSnapshot;
import com.exchange.cfddealer.service.LimitWorkingOrderService;
import com.exchange.cfddealer.service.ReferenceBookStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 参考深度消费者（事件驱动触发 WORKING LIMIT 订单）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReferenceDepthConsumer {

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final CfdDealerProperties properties;
    private final ReferenceBookStore referenceBookStore;
    private final LimitWorkingOrderService limitWorkingOrderService;
    private final Map<String, Long> latestOffsetBySymbol = new ConcurrentHashMap<>();

    @KafkaListener(
        topicPattern = "${cfd.dealer.reference-topic-pattern:market.ext.binance.depth.*}",
        groupId = "${cfd.dealer.reference-consumer-group:cfd-dealer-reference}"
    )
    public void consume(
        @Payload String message,
        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
        @Header(KafkaHeaders.OFFSET) long kafkaOffset
    ) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String symbol = normalizeSymbol(readText(root, "s"));
            if (symbol == null) {
                return;
            }
            if (!isSymbolEnabled(symbol)) {
                return;
            }

            long eventTime = root.path("E").asLong(0L);
            long sourceOffset = root.path("u").asLong(0L);
            if (sourceOffset <= 0L) {
                sourceOffset = kafkaOffset;
            }
            Long lastOffset = latestOffsetBySymbol.get(symbol);
            if (lastOffset != null && sourceOffset <= lastOffset) {
                return;
            }
            latestOffsetBySymbol.put(symbol, sourceOffset);

            List<ReferenceBookSnapshot.PriceLevel> bids = toLevels(root.path("b"), properties.getReferenceDepthLevels());
            List<ReferenceBookSnapshot.PriceLevel> asks = toLevels(root.path("a"), properties.getReferenceDepthLevels());
            if (bids.isEmpty() || asks.isEmpty()) {
                return;
            }

            String bestBid = bids.get(0).getPrice();
            String bestAsk = asks.get(0).getPrice();
            if (bestBid == null || bestAsk == null) {
                return;
            }

            BigDecimal bestBidDecimal = new BigDecimal(bestBid);
            BigDecimal bestAskDecimal = new BigDecimal(bestAsk);
            if (bestBidDecimal.compareTo(BigDecimal.ZERO) <= 0 || bestAskDecimal.compareTo(BigDecimal.ZERO) <= 0) {
                return;
            }

            long now = System.currentTimeMillis();
            ReferenceBookSnapshot snapshot = new ReferenceBookSnapshot();
            snapshot.setSymbol(symbol);
            snapshot.setEventTime(eventTime > 0L ? eventTime : now);
            snapshot.setTopic(topic);
            snapshot.setOffset(sourceOffset);
            snapshot.setBestBid(bestBid);
            snapshot.setBestAsk(bestAsk);
            snapshot.setBidsTopN(bids);
            snapshot.setAsksTopN(asks);
            snapshot.setSource(readText(root, "source"));
            snapshot.setStalenessMs(snapshot.getEventTime() > 0L ? Math.max(0L, now - snapshot.getEventTime()) : 0L);

            referenceBookStore.upsert(snapshot);

            String redisKey = properties.getReferenceRedisPrefix() + symbol;
            try {
                stringRedisTemplate.opsForValue().set(redisKey, objectMapper.writeValueAsString(snapshot));
            } catch (Exception redisException) {
                log.warn("[CFD-DEALER] write redis reference snapshot failed, symbol={}, key={}", symbol, redisKey, redisException);
            }

            int triggered = limitWorkingOrderService.triggerWorkingOrdersByQuote(
                symbol,
                bestBidDecimal,
                bestAskDecimal,
                snapshot.getEventTime(),
                topic,
                sourceOffset
            );

            if (triggered > 0) {
                log.info("[CFD-DEALER] reference event triggered working fills, symbol={}, triggered={}, bestBid={}, bestAsk={}, topic={}, sourceOffset={}",
                    symbol, triggered, bestBid, bestAsk, topic, sourceOffset);
            }
        } catch (Exception e) {
            log.error("[CFD-DEALER] consume reference depth failed, topic={}, kafkaOffset={}", topic, kafkaOffset, e);
        }
    }

    private List<ReferenceBookSnapshot.PriceLevel> toLevels(JsonNode levelsNode, int maxLevels) {
        List<ReferenceBookSnapshot.PriceLevel> levels = new ArrayList<>();
        if (levelsNode == null || !levelsNode.isArray()) {
            return levels;
        }

        int limit = maxLevels <= 0 ? 20 : maxLevels;
        int size = Math.min(levelsNode.size(), limit);
        for (int i = 0; i < size; i++) {
            JsonNode levelNode = levelsNode.get(i);
            if (levelNode == null || !levelNode.isArray() || levelNode.size() < 2) {
                continue;
            }

            String price = levelNode.get(0).asText(null);
            String quantity = levelNode.get(1).asText(null);
            if (price == null || quantity == null) {
                continue;
            }

            ReferenceBookSnapshot.PriceLevel level = new ReferenceBookSnapshot.PriceLevel();
            level.setPrice(price);
            level.setQuantity(quantity);
            levels.add(level);
        }
        return levels;
    }

    private String readText(JsonNode node, String field) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText(null);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isSymbolEnabled(String symbol) {
        List<String> symbols = properties.getSymbols();
        return symbols == null || symbols.isEmpty() || symbols.contains(symbol);
    }
}
