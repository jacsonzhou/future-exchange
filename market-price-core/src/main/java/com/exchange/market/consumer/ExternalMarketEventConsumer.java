package com.exchange.market.consumer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.market.cache.MarketDataCache;
import com.exchange.market.config.ExternalMarketProperties;
import com.exchange.market.engine.TradeEngine;
import com.exchange.market.model.Kline;
import com.exchange.market.service.KlineService;
import com.exchange.market.service.support.ExternalTickerStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * 外部行情事件消费者（Binance ext 通道）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalMarketEventConsumer {

    private static final long SCALE = 100_000_000L;
    private static final BigDecimal SCALE_BD = BigDecimal.valueOf(SCALE);

    private final ExternalMarketProperties externalProperties;
    private final KlineService klineService;
    private final MarketDataCache marketDataCache;
    private final ExternalTickerStateService externalTickerStateService;

    @Value("${market-data.output.standard-only:false}")
    private boolean standardOnlyOutput;

    @KafkaListener(
            topicPattern = "market\\.ext\\..*\\.kline\\..*\\..*",
            containerFactory = "externalMarketKafkaListenerContainerFactory"
    )
    public void onExternalKline(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        if (!isExternalInputEnabled()) {
            ack.acknowledge();
            return;
        }

        try {
            for (ConsumerRecord<String, String> record : records) {
                processExternalKline(record.topic(), record.value());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[ExternalConsumer] Failed to process external kline batch", e);
        }
    }

    @KafkaListener(
            topicPattern = "market\\.ext\\..*\\.ticker\\..*",
            containerFactory = "externalMarketKafkaListenerContainerFactory"
    )
    public void onExternalTicker(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        if (!isExternalInputEnabled()) {
            ack.acknowledge();
            return;
        }

        try {
            for (ConsumerRecord<String, String> record : records) {
                processExternalTicker(record.topic(), record.value());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[ExternalConsumer] Failed to process external ticker batch", e);
        }
    }

    private void processExternalKline(String topic, String payload) {
        JSONObject root = JSON.parseObject(payload);
        if (root == null || !"kline".equals(root.getString("e"))) {
            return;
        }

        String sourceInTopic = topicPart(topic, 2);
        if (!isExpectedSource(sourceInTopic)) {
            return;
        }

        JSONObject k = root.getJSONObject("k");
        if (k == null) {
            return;
        }

        String symbol = normalizeSymbol(root.getString("s"));
        if (symbol == null) {
            symbol = normalizeSymbol(topicPart(topic, 4));
        }
        String interval = k.getString("i");
        if (interval == null || interval.isBlank()) {
            interval = topicPart(topic, 5);
        }
        if (symbol == null || interval == null || interval.isBlank()) {
            return;
        }

        long openTime = k.getLongValue("t");
        long closeTime = k.getLongValue("T");
        if (openTime <= 0 || closeTime <= 0) {
            return;
        }

        Kline kline = Kline.builder()
                .symbol(symbol)
                .interval(interval)
                .openTime(openTime)
                .closeTime(closeTime)
                .openPrice(parseScaled(k.getString("o")))
                .highPrice(parseScaled(k.getString("h")))
                .lowPrice(parseScaled(k.getString("l")))
                .closePrice(parseScaled(k.getString("c")))
                .volume(parseScaled(k.getString("v")))
                .quoteVolume(parseScaled(k.getString("q")))
                .tradeCount(k.getIntValue("n"))
                .takerBuyVolume(parseScaled(k.getString("V")))
                .takerBuyQuoteVolume(parseScaled(k.getString("Q")))
                .build();

        marketDataCache.updateKline(symbol, interval, kline);
        try {
            klineService.saveRealtimeKline(kline);
        } catch (Exception e) {
            // 外部行情链路优先保证实时可见，ClickHouse 异常时跳过持久化，避免 Kafka 重试风暴。
            log.warn("[ExternalConsumer] Failed to persist realtime kline, symbol={}, interval={}, openTime={}, reason={}",
                    symbol, interval, openTime, e.getMessage());
            return;
        }

        if (k.getBooleanValue("x")) {
            try {
                klineService.closeKline(kline);
                marketDataCache.closeKline(symbol, interval, kline);
            } catch (Exception e) {
                log.warn("[ExternalConsumer] Failed to close kline, symbol={}, interval={}, openTime={}, reason={}",
                        symbol, interval, openTime, e.getMessage());
            }
        }
    }

    private void processExternalTicker(String topic, String payload) {
        JSONObject root = JSON.parseObject(payload);
        if (root == null || !"24hrTicker".equals(root.getString("e"))) {
            return;
        }

        String sourceInTopic = topicPart(topic, 2);
        if (!isExpectedSource(sourceInTopic)) {
            return;
        }

        String symbol = normalizeSymbol(root.getString("s"));
        if (symbol == null) {
            symbol = normalizeSymbol(topicPart(topic, 4));
        }
        if (symbol == null) {
            return;
        }

        TradeEngine.TradeStats24h stats = new TradeEngine.TradeStats24h();
        stats.setSymbol(symbol);
        stats.setLastPrice(parseScaled(root.getString("c")));
        stats.setLastQty(parseScaled(root.getString("Q")));
        stats.setPriceChange(parseScaled(root.getString("p")));
        stats.setPriceChangePercent(parseDouble(root.get("P")));
        stats.setWeightedAvgPrice(parseScaled(root.getString("w")));
        stats.setOpenPrice(parseScaled(root.getString("o")));
        stats.setHighPrice(parseScaled(root.getString("h")));
        stats.setLowPrice(parseScaled(root.getString("l")));
        stats.setVolume(parseScaled(root.getString("v")));
        stats.setQuoteVolume(parseScaled(root.getString("q")));
        stats.setOpenTime(root.getLongValue("O"));
        stats.setCloseTime(root.getLongValue("C"));
        stats.setFirstId(root.getLongValue("F"));
        stats.setLastId(root.getLongValue("L"));
        stats.setCount(root.getIntValue("n"));

        // 兼容字段
        stats.setHigh24h(stats.getHighPrice());
        stats.setLow24h(stats.getLowPrice());
        stats.setOpen24h(stats.getOpenPrice());
        stats.setVolume24h(stats.getVolume());
        stats.setQuoteVolume24h(stats.getQuoteVolume());
        stats.setTradeCount(stats.getCount());

        marketDataCache.updateTicker(symbol, stats);
        externalTickerStateService.update(symbol, stats, root.getLongValue("E"));
    }

    private boolean isExpectedSource(String sourceInTopic) {
        String expected = externalProperties.getSource();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return expected.equalsIgnoreCase(sourceInTopic);
    }

    private boolean isExternalInputEnabled() {
        return externalProperties.isEnabled() && !standardOnlyOutput;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String topicPart(String topic, int idx) {
        String[] parts = topic == null ? new String[0] : topic.split("\\.");
        if (idx < 0 || idx >= parts.length) {
            return "";
        }
        return parts[idx];
    }

    private long parseScaled(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return new BigDecimal(value.trim())
                    .multiply(SCALE_BD)
                    .setScale(0, RoundingMode.DOWN)
                    .longValue();
        } catch (Exception e) {
            return 0L;
        }
    }

    private double parseDouble(Object value) {
        if (value == null) {
            return 0D;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return 0D;
        }
    }
}
