package com.exchange.market.consumer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.market.cache.MarketDataCache;
import com.exchange.market.config.ExternalMarketProperties;
import com.exchange.market.engine.TradeEngine;
import com.exchange.market.model.Kline;
import com.exchange.market.service.KlineAuthorityService;
import com.exchange.market.service.KlineService;
import com.exchange.market.service.support.ExternalTickerStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 外部行情事件消费者（Binance ext 通道）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalMarketEventConsumer implements ConsumerSeekAware {

    private static final long SCALE = 100_000_000L;
    private static final BigDecimal SCALE_BD = BigDecimal.valueOf(SCALE);
    private static final String TOPIC_TRADE_PREFIX = "market.trade.";
    private static final String TOPIC_DEPTH_PREFIX = "market.depth.";
    private static final String TOPIC_KLINE_PREFIX = "market.kline.";
    private static final String TOPIC_TICKER_PREFIX = "market.ticker.";
    private static final String SNAPSHOT_TRADE_PREFIX = "market:snapshot:trade:";
    private static final String SNAPSHOT_DEPTH_PREFIX = "market:snapshot:depth:";
    private static final String SNAPSHOT_KLINE_PREFIX = "market:snapshot:kline:";
    private static final String SNAPSHOT_TICKER_PREFIX = "market:snapshot:ticker:";

    private final ExternalMarketProperties externalProperties;
    private final KlineService klineService;
    private final KlineAuthorityService klineAuthorityService;
    private final MarketDataCache marketDataCache;
    private final ExternalTickerStateService externalTickerStateService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${market-data.output.standard-only:false}")
    private boolean standardOnlyOutput;

    @Value("${market-data.kafka.enabled:true}")
    private boolean kafkaEnabled;

    @Value("${market-data.redis.snapshot-enabled:true}")
    private boolean redisSnapshotEnabled;

    @Value("${market-data.external.bridge-to-standard:true}")
    private boolean bridgeToStandard;

    @Value("${market-data.kline.authority-enabled:false}")
    private boolean authorityEnabled;

    @Value("${market-data.kline.authority-source:binance}")
    private String authoritySource;

    @Value("${market-data.external.force-seek-to-end-on-assign:true}")
    private boolean forceSeekToEndOnAssign;

    @Value("${market-data.external.realtime-max-age-ms.trade:0}")
    private long tradeMaxAgeMs;

    @Value("${market-data.external.realtime-max-age-ms.depth:0}")
    private long depthMaxAgeMs;

    @Value("${market-data.external.realtime-max-age-ms.ticker:0}")
    private long tickerMaxAgeMs;

    @Value("${market-data.external.realtime-max-age-ms.kline:0}")
    private long klineMaxAgeMs;

    @Value("${market-data.external.drop-out-of-order:true}")
    private boolean dropOutOfOrder;

    @Value("${market-data.external.trade-drop-out-of-order:false}")
    private boolean tradeDropOutOfOrder;

    @Value("${market-data.external.drop-log-sample-interval:200}")
    private long dropLogSampleInterval;

    private final ConcurrentMap<String, Long> latestTradeEventTs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> latestDepthEventTs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> latestTickerEventTs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> latestKlineEventTs = new ConcurrentHashMap<>();
    private final AtomicLong staleDropCount = new AtomicLong(0);
    private final AtomicLong outOfOrderDropCount = new AtomicLong(0);

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        if (!forceSeekToEndOnAssign || assignments == null || assignments.isEmpty()) {
            return;
        }
        for (TopicPartition partition : assignments.keySet()) {
            callback.seekToEnd(partition.topic(), partition.partition());
        }
        log.warn("[ExternalConsumer] Force seek-to-end on assign applied, partitions={}", assignments.keySet());
    }

    @KafkaListener(
            topicPattern = "market\\.ext\\..*\\.trade\\..*",
            containerFactory = "externalMarketKafkaListenerContainerFactory"
    )
    public void onExternalTrade(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        if (!isExternalInputEnabled()) {
            ack.acknowledge();
            return;
        }

        try {
            for (ConsumerRecord<String, String> record : records) {
                processExternalTrade(record.topic(), record.value());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[ExternalConsumer] Failed to process external trade batch", e);
        }
    }

    @KafkaListener(
            topicPattern = "market\\.ext\\..*\\.depth\\..*",
            containerFactory = "externalMarketKafkaListenerContainerFactory"
    )
    public void onExternalDepth(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        if (!isExternalInputEnabled()) {
            ack.acknowledge();
            return;
        }

        try {
            for (ConsumerRecord<String, String> record : records) {
                processExternalDepth(record.topic(), record.value());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[ExternalConsumer] Failed to process external depth batch", e);
        }
    }

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

    private void processExternalTrade(String topic, String payload) {
        JSONObject root = JSON.parseObject(payload);
        if (root == null || !"trade".equals(root.getString("e"))) {
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

        long eventTs = root.getLongValue("T");
        if (eventTs <= 0) {
            eventTs = root.getLongValue("E");
        }
        if (shouldDropEvent("trade", symbol, eventTs, tradeMaxAgeMs, latestTradeEventTs, tradeDropOutOfOrder)) {
            return;
        }

        root.put("s", symbol);
        enrichSource(root, sourceInTopic);

        publishStandard(
                TOPIC_TRADE_PREFIX + symbol,
                symbol,
                root.toJSONString(),
                SNAPSHOT_TRADE_PREFIX + symbol
        );
    }

    private void processExternalDepth(String topic, String payload) {
        JSONObject root = JSON.parseObject(payload);
        if (root == null || !"depthUpdate".equals(root.getString("e"))) {
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

        long eventTs = root.getLongValue("E");
        if (eventTs <= 0) {
            eventTs = root.getLongValue("T");
        }
        if (shouldDropEvent("depth", symbol, eventTs, depthMaxAgeMs, latestDepthEventTs, dropOutOfOrder)) {
            return;
        }

        root.put("s", symbol);
        enrichSource(root, sourceInTopic);

        publishStandard(
                TOPIC_DEPTH_PREFIX + symbol,
                symbol,
                root.toJSONString(),
                SNAPSHOT_DEPTH_PREFIX + symbol
        );
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

        enrichSource(root, sourceInTopic);
        enrichSource(k, sourceInTopic);

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

        long eventTs = root.getLongValue("E");
        if (eventTs <= 0) {
            eventTs = k.getLongValue("T");
        }
        if (shouldDropEvent("kline", symbol + ":" + interval, eventTs, klineMaxAgeMs, latestKlineEventTs, dropOutOfOrder)) {
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

        root.put("s", symbol);
        k.put("s", symbol);
        k.put("i", interval);

        // 提前读取 candleClosed 标志
        boolean candleClosed = k.getBooleanValue("x");

        // 标准通道推送优先于权威存储，避免下游因存储抖动感知不到实时K线。
        publishStandard(
                TOPIC_KLINE_PREFIX + symbol + "." + interval,
                symbol,
                root.toJSONString(),
                SNAPSHOT_KLINE_PREFIX + symbol + ":" + interval
        );

        marketDataCache.updateKline(symbol, interval, kline);

        // 写入历史K线快照（最近50根）
        updateKlineHistorySnapshot(symbol, interval, kline, candleClosed);
        KlineAuthorityService.AuthorityWriteResult authorityResult = KlineAuthorityService.AuthorityWriteResult.SKIPPED;
        boolean skipPersistence = false;
        if (authorityEnabled) {
            authorityResult = klineAuthorityService.saveRealtime(resolveAuthoritySource(sourceInTopic), kline, candleClosed, payload);
            if (authorityResult == KlineAuthorityService.AuthorityWriteResult.CONFLICT) {
                log.warn("[ExternalConsumer] Authority conflict ignored for closed candle, source={}, symbol={}, interval={}, openTime={}",
                        resolveAuthoritySource(sourceInTopic), symbol, interval, openTime);
                // 冲突保留审计记录，但不阻断实时推送链路。
                skipPersistence = true;
            }
            if (authorityResult == KlineAuthorityService.AuthorityWriteResult.DUPLICATE) {
                log.debug("[ExternalConsumer] Duplicate external kline skipped, source={}, symbol={}, interval={}, openTime={}, closed={}",
                        resolveAuthoritySource(sourceInTopic), symbol, interval, openTime, candleClosed);
                // 去重用于存储降噪，不影响标准推送输出。
                skipPersistence = true;
            }
        }

        if (!skipPersistence) {
            try {
                klineService.saveRealtimeKline(kline);
            } catch (Exception e) {
                // 外部行情链路优先保证实时可见，ClickHouse 异常时跳过持久化，避免 Kafka 重试风暴。
                log.warn("[ExternalConsumer] Failed to persist realtime kline, symbol={}, interval={}, openTime={}, reason={}",
                        symbol, interval, openTime, e.getMessage());
            }
        }

        if (candleClosed && !skipPersistence) {
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

        long eventTs = root.getLongValue("E");
        if (shouldDropEvent("ticker", symbol, eventTs, tickerMaxAgeMs, latestTickerEventTs, dropOutOfOrder)) {
            return;
        }

        root.put("s", symbol);
        enrichSource(root, sourceInTopic);

        publishStandard(
                TOPIC_TICKER_PREFIX + symbol,
                symbol,
                root.toJSONString(),
                SNAPSHOT_TICKER_PREFIX + symbol
        );

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

    private void publishStandard(String topic, String key, String payload, String snapshotKey) {
        if (!bridgeToStandard) {
            return;
        }

        if (kafkaEnabled) {
            try {
                kafkaTemplate.send(topic, key, payload);
            } catch (Exception e) {
                log.warn("[ExternalConsumer] Failed to publish standard topic={}, key={}, reason={}",
                        topic, key, e.getMessage());
            }
        }

        if (redisSnapshotEnabled) {
            try {
                redisTemplate.opsForValue().set(snapshotKey, payload);
            } catch (Exception e) {
                log.warn("[ExternalConsumer] Failed to write standard snapshot key={}, reason={}",
                        snapshotKey, e.getMessage());
            }
        }
    }

    private void enrichSource(JSONObject root, String sourceInTopic) {
        if (root == null) {
            return;
        }
        String source = root.getString("source");
        if (source != null && !source.isBlank()) {
            return;
        }
        if (sourceInTopic != null && !sourceInTopic.isBlank()) {
            root.put("source", sourceInTopic.toLowerCase(Locale.ROOT));
        } else {
            root.put("source", resolveAuthoritySource(null));
        }
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

    private boolean shouldDropEvent(String eventType,
                                    String key,
                                    long eventTs,
                                    long maxAgeMs,
                                    ConcurrentMap<String, Long> latestEventTs,
                                    boolean dropIfOutOfOrder) {
        if (eventTs <= 0) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (maxAgeMs > 0) {
            long age = now - eventTs;
            if (age > maxAgeMs) {
                long dropped = staleDropCount.incrementAndGet();
                if (shouldSampleLog(dropped)) {
                    log.warn("[ExternalConsumer] Drop stale {} event, key={}, eventTs={}, ageMs={}, maxAgeMs={}, dropped={}",
                            eventType, key, eventTs, age, maxAgeMs, dropped);
                }
                return true;
            }
        }

        if (!dropIfOutOfOrder) {
            latestEventTs.merge(key, eventTs, Math::max);
            return false;
        }

        Long prev = latestEventTs.get(key);
        if (prev != null && eventTs < prev) {
            long dropped = outOfOrderDropCount.incrementAndGet();
            if (shouldSampleLog(dropped)) {
                log.warn("[ExternalConsumer] Drop out-of-order {} event, key={}, eventTs={}, prevTs={}, dropped={}",
                        eventType, key, eventTs, prev, dropped);
            }
            return true;
        }
        latestEventTs.merge(key, eventTs, Math::max);
        return false;
    }

    private boolean shouldSampleLog(long count) {
        long sample = Math.max(1L, dropLogSampleInterval);
        return count == 1 || (count % sample == 0);
    }

    private String resolveAuthoritySource(String sourceInTopic) {
        if (authoritySource != null && !authoritySource.isBlank()) {
            return authoritySource.trim().toLowerCase(Locale.ROOT);
        }
        if (sourceInTopic == null || sourceInTopic.isBlank()) {
            return klineAuthorityService.getAuthoritySource();
        }
        return sourceInTopic.trim().toLowerCase(Locale.ROOT);
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

    /**
     * 更新K线历史快照（Redis List，最近50根）
     * 只在K线关闭时更新，避免未完成K线污染历史
     */
    private void updateKlineHistorySnapshot(String symbol, String interval, Kline kline, boolean candleClosed) {
        if (!candleClosed) {
            return; // 未关闭的K线不写入历史
        }

        if (!bridgeToStandard || !redisSnapshotEnabled) {
            return;
        }

        try {
            String historyKey = "market:snapshot:kline:history:" + symbol + ":" + interval;

            // 将Kline转换为JSON
            JSONObject klineJson = new JSONObject();
            klineJson.put("t", kline.getOpenTime());
            klineJson.put("T", kline.getCloseTime());
            klineJson.put("s", symbol);
            klineJson.put("i", interval);
            klineJson.put("o", formatScaled(kline.getOpenPrice()));
            klineJson.put("h", formatScaled(kline.getHighPrice()));
            klineJson.put("l", formatScaled(kline.getLowPrice()));
            klineJson.put("c", formatScaled(kline.getClosePrice()));
            klineJson.put("v", formatScaled(kline.getVolume()));
            klineJson.put("q", formatScaled(kline.getQuoteVolume()));
            klineJson.put("n", kline.getTradeCount());
            klineJson.put("V", formatScaled(kline.getTakerBuyVolume()));
            klineJson.put("Q", formatScaled(kline.getTakerBuyQuoteVolume()));
            klineJson.put("x", true); // 已关闭

            String klineStr = klineJson.toJSONString();

            // 添加到List头部（最新的在前面）
            redisTemplate.opsForList().leftPush(historyKey, klineStr);

            // 保留最近50根（使用trim确保List不会无限增长）
            redisTemplate.opsForList().trim(historyKey, 0, 49);

            // 设置过期时间（7天，防止冷门交易对占用内存）
            redisTemplate.expire(historyKey, 7, java.util.concurrent.TimeUnit.DAYS);

            log.debug("[ExternalConsumer] Updated kline history snapshot: {}:{}, openTime={}",
                    symbol, interval, kline.getOpenTime());

        } catch (Exception e) {
            log.warn("[ExternalConsumer] Failed to update kline history snapshot: {}:{}, reason={}",
                    symbol, interval, e.getMessage());
        }
    }

    private String formatScaled(long value) {
        return BigDecimal.valueOf(value)
                .divide(SCALE_BD, 8, RoundingMode.HALF_UP)
                .toPlainString();
    }
}
