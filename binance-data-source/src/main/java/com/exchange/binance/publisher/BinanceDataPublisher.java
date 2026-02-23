package com.exchange.binance.publisher;

import com.alibaba.fastjson2.JSONObject;
import com.exchange.binance.config.BinanceDataSourceConfig;
import com.exchange.binance.model.BinanceDepth;
import com.exchange.binance.model.BinanceTrade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 币安数据发布器
 * 
 * 职责：
 * - 将币安数据转换为内部标准格式
 * - 发布到Kafka供其他服务消费
 * - 更新Redis快照
 * 
 * 输出Topic格式：
 * - market.depth.{symbol}     - 深度数据
 * - market.trade.{symbol}     - 逐笔成交
 * - market.aggtrade.{symbol}  - 聚合成交
 * - market.ticker.{symbol}    - 24h统计
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

    // Redis Key前缀
    private static final String REDIS_DEPTH_PREFIX = "binance:depth:";
    private static final String REDIS_TRADE_PREFIX = "binance:trade:";
    private static final String REDIS_TICKER_PREFIX = "binance:ticker:";

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
            String topic = String.format(config.getKafka().getDepthTopicFormat(), symbol);
            
            // 构建标准消息
            JSONObject message = buildDepthMessage(depth);
            String json = message.toJSONString();

            // 发布到Kafka
            if (config.getKafka().isEnabled()) {
                kafkaTemplate.send(topic, symbol, json);
            }

            // 更新Redis快照
            redisTemplate.opsForValue().set(REDIS_DEPTH_PREFIX + symbol, json);

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
            String topic = String.format(config.getKafka().getTradeTopicFormat(), symbol);

            // 构建标准消息
            JSONObject message = buildTradeMessage(trade);
            String json = message.toJSONString();

            // 发布到Kafka
            if (config.getKafka().isEnabled()) {
                kafkaTemplate.send(topic, symbol, json);
            }

            // 更新Redis快照（保留最近100条）
            String redisKey = REDIS_TRADE_PREFIX + symbol;
            redisTemplate.opsForList().leftPush(redisKey, json);
            redisTemplate.opsForList().trim(redisKey, 0, 99);

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
            String topic = String.format(config.getKafka().getAggTradeTopicFormat(), symbol);

            // 构建聚合成交消息
            JSONObject message = buildAggTradeMessage(trade);
            String json = message.toJSONString();

            // 发布到Kafka
            if (config.getKafka().isEnabled()) {
                kafkaTemplate.send(topic, symbol, json);
            }

            // 同时发布到普通trade topic（聚合成交可以当作成交使用）
            String tradeTopic = String.format(config.getKafka().getTradeTopicFormat(), symbol);
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
            String topic = String.format(config.getKafka().getTickerTopicFormat(), symbol);

            // 构建Ticker消息
            JSONObject message = buildTickerMessage(ticker);
            String json = message.toJSONString();

            // 发布到Kafka
            if (config.getKafka().isEnabled()) {
                kafkaTemplate.send(topic, symbol, json);
            }

            // 更新Redis快照
            redisTemplate.opsForValue().set(REDIS_TICKER_PREFIX + symbol, json);

            // 发布到Redis
            redisTemplate.convertAndSend("binance:ticker:" + symbol, json);

            log.trace("[BinancePublisher] Published ticker for {}", symbol);

        } catch (Exception e) {
            log.error("[BinancePublisher] Failed to publish ticker: {}", ticker, e);
        }
    }

    // ========== 消息构建方法 ==========

    /**
     * 构建深度消息（与内部系统兼容的格式）
     */
    private JSONObject buildDepthMessage(BinanceDepth depth) {
        JSONObject msg = new JSONObject();
        msg.put("e", "depthUpdate");           // event type
        msg.put("E", System.currentTimeMillis()); // event time
        msg.put("s", depth.getSymbol());
        msg.put("U", depth.getFirstUpdateId());
        msg.put("u", depth.getLastUpdateId());
        msg.put("pu", depth.getLastUpdateId() - 1); // previous update id
        msg.put("b", convertLevels(depth.getBids()));
        msg.put("a", convertLevels(depth.getAsks()));
        msg.put("source", "binance");           // 数据来源标识
        return msg;
    }

    /**
     * 构建成交消息
     */
    private JSONObject buildTradeMessage(BinanceTrade trade) {
        JSONObject msg = new JSONObject();
        msg.put("e", "trade");
        msg.put("E", System.currentTimeMillis());
        msg.put("s", trade.getSymbol());
        msg.put("t", trade.getTradeId());
        msg.put("p", trade.getPrice());
        msg.put("q", trade.getQuantity());
        msg.put("T", trade.getTradeTime());
        msg.put("m", trade.isBuyerMaker());
        msg.put("source", "binance");
        return msg;
    }

    /**
     * 构建聚合成交消息
     */
    private JSONObject buildAggTradeMessage(BinanceTrade trade) {
        JSONObject msg = new JSONObject();
        msg.put("e", "aggTrade");
        msg.put("E", System.currentTimeMillis());
        msg.put("s", trade.getSymbol());
        msg.put("a", trade.getTradeId());
        msg.put("p", trade.getPrice());
        msg.put("q", trade.getQuantity());
        msg.put("f", trade.getFirstTradeId());
        msg.put("l", trade.getLastTradeId());
        msg.put("T", trade.getTradeTime());
        msg.put("m", trade.isBuyerMaker());
        msg.put("source", "binance");
        return msg;
    }

    /**
     * 构建Ticker消息
     */
    private JSONObject buildTickerMessage(BinanceTrade ticker) {
        JSONObject msg = new JSONObject();
        msg.put("e", "24hrTicker");
        msg.put("E", System.currentTimeMillis());
        msg.put("s", ticker.getSymbol());
        msg.put("p", ticker.getPriceChange());
        msg.put("P", ticker.getPriceChangePercent());
        msg.put("w", ticker.getWeightedAvgPrice());
        msg.put("x", ticker.getOpenPrice());
        msg.put("c", ticker.getPrice());
        msg.put("Q", ticker.getQuantity());
        msg.put("o", ticker.getOpenPrice());
        msg.put("h", ticker.getHighPrice());
        msg.put("l", ticker.getLowPrice());
        msg.put("v", ticker.getVolume());
        msg.put("q", ticker.getQuoteVolume());
        msg.put("O", ticker.getOpenTime());
        msg.put("C", ticker.getCloseTime());
        msg.put("F", ticker.getFirstTradeId());
        msg.put("L", ticker.getTradeId());
        msg.put("n", ticker.getTradeCount());
        msg.put("source", "binance");
        return msg;
    }

    /**
     * 转换价格档位格式
     * 将 [[price, qty], ...] 转换为适合JSON序列化的格式
     */
    private List<String[]> convertLevels(List<long[]> levels) {
        return levels.stream()
                .map(level -> new String[]{
                        String.valueOf(level[0]),
                        String.valueOf(level[1])
                })
                .toList();
    }
}
