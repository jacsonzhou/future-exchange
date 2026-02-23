package com.exchange.market.publisher;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.market.model.Kline;
import com.exchange.market.model.Trade;
import com.exchange.market.engine.TradeEngine.TradeStats24h;
import com.exchange.market.cache.MarketDataCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 行情数据发布器（Market Data Publisher）
 * 
 * 职责：
 * - 将行情数据发布到Kafka（供Public Push System消费）
 * - 更新本地缓存和Redis快照
 * - 异步处理，不阻塞主线程
 * 
 * 架构分层：
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    Market Data Engine                        │
 * │                    (计算层，端口8095)                         │
 * │                                                              │
 * │   Input: match-event-topic (from Match Engine)               │
 * │   Output: market-events (to Kafka)                           │
 * │           market:snapshot:* (to Redis，供快照查询)            │
 * └─────────────────────────────────────────────────────────────┘
 *                              │
 *                              ▼ Kafka (market-events)
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    Public Push System                        │
 * │                    (推送层，端口8096)                         │
 * │                                                              │
 * │   Input: market-events (from Kafka)                          │
 * │   Output: WebSocket (to Clients)                             │
 * └─────────────────────────────────────────────────────────────┘
 * 
 * 设计原则：
 * - 计算层只负责计算和发布到Kafka
 * - 推送层只负责消费Kafka和WebSocket推送
 * - 两者完全解耦，可独立扩容
 * 
 * 对标：Binance/OKX Market Data Stream
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketDataPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MarketDataCache marketDataCache;

    @Value("${market-data.kafka.enabled:true}")
    private boolean kafkaEnabled;

    // Kafka Topic 前缀
    private static final String TOPIC_TRADE = "market.trade.";
    private static final String TOPIC_AGG_TRADE = "market.aggtrade.";
    private static final String TOPIC_KLINE = "market.kline.";
    private static final String TOPIC_TICKER = "market.ticker.";
    private static final String TOPIC_DEPTH = "market.depth.";
    private static final String TOPIC_MARK_PRICE = "market.markprice.";
    private static final String TOPIC_TICKER_ALL = "market.ticker.all";
    private static final String TOPIC_MARK_PRICE_ALL = "market.markprice.all";

    // Redis Channel Topics (向后兼容)
    private static final String CHANNEL_TRADE = "market:trade:";
    private static final String CHANNEL_AGG_TRADE = "market:aggtrade:";
    private static final String CHANNEL_KLINE = "market:kline:";
    private static final String CHANNEL_TICKER = "market:ticker:";
    private static final String CHANNEL_DEPTH = "market:depth:";
    private static final String CHANNEL_MARK_PRICE = "market:markprice:";
    private static final String CHANNEL_ALL_TICKER = "market:ticker:all";
    private static final String CHANNEL_ALL_MARK_PRICE = "market:markprice:all";

    // Redis Snapshot Keys
    private static final String SNAPSHOT_DEPTH = "market:snapshot:depth:";
    private static final String SNAPSHOT_TICKER = "market:snapshot:ticker:";
    private static final String SNAPSHOT_TRADE = "market:snapshot:trade:";
    private static final String SNAPSHOT_KLINE = "market:snapshot:kline:";

    /**
     * 发布实时成交
     * 
     * 输出：
     * - Kafka: market.trade.{symbol}
     * - Redis Pub/Sub: market:trade:{symbol} (向后兼容)
     * - Redis Snapshot: market:snapshot:trade:{symbol}
     */
    @Async("marketDataTaskExecutor")
    public void publishTrade(String symbol, Trade trade) {
        try {
            // 更新本地缓存
            marketDataCache.updateLastTrade(symbol, trade);

            // 构建标准消息格式
            JSONObject message = buildTradeMessage(trade);
            String json = message.toJSONString();

            // 1. 发布到Kafka（主要输出，供Public Push System消费）
            if (kafkaEnabled) {
                String topic = TOPIC_TRADE + symbol;
                kafkaTemplate.send(topic, symbol, json);
            }

            // 2. 更新Redis快照（供Public Push System获取初始快照）
            redisTemplate.opsForValue().set(SNAPSHOT_TRADE + symbol, json);

            // 3. 发布到Redis Pub/Sub（向后兼容，老版本WebSocket使用）
            String channel = CHANNEL_TRADE + symbol;
            redisTemplate.convertAndSend(channel, trade);
            redisTemplate.convertAndSend(CHANNEL_TRADE + "all:trade", trade);

        } catch (Exception e) {
            log.error("[Publisher] Failed to publish trade for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * 发布聚合成交（100ms窗口）
     * 
     * 输出：
     * - Kafka: market.aggtrade.{symbol}
     * - Redis Pub/Sub: market:aggtrade:{symbol}
     */
    @Async("marketDataTaskExecutor")
    public void publishAggTrade(String symbol, List<Trade> trades, long windowStart) {
        if (trades == null || trades.isEmpty()) {
            return;
        }

        try {
            // 计算聚合信息
            AggTrade aggTrade = calculateAggTrade(symbol, trades, windowStart);

            // 更新缓存
            marketDataCache.updateAggTrade(symbol, aggTrade);

            // 发布到Kafka
            if (kafkaEnabled) {
                String topic = TOPIC_AGG_TRADE + symbol;
                kafkaTemplate.send(topic, symbol, JSON.toJSONString(aggTrade));
            }

            // 发布到Redis
            String channel = CHANNEL_AGG_TRADE + symbol;
            redisTemplate.convertAndSend(channel, aggTrade);

        } catch (Exception e) {
            log.error("[Publisher] Failed to publish agg trade for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * 发布K线更新（实时）
     * 
     * 输出：
     * - Kafka: market.kline.{symbol}.{interval}
     * - Redis Pub/Sub: market:kline:{symbol}:{interval}
     * - Redis Snapshot: market:snapshot:kline:{symbol}:{interval}
     */
    @Async("marketDataTaskExecutor")
    public void publishKline(String symbol, String interval, Kline kline) {
        try {
            // 更新缓存
            marketDataCache.updateKline(symbol, interval, kline);

            // 构建标准消息
            JSONObject message = buildKlineMessage(kline);
            String json = message.toJSONString();

            // 发布到Kafka
            if (kafkaEnabled) {
                String topic = TOPIC_KLINE + symbol + "." + interval;
                kafkaTemplate.send(topic, symbol, json);
            }

            // 更新Redis快照
            redisTemplate.opsForValue().set(SNAPSHOT_KLINE + symbol + ":" + interval, json);

            // 发布到Redis
            String channel = CHANNEL_KLINE + symbol + ":" + interval;
            redisTemplate.convertAndSend(channel, kline);

        } catch (Exception e) {
            log.error("[Publisher] Failed to publish kline for {} {}: {}", symbol, interval, e.getMessage());
        }
    }

    /**
     * 发布K线关闭（周期结束）
     * 
     * 输出：
     * - Kafka: market.kline.{symbol}.{interval} (with closed flag)
     */
    @Async("marketDataTaskExecutor")
    public void publishKlineClosed(String symbol, String interval, Kline kline) {
        try {
            // 更新缓存
            marketDataCache.closeKline(symbol, interval, kline);

            // 构建标准消息（标记为已关闭）
            JSONObject message = buildKlineMessage(kline);
            message.put("closed", true);
            String json = message.toJSONString();

            // 发布到Kafka
            if (kafkaEnabled) {
                String topic = TOPIC_KLINE + symbol + "." + interval;
                kafkaTemplate.send(topic, symbol, json);
            }

        } catch (Exception e) {
            log.error("[Publisher] Failed to publish closed kline for {} {}: {}", symbol, interval, e.getMessage());
        }
    }

    /**
     * 发布Ticker更新（24h统计）
     * 
     * 输出：
     * - Kafka: market.ticker.{symbol}
     * - Kafka: market.ticker.all
     * - Redis Pub/Sub: market:ticker:{symbol}
     * - Redis Snapshot: market:snapshot:ticker:{symbol}
     */
    @Async("marketDataTaskExecutor")
    public void publishTicker(String symbol, TradeStats24h stats) {
        try {
            // 更新缓存
            marketDataCache.updateTicker(symbol, stats);

            // 构建标准消息
            JSONObject message = buildTickerMessage(symbol, stats);
            String json = message.toJSONString();

            // 发布到Kafka（单symbol）
            if (kafkaEnabled) {
                String topic = TOPIC_TICKER + symbol;
                kafkaTemplate.send(topic, symbol, json);
            }

            // 更新Redis快照
            redisTemplate.opsForValue().set(SNAPSHOT_TICKER + symbol, json);

            // 发布到Redis
            String channel = CHANNEL_TICKER + symbol;
            redisTemplate.convertAndSend(channel, stats);
            redisTemplate.convertAndSend(CHANNEL_ALL_TICKER, stats);

        } catch (Exception e) {
            log.error("[Publisher] Failed to publish ticker for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * 发布全市场Ticker
     * 
     * 输出：
     * - Kafka: market.ticker.all
     */
    public void publishAllTicker(JSONObject allTicker) {
        try {
            if (kafkaEnabled) {
                kafkaTemplate.send(TOPIC_TICKER_ALL, "all", allTicker.toJSONString());
            }
            redisTemplate.convertAndSend(CHANNEL_ALL_TICKER, allTicker);
        } catch (Exception e) {
            log.error("[Publisher] Failed to publish all ticker: {}", e.getMessage());
        }
    }

    /**
     * 发布深度更新
     * 
     * 输出：
     * - Kafka: market.depth.{symbol}
     * - Redis Pub/Sub: market:depth:{symbol}
     * - Redis Snapshot: market:snapshot:depth:{symbol}
     */
    @Async("marketDataTaskExecutor")
    public void publishDepth(String symbol, DepthUpdate depthUpdate) {
        try {
            // 更新缓存
            marketDataCache.updateDepth(symbol, depthUpdate);

            // 构建标准消息
            JSONObject message = buildDepthMessage(depthUpdate);
            String json = message.toJSONString();

            // 发布到Kafka
            if (kafkaEnabled) {
                String topic = TOPIC_DEPTH + symbol;
                kafkaTemplate.send(topic, symbol, json);
            }

            // 更新Redis快照（🔥 FIX: 每次更新都写入，供public-push-core获取）
            redisTemplate.opsForValue().set(SNAPSHOT_DEPTH + symbol, json);

            // 发布到Redis
            String channel = CHANNEL_DEPTH + symbol;
            redisTemplate.convertAndSend(channel, depthUpdate);

        } catch (Exception e) {
            log.error("[Publisher] Failed to publish depth for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * 发布标记价格
     * 
     * 输出：
     * - Kafka: market.markprice.{symbol}
     * - Kafka: market.markprice.all
     * - Redis Pub/Sub: market:markprice:{symbol}
     */
    @Async("marketDataTaskExecutor")
    public void publishMarkPrice(String symbol, MarkPrice markPrice) {
        try {
            // 更新缓存
            marketDataCache.updateMarkPrice(symbol, markPrice);

            // 构建标准消息
            JSONObject message = buildMarkPriceMessage(markPrice);
            String json = message.toJSONString();

            // 发布到Kafka
            if (kafkaEnabled) {
                String topic = TOPIC_MARK_PRICE + symbol;
                kafkaTemplate.send(topic, symbol, json);

                // 发布到全市场topic
                kafkaTemplate.send(TOPIC_MARK_PRICE_ALL, symbol, json);
            }

            // 发布到Redis
            String channel = CHANNEL_MARK_PRICE + symbol;
            redisTemplate.convertAndSend(channel, markPrice);
            redisTemplate.convertAndSend(CHANNEL_ALL_MARK_PRICE, markPrice);

        } catch (Exception e) {
            log.error("[Publisher] Failed to publish mark price for {}: {}", symbol, e.getMessage());
        }
    }

    // ========== 消息构建方法 ==========

    private JSONObject buildTradeMessage(Trade trade) {
        JSONObject msg = new JSONObject();
        msg.put("e", "trade");  // event type
        msg.put("E", System.currentTimeMillis());  // event time
        msg.put("s", trade.getSymbol());
        msg.put("t", trade.getTradeId());
        msg.put("p", trade.getPrice());
        msg.put("q", trade.getQuantity());
        msg.put("T", trade.getTimestamp());
        msg.put("m", trade.isBuyerMaker());
        return msg;
    }

    private JSONObject buildKlineMessage(Kline kline) {
        JSONObject msg = new JSONObject();
        msg.put("e", "kline");
        msg.put("E", System.currentTimeMillis());
        msg.put("s", kline.getSymbol());
        
        JSONObject k = new JSONObject();
        k.put("t", kline.getOpenTime());
        k.put("T", kline.getCloseTime());
        k.put("s", kline.getSymbol());
        k.put("i", kline.getInterval());
        k.put("o", kline.getOpenPrice());
        k.put("c", kline.getClosePrice());
        k.put("h", kline.getHighPrice());
        k.put("l", kline.getLowPrice());
        k.put("v", kline.getVolume());
        k.put("n", kline.getTradeCount());
        k.put("x", false);  // not closed yet
        k.put("q", kline.getQuoteVolume());
        k.put("V", kline.getTakerBuyVolume());
        k.put("Q", kline.getTakerBuyQuoteVolume());
        
        msg.put("k", k);
        return msg;
    }

    private JSONObject buildTickerMessage(String symbol, TradeStats24h stats) {
        JSONObject msg = new JSONObject();
        msg.put("e", "24hrTicker");
        msg.put("E", System.currentTimeMillis());
        msg.put("s", symbol);
        msg.put("p", stats.getPriceChange());
        msg.put("P", stats.getPriceChangePercent());
        msg.put("w", stats.getWeightedAvgPrice());
        msg.put("x", stats.getOpenPrice()); // 使用开盘价作为前收盘价
        msg.put("c", stats.getLastPrice());
        msg.put("Q", stats.getLastQty());
        msg.put("b", 0L); // BBO需要从OrderBook获取，这里暂时为0
        msg.put("B", 0L);
        msg.put("a", 0L);
        msg.put("A", 0L);
        msg.put("o", stats.getOpenPrice());
        msg.put("h", stats.getHighPrice());
        msg.put("l", stats.getLowPrice());
        msg.put("v", stats.getVolume());
        msg.put("q", stats.getQuoteVolume());
        msg.put("O", stats.getOpenTime());
        msg.put("C", stats.getCloseTime());
        msg.put("F", stats.getFirstId());
        msg.put("L", stats.getLastId());
        msg.put("n", stats.getCount());
        return msg;
    }

    private JSONObject buildDepthMessage(DepthUpdate depthUpdate) {
        JSONObject msg = new JSONObject();
        msg.put("e", "depthUpdate");
        msg.put("E", System.currentTimeMillis());
        msg.put("s", depthUpdate.getSymbol());
        msg.put("U", depthUpdate.getFirstUpdateId());
        msg.put("u", depthUpdate.getLastUpdateId());
        msg.put("pu", depthUpdate.getLastUpdateId() - 1);  // prev update id
        msg.put("b", depthUpdate.getBids());
        msg.put("a", depthUpdate.getAsks());
        return msg;
    }

    private JSONObject buildMarkPriceMessage(MarkPrice markPrice) {
        JSONObject msg = new JSONObject();
        msg.put("e", "markPriceUpdate");
        msg.put("E", System.currentTimeMillis());
        msg.put("s", markPrice.getSymbol());
        msg.put("p", markPrice.getMarkPrice());
        msg.put("i", markPrice.getIndexPrice());
        msg.put("P", markPrice.getEstimatedSettlePrice());
        msg.put("r", markPrice.getLastFundingRate());
        msg.put("T", markPrice.getNextFundingTime());
        return msg;
    }

    // ========== 聚合计算 ==========

    private AggTrade calculateAggTrade(String symbol, List<Trade> trades, long windowStart) {
        if (trades.isEmpty()) {
            return null;
        }

        Trade first = trades.get(0);
        Trade last = trades.get(trades.size() - 1);

        long totalQty = 0;
        long totalQuoteQty = 0;
        long high = first.getPrice();
        long low = first.getPrice();

        for (Trade trade : trades) {
            totalQty += trade.getQuantity();
            totalQuoteQty += trade.getPrice() * trade.getQuantity();
            high = Math.max(high, trade.getPrice());
            low = Math.min(low, trade.getPrice());
        }

        AggTrade agg = new AggTrade();
        agg.setSymbol(symbol);
        agg.setAggId(windowStart);
        agg.setPrice(last.getPrice());
        agg.setQuantity(totalQty);
        agg.setFirstTradeId(first.getTradeId());
        agg.setLastTradeId(last.getTradeId());
        agg.setTimestamp(windowStart);
        // AggTrade没有isBuyerMaker字段，移除

        return agg;
    }

    // ========== DTO 类定义 ==========

    @lombok.Data
    public static class AggTrade {
        private String symbol;
        private long aggId;
        private long price;
        private long quantity;
        private long firstTradeId;
        private long lastTradeId;
        private long timestamp;
        private boolean buyerMaker;
        
        public boolean getIsBuyerMaker() { return buyerMaker; }
        public void setIsBuyerMaker(boolean buyerMaker) { this.buyerMaker = buyerMaker; }
    }

    @lombok.Data
    public static class DepthUpdate {
        private String symbol;
        private long firstUpdateId;
        private long lastUpdateId;
        private List<long[]> bids;
        private List<long[]> asks;
        private long timestamp;
        private boolean isSnapshot;
    }

    @lombok.Data
    public static class MarkPrice {
        private String symbol;
        private long markPrice;
        private long indexPrice;
        private long estimatedSettlePrice;
        private long lastFundingRate;
        private long nextFundingTime;
        private long timestamp;
    }
}
