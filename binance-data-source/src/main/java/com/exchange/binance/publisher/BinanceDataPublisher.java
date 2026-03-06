package com.exchange.binance.publisher;

import com.alibaba.fastjson2.JSONObject;
import com.exchange.binance.config.BinanceDataSourceConfig;
import com.exchange.binance.model.BinanceDepth;
import com.exchange.binance.model.BinanceTrade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 币安数据发布器
 * 
 * 职责：
 * - 将币安数据转换为内部标准格式
 * - 发布到Kafka供其他服务消费
 * - 更新Redis快照
 * 
 * 输出Topic格式（外部数据通道）：
 * - market.ext.{source}.depth.{symbol}     - 深度数据
 * - market.ext.{source}.trade.{symbol}     - 逐笔成交
 * - market.ext.{source}.aggtrade.{symbol}  - 聚合成交
 * - market.ext.{source}.ticker.{symbol}    - 24h统计
 * - market.ext.{source}.kline.{symbol}.{interval} - K线
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceDataPublisher {

    @Autowired
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private final BinanceDataSourceConfig config;

    @Value("${binance.datasource.publish-standard-compatible:true}")
    private boolean publishStandardCompatible;

    // 兼容旧查询接口的Redis Key前缀
    private static final String REDIS_DEPTH_PREFIX = "binance:depth:";
    private static final String REDIS_TRADE_PREFIX = "binance:trade:";
    private static final String REDIS_TICKER_PREFIX = "binance:ticker:";

    // 标准化快照Key（供 public-push-core 快照首包使用）
    private static final String SNAPSHOT_DEPTH_PREFIX = "market:snapshot:depth:ext:";
    private static final String SNAPSHOT_TRADE_PREFIX = "market:snapshot:trade:ext:";
    private static final String SNAPSHOT_TICKER_PREFIX = "market:snapshot:ticker:ext:";
    private static final String SNAPSHOT_KLINE_PREFIX = "market:snapshot:kline:ext:";

    // 标准通道兼容（用于未切到 ext.* 订阅的客户端）
    private static final String STANDARD_TOPIC_DEPTH_PREFIX = "market.depth.";
    private static final String STANDARD_TOPIC_TRADE_PREFIX = "market.trade.";
    private static final String STANDARD_TOPIC_TICKER_PREFIX = "market.ticker.";
    private static final String STANDARD_TOPIC_KLINE_PREFIX = "market.kline.";
    private static final String STANDARD_SNAPSHOT_DEPTH_PREFIX = "market:snapshot:depth:";
    private static final String STANDARD_SNAPSHOT_TRADE_PREFIX = "market:snapshot:trade:";
    private static final String STANDARD_SNAPSHOT_TICKER_PREFIX = "market:snapshot:ticker:";
    private static final String STANDARD_SNAPSHOT_KLINE_PREFIX = "market:snapshot:kline:";

    private static final long SCALE = 100_000_000L;
    private static final BigDecimal SCALE_BD = BigDecimal.valueOf(SCALE);

    /**
     * 发布深度数据
     */
    @Async("binancePublisherExecutor")
    public void publishDepth(BinanceDepth depth) {
        if (!depth.isValid()) {
            return;
        }

        try {
            String symbol = depth.getSymbol();
            String source = sourceName();
            String topic = String.format(config.getKafka().getDepthTopicFormat(), source, symbol);
            
            // 构建标准消息
            JSONObject message = buildDepthMessage(depth);
            String json = message.toJSONString();

            // 发布到Kafka
            if (config.getKafka().isEnabled()) {
                kafkaTemplate.send(topic, symbol, json);
                if (publishStandardCompatible) {
                    publishCompatibilityMessage(STANDARD_TOPIC_DEPTH_PREFIX + symbol, symbol, json);
                }
            }

            // 更新Redis快照
            redisTemplate.opsForValue().set(REDIS_DEPTH_PREFIX + symbol, json);
            redisTemplate.opsForValue().set(SNAPSHOT_DEPTH_PREFIX + source + ":" + symbol, json);
            if (publishStandardCompatible) {
                redisTemplate.opsForValue().set(STANDARD_SNAPSHOT_DEPTH_PREFIX + symbol, json);
            }

            // 发布到Redis Pub/Sub（向后兼容）
            redisTemplate.convertAndSend("binance:depth:" + symbol, json);

            log.trace("[BinancePublisher] Published depth for {}", symbol);

        } catch (Exception e) {
            log.error("[BinancePublisher] Failed to publish depth: {}", depth, e);
        }
    }

    /**
     * 发布逐笔成交
     */
    @Async("binancePublisherExecutor")
    public void publishTrade(BinanceTrade trade) {
        if (!trade.isValidTrade()) {
            return;
        }

        try {
            String symbol = trade.getSymbol();
            String source = sourceName();
            String topic = String.format(config.getKafka().getTradeTopicFormat(), source, symbol);

            // 构建标准消息
            JSONObject message = buildTradeMessage(trade);
            String json = message.toJSONString();

            // 发布到Kafka
            if (config.getKafka().isEnabled()) {
                kafkaTemplate.send(topic, symbol, json);
                if (publishStandardCompatible) {
                    publishCompatibilityMessage(STANDARD_TOPIC_TRADE_PREFIX + symbol, symbol, json);
                }
            }

            // 更新Redis快照（保留最近100条）
            String redisKey = REDIS_TRADE_PREFIX + symbol;
            redisTemplate.opsForList().leftPush(redisKey, json);
            redisTemplate.opsForList().trim(redisKey, 0, 99);
            redisTemplate.opsForValue().set(SNAPSHOT_TRADE_PREFIX + source + ":" + symbol, json);
            if (publishStandardCompatible) {
                redisTemplate.opsForValue().set(STANDARD_SNAPSHOT_TRADE_PREFIX + symbol, json);
            }

            // 发布到Redis Pub/Sub
            redisTemplate.convertAndSend("binance:trade:" + symbol, json);

            log.trace("[BinancePublisher] Published trade for {}: id={}", symbol, trade.getTradeId());

        } catch (Exception e) {
            log.error("[BinancePublisher] Failed to publish trade: {}", trade, e);
        }
    }

    /**
     * 发布聚合成交
     */
    @Async("binancePublisherExecutor")
    public void publishAggTrade(BinanceTrade trade) {
        if (!trade.isValidTrade()) {
            return;
        }

        try {
            String symbol = trade.getSymbol();
            String source = sourceName();
            String topic = String.format(config.getKafka().getAggTradeTopicFormat(), source, symbol);

            // 构建聚合成交消息
            JSONObject message = buildAggTradeMessage(trade);
            String json = message.toJSONString();

            // 发布到Kafka
            if (config.getKafka().isEnabled()) {
                kafkaTemplate.send(topic, symbol, json);
            }

            // 同时发布到普通trade topic（聚合成交可以当作成交使用）
            String tradeTopic = String.format(config.getKafka().getTradeTopicFormat(), source, symbol);
            kafkaTemplate.send(tradeTopic, symbol, buildTradeMessage(trade).toJSONString());

            // 发布到Redis
            redisTemplate.convertAndSend("binance:aggtrade:" + symbol, json);

            log.trace("[BinancePublisher] Published agg trade for {}: id={}", symbol, trade.getTradeId());

        } catch (Exception e) {
            log.error("[BinancePublisher] Failed to publish agg trade: {}", trade, e);
        }
    }

    /**
     * 发布Ticker数据
     */
    @Async("binancePublisherExecutor")
    public void publishTicker(BinanceTrade ticker) {
        if (!ticker.isValidTicker()) {
            return;
        }

        try {
            String symbol = ticker.getSymbol();
            String source = sourceName();
            String topic = String.format(config.getKafka().getTickerTopicFormat(), source, symbol);

            // 构建Ticker消息
            JSONObject message = buildTickerMessage(ticker);
            String json = message.toJSONString();

            // 发布到Kafka
            if (config.getKafka().isEnabled()) {
                kafkaTemplate.send(topic, symbol, json);
                if (publishStandardCompatible) {
                    publishCompatibilityMessage(STANDARD_TOPIC_TICKER_PREFIX + symbol, symbol, json);
                }
            }

            // 更新Redis快照
            redisTemplate.opsForValue().set(REDIS_TICKER_PREFIX + symbol, json);
            redisTemplate.opsForValue().set(SNAPSHOT_TICKER_PREFIX + source + ":" + symbol, json);
            if (publishStandardCompatible) {
                redisTemplate.opsForValue().set(STANDARD_SNAPSHOT_TICKER_PREFIX + symbol, json);
            }

            // 发布到Redis
            redisTemplate.convertAndSend("binance:ticker:" + symbol, json);

            log.trace("[BinancePublisher] Published ticker for {}", symbol);

        } catch (Exception e) {
            log.error("[BinancePublisher] Failed to publish ticker: {}", ticker, e);
        }
    }

    /**
     * 发布K线数据（外部通道）
     */
    @Async("binancePublisherExecutor")
    public void publishKline(String symbol, String interval, long eventTime, JSONObject klinePayload) {
        if (symbol == null || symbol.isBlank() || interval == null || interval.isBlank() || klinePayload == null) {
            return;
        }

        try {
            String source = sourceName();
            String topic = String.format(config.getKafka().getKlineTopicFormat(), source, symbol, interval);
            JSONObject message = buildKlineMessage(symbol, interval, eventTime, klinePayload);
            String json = message.toJSONString();

            if (config.getKafka().isEnabled()) {
                kafkaTemplate.send(topic, symbol, json);
                if (publishStandardCompatible) {
                    publishCompatibilityMessage(STANDARD_TOPIC_KLINE_PREFIX + symbol + "." + interval, symbol, json);
                }
            }

            redisTemplate.opsForValue().set(
                    SNAPSHOT_KLINE_PREFIX + source + ":" + symbol + ":" + interval,
                    json
            );
            if (publishStandardCompatible) {
                redisTemplate.opsForValue().set(STANDARD_SNAPSHOT_KLINE_PREFIX + symbol + ":" + interval, json);
            }

            // 兼容已有 binance 命名空间查询
            redisTemplate.opsForValue().set("binance:kline:" + symbol + ":" + interval, json);
            redisTemplate.convertAndSend("binance:kline:" + symbol + ":" + interval, json);
        } catch (Exception e) {
            log.error("[BinancePublisher] Failed to publish kline: symbol={}, interval={}", symbol, interval, e);
        }
    }

    // ========== 消息构建方法 ==========

    /**
     * 构建深度消息（与内部系统兼容的格式）
     */
    private JSONObject buildDepthMessage(BinanceDepth depth) {
        JSONObject msg = new JSONObject();
        msg.put("e", "depthUpdate");           // event type
        msg.put("E", depth.getEventTime()); // event time
        msg.put("s", depth.getSymbol());
        msg.put("U", depth.getFirstUpdateId());
        msg.put("u", depth.getLastUpdateId());
        msg.put("pu", depth.getLastUpdateId() - 1); // previous update id
        msg.put("b", convertLevels(depth.getBids()));
        msg.put("a", convertLevels(depth.getAsks()));
        msg.put("source", sourceName());           // 数据来源标识
        return msg;
    }

    /**
     * 构建成交消息
     */
    private JSONObject buildTradeMessage(BinanceTrade trade) {
        JSONObject msg = new JSONObject();
        msg.put("e", "trade");
        msg.put("E", trade.getEventTime());
        msg.put("s", trade.getSymbol());
        msg.put("t", trade.getTradeId());
        msg.put("p", formatScaled(trade.getPrice()));
        msg.put("q", formatScaled(trade.getQuantity()));
        msg.put("T", trade.getTradeTime());
        msg.put("m", trade.isBuyerMaker());
        msg.put("source", sourceName());
        return msg;
    }

    /**
     * 构建聚合成交消息
     */
    private JSONObject buildAggTradeMessage(BinanceTrade trade) {
        JSONObject msg = new JSONObject();
        msg.put("e", "aggTrade");
        msg.put("E", trade.getEventTime());
        msg.put("s", trade.getSymbol());
        msg.put("a", trade.getTradeId());
        msg.put("p", formatScaled(trade.getPrice()));
        msg.put("q", formatScaled(trade.getQuantity()));
        msg.put("f", trade.getFirstTradeId());
        msg.put("l", trade.getLastTradeId());
        msg.put("T", trade.getTradeTime());
        msg.put("m", trade.isBuyerMaker());
        msg.put("source", sourceName());
        return msg;
    }

    /**
     * 构建Ticker消息
     */
    private JSONObject buildTickerMessage(BinanceTrade ticker) {
        JSONObject msg = new JSONObject();
        msg.put("e", "24hrTicker");
        msg.put("E", ticker.getEventTime());
        msg.put("s", ticker.getSymbol());
        msg.put("p", formatScaled(ticker.getPriceChange()));
        msg.put("P", ticker.getPriceChangePercent());
        msg.put("w", formatScaled(ticker.getWeightedAvgPrice()));
        msg.put("x", formatScaled(ticker.getOpenPrice()));
        msg.put("c", formatScaled(ticker.getPrice()));
        msg.put("Q", formatScaled(ticker.getQuantity()));
        msg.put("o", formatScaled(ticker.getOpenPrice()));
        msg.put("h", formatScaled(ticker.getHighPrice()));
        msg.put("l", formatScaled(ticker.getLowPrice()));
        msg.put("v", formatScaled(ticker.getVolume()));
        msg.put("q", formatScaled(ticker.getQuoteVolume()));
        msg.put("O", ticker.getOpenTime());
        msg.put("C", ticker.getCloseTime());
        msg.put("F", ticker.getFirstTradeId());
        msg.put("L", ticker.getTradeId());
        msg.put("n", ticker.getTradeCount());
        msg.put("source", sourceName());
        return msg;
    }

    private JSONObject buildKlineMessage(String symbol, String interval, long eventTime, JSONObject kline) {
        JSONObject msg = new JSONObject();
        msg.put("e", "kline");
        msg.put("E", eventTime > 0 ? eventTime : System.currentTimeMillis());
        msg.put("s", symbol);

        JSONObject k = new JSONObject();
        k.put("t", kline.getLongValue("t"));
        k.put("T", kline.getLongValue("T"));
        k.put("s", symbol);
        k.put("i", interval);
        k.put("f", kline.getLongValue("f"));
        k.put("L", kline.getLongValue("L"));
        k.put("o", normalizeDecimal(kline.getString("o")));
        k.put("c", normalizeDecimal(kline.getString("c")));
        k.put("h", normalizeDecimal(kline.getString("h")));
        k.put("l", normalizeDecimal(kline.getString("l")));
        k.put("v", normalizeDecimal(kline.getString("v")));
        k.put("n", kline.getIntValue("n"));
        k.put("x", kline.getBooleanValue("x"));
        k.put("q", normalizeDecimal(kline.getString("q")));
        k.put("V", normalizeDecimal(kline.getString("V")));
        k.put("Q", normalizeDecimal(kline.getString("Q")));
        k.put("B", normalizeDecimal(kline.getString("B")));
        k.put("source", sourceName());

        msg.put("k", k);
        msg.put("source", sourceName());
        return msg;
    }

    /**
     * 转换价格档位格式
     * 将 [[price, qty], ...] 转换为适合JSON序列化的格式
     */
    private List<String[]> convertLevels(List<long[]> levels) {
        return levels.stream()
                .map(level -> new String[]{
                        formatScaled(level[0]),
                        formatScaled(level[1])
                })
                .toList();
    }

    private String sourceName() {
        return config.getSource() == null || config.getSource().isBlank()
                ? "binance"
                : config.getSource().toLowerCase();
    }

    private void publishCompatibilityMessage(String topic, String key, String json) {
        try {
            kafkaTemplate.send(topic, key, json);
        } catch (Exception e) {
            log.warn("[BinancePublisher] Failed to publish compatibility topic {}, key={}", topic, key, e);
        }
    }

    private String formatScaled(long value) {
        return BigDecimal.valueOf(value)
                .divide(SCALE_BD, 8, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private String normalizeDecimal(String value) {
        if (value == null || value.isBlank()) {
            return "0.00000000";
        }
        try {
            return new BigDecimal(value).setScale(8, RoundingMode.HALF_UP).toPlainString();
        } catch (Exception e) {
            return "0.00000000";
        }
    }
}
