package com.exchange.binance.handler;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.binance.config.BinanceDataSourceConfig;
import com.exchange.binance.fetcher.BinanceSnapshotFetcher;
import com.exchange.binance.manager.OrderBookManager;
import com.exchange.binance.metrics.BinanceMetricsCollector;
import com.exchange.binance.model.BinanceDepth;
import com.exchange.binance.model.BinanceTrade;
import com.exchange.binance.publisher.BinanceDataPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 币安消息处理器（增强版）
 *
 * 职责：
 * - 解析币安WebSocket消息
 * - 管理本地订单簿（序号检查 + 自动重建）
 * - 转换为内部数据模型
 * - 发布到内部系统
 *
 * 核心增强：
 * ✅ 订单簿序号连续性检查
 * ✅ 自动触发快照重建
 * ✅ 数据质量校验（价格合理性、新鲜度）
 * ✅ 仅发布ACTIVE状态的订单簿
 *
 * 币安消息格式：
 * - 深度: {"e":"depthUpdate", "E":123456789, "s":"BTCUSDT", "U":100, "u":110, "b":[["50000","1.5"]], "a":[["50001","2.0"]]}
 * - 成交: {"e":"trade", "E":123456789, "s":"BTCUSDT", "t":123, "p":"50000", "q":"0.5", "T":123456, "m":true}
 * - 聚合成交: {"e":"aggTrade", "E":123456789, "s":"BTCUSDT", "a":123, "p":"50000", "q":"0.5", "f":100, "l":110, "T":123456, "m":true}
 * - Ticker: {"e":"24hrTicker", "E":123456789, "s":"BTCUSDT", "p":"100", "P":"0.2", ...}
 */
@Slf4j
@Component
public class BinanceMessageHandler {

    @Autowired
    private BinanceDataPublisher publisher;

    @Autowired
    private BinanceSnapshotFetcher snapshotFetcher;

    @Autowired
    private BinanceDataSourceConfig config;

    @Autowired(required = false)
    @Lazy
    private BinanceMetricsCollector metricsCollector;

    // 每个symbol一个OrderBookManager
    private final ConcurrentHashMap<String, OrderBookManager> orderBookManagers =
            new ConcurrentHashMap<>();

    // 防重入重建控制：每个symbol同一时刻只允许一个重建任务
    private final Set<String> rebuildingSymbols = ConcurrentHashMap.newKeySet();

    // 金额精度：8位小数
    private static final int PRICE_SCALE = 8;
    private static final long PRICE_MULTIPLIER = 100_000_000L;

    // 数据新鲜度阈值（5秒）
    private static final long FRESHNESS_THRESHOLD_MS = 5000;

    // 价格变化幅度阈值（20%）
    private static final double PRICE_CHANGE_THRESHOLD = 0.20;

    // 统计指标
    private long totalMessagesHandled = 0;
    private long depthMessagesHandled = 0;
    private long tradeMessagesHandled = 0;
    private long tickerMessagesHandled = 0;

    /**
     * 处理币安消息
     *
     * @param message 币安原始消息（已解析为JSON）
     */
    public void handleMessage(JSONObject message) {
        totalMessagesHandled++;

        String eventType = message.getString("e");

        if (eventType == null) {
            // 可能是stream推送的格式 {"stream":"btcusdt@depth","data":{...}}
            if (message.containsKey("stream") && message.containsKey("data")) {
                String stream = message.getString("stream");
                JSONObject data = message.getJSONObject("data");

                if (stream.contains("@depth")) {
                    handleDepthUpdate(data);
                } else if (stream.contains("@trade")) {
                    handleTrade(data);
                } else if (stream.contains("@aggTrade")) {
                    handleAggTrade(data);
                } else if (stream.contains("@ticker")) {
                    handleTicker(data);
                } else if (stream.contains("@kline_")) {
                    handleKline(data);
                }
            }
            return;
        }

        switch (eventType) {
            case "depthUpdate":
                handleDepthUpdate(message);
                break;
            case "trade":
                handleTrade(message);
                break;
            case "aggTrade":
                handleAggTrade(message);
                break;
            case "24hrTicker":
                handleTicker(message);
                break;
            case "kline":
                handleKline(message);
                break;
            case "bookTicker":
                handleBookTicker(message);
                break;
            default:
                log.debug("[BinanceHandler] Unknown event type: {}", eventType);
        }
    }

    /**
     * 处理深度更新（增强版：集成序号检查）
     */
    private void handleDepthUpdate(JSONObject message) {
        depthMessagesHandled++;
        long startTime = System.nanoTime();

        try {
            String symbol = message.getString("s");
            long eventTime = message.getLongValue("E");
            long firstUpdateId = message.getLongValue("U");
            long lastUpdateId = message.getLongValue("u");

            // 数据新鲜度检查
            if (!checkFreshness(symbol, eventTime)) {
                return;
            }

            // 解析买卖盘
            List<long[]> bids = parsePriceLevels(message.getJSONArray("b"));
            List<long[]> asks = parsePriceLevels(message.getJSONArray("a"));

            // 获取或创建OrderBookManager
            OrderBookManager manager = orderBookManagers.computeIfAbsent(
                    symbol,
                    k -> {
                        log.info("[BinanceHandler] Creating OrderBookManager for {}", k);
                        return new OrderBookManager(k);
                    }
            );

            // 序号检查（核心逻辑）
            boolean success = manager.onDepthUpdate(firstUpdateId, lastUpdateId, bids, asks);

            if (!success) {
                triggerRebuildIfNeeded(manager, firstUpdateId, lastUpdateId);
                return;
            }

            // 仅在ACTIVE状态发布数据
            if (manager.getStatus() == OrderBookManager.OrderBookStatus.ACTIVE) {
                // 对外发布使用“本地重建后的完整前N档”，而不是原始增量，
                // 便于 public-push/前端直接展示稳定盘口快照。
                OrderBookManager.Snapshot snapshot = manager.getSnapshot(config.getDepthLevels());
                List<long[]> topBids = snapshot.getBids();
                List<long[]> topAsks = snapshot.getAsks();

                // 价格合理性检查
                if (!validatePrices(symbol, topBids, topAsks)) {
                    log.warn("[BinanceHandler] Price validation failed for {}, skip publishing", symbol);
                    return;
                }

                BinanceDepth depth = BinanceDepth.builder()
                        .symbol(symbol)
                        .eventTime(eventTime)
                        .firstUpdateId(firstUpdateId)
                        .lastUpdateId(lastUpdateId)
                        .bids(topBids)
                        .asks(topAsks)
                        .build();

                publisher.publishDepth(depth);

                long latencyNs = System.nanoTime() - startTime;

                // 记录处理延迟
                if (metricsCollector != null) {
                    metricsCollector.recordLatency(symbol, "depth_update", latencyNs);
                }

                log.trace("[BinanceHandler] Published depth for {}: U={}, u={}, latency={}μs",
                        symbol, firstUpdateId, lastUpdateId, latencyNs / 1000);

            } else {
                log.debug("[BinanceHandler] Skip publishing depth for {} in {} state",
                        symbol, manager.getStatus());
            }

        } catch (Exception e) {
            log.error("[BinanceHandler] Failed to handle depth update: {}", message, e);
        }
    }

    private void triggerRebuildIfNeeded(OrderBookManager manager, long firstUpdateId, long lastUpdateId) {
        String symbol = manager.getSymbol();
        if (!rebuildingSymbols.add(symbol)) {
            log.debug("[BinanceHandler] Rebuild already in progress for {}, skip duplicate trigger", symbol);
            return;
        }

        // 切换到 REBUILDING，后续增量进入缓冲区，避免快照窗口内消息丢失。
        manager.markRebuilding();
        log.warn("[BinanceHandler] OrderBook rebuild triggered for {}: U={}, u={}, last={}",
                symbol, firstUpdateId, lastUpdateId, manager.getLastUpdateId());
        rebuildOrderBookAsync(manager);
    }

    /**
     * 重建订单簿（异步执行，避免阻塞WebSocket线程）
     */
    @Async("binancePublisherExecutor")
    public void rebuildOrderBookAsync(OrderBookManager manager) {
        String symbol = manager.getSymbol();

        try {
            log.info("[BinanceHandler] Starting rebuild for {}", symbol);

            // 获取快照
            int limit = config.getDepthLevels();
            BinanceSnapshotFetcher.DepthSnapshot snapshot = snapshotFetcher.fetchSnapshot(symbol, limit);

            if (snapshot == null) {
                log.error("[BinanceHandler] ❌ Failed to fetch snapshot for {}, will retry on next gap",
                        symbol);
                manager.reset();
                return;
            }

            // 初始化订单簿
            manager.initFromSnapshot(
                    snapshot.getLastUpdateId(),
                    snapshot.getBids(),
                    snapshot.getAsks()
            );

            log.info("[BinanceHandler] ✅ OrderBook rebuilt for {}: lastUpdateId={}, bids={}, asks={}",
                    symbol, snapshot.getLastUpdateId(), snapshot.getBids().size(), snapshot.getAsks().size());

        } catch (Exception e) {
            log.error("[BinanceHandler] Error rebuilding orderbook for {}: {}", symbol, e.getMessage(), e);
            manager.reset();
        } finally {
            rebuildingSymbols.remove(symbol);
        }
    }

    /**
     * 数据新鲜度检查
     */
    private boolean checkFreshness(String symbol, long eventTime) {
        long now = System.currentTimeMillis();
        long age = now - eventTime;

        if (age > FRESHNESS_THRESHOLD_MS) {
            log.warn("[BinanceHandler] Stale data for {}: eventTime={}, now={}, age={}ms",
                    symbol, eventTime, now, age);
            return false;
        }

        return true;
    }

    /**
     * 价格合理性校验
     */
    private boolean validatePrices(String symbol, List<long[]> bids, List<long[]> asks) {
        // 检查1：买价必须低于卖价
        if (!bids.isEmpty() && !asks.isEmpty()) {
            long bestBid = bids.get(0)[0];
            long bestAsk = asks.get(0)[0];

            if (bestBid >= bestAsk) {
                log.error("[BinanceHandler] ❌ PRICE ANOMALY for {}: bid={} >= ask={}",
                        symbol, bestBid, bestAsk);
                return false;
            }

            // 检查2：价差不能过大（可能数据错误）
            long spread = bestAsk - bestBid;
            long midPrice = (bestBid + bestAsk) / 2;
            double spreadPercent = (double) spread / midPrice;

            if (spreadPercent > PRICE_CHANGE_THRESHOLD) {
                log.warn("[BinanceHandler] Large spread for {}: spread={}, percent={}%",
                        symbol, spread, spreadPercent * 100);
                // 不阻止发布，只记录告警
            }
        }

        return true;
    }

    /**
     * 处理逐笔成交
     */
    private void handleTrade(JSONObject message) {
        tradeMessagesHandled++;

        try {
            String symbol = message.getString("s");
            long eventTime = message.getLongValue("E");
            long tradeId = message.getLongValue("t");
            long price = parsePrice(message.getString("p"));
            long quantity = parseQuantity(message.getString("q"));
            long tradeTime = message.getLongValue("T");
            boolean isBuyerMaker = message.getBooleanValue("m");

            BinanceTrade trade = BinanceTrade.builder()
                    .symbol(symbol)
                    .eventTime(eventTime)
                    .tradeId(tradeId)
                    .price(price)
                    .quantity(quantity)
                    .tradeTime(tradeTime)
                    .isBuyerMaker(isBuyerMaker)
                    .build();

            publisher.publishTrade(trade);

        } catch (Exception e) {
            log.error("[BinanceHandler] Failed to handle trade: {}", message, e);
        }
    }

    /**
     * 处理聚合成交
     */
    private void handleAggTrade(JSONObject message) {
        try {
            String symbol = message.getString("s");
            long eventTime = message.getLongValue("E");
            long aggId = message.getLongValue("a");
            long price = parsePrice(message.getString("p"));
            long quantity = parseQuantity(message.getString("q"));
            long firstTradeId = message.getLongValue("f");
            long lastTradeId = message.getLongValue("l");
            long tradeTime = message.getLongValue("T");
            boolean isBuyerMaker = message.getBooleanValue("m");

            // 聚合成交转为内部Trade格式，使用aggId作为tradeId
            BinanceTrade trade = BinanceTrade.builder()
                    .symbol(symbol)
                    .eventTime(eventTime)
                    .tradeId(aggId)
                    .price(price)
                    .quantity(quantity)
                    .tradeTime(tradeTime)
                    .isBuyerMaker(isBuyerMaker)
                    .firstTradeId(firstTradeId)
                    .lastTradeId(lastTradeId)
                    .isAggTrade(true)
                    .build();

            publisher.publishAggTrade(trade);

        } catch (Exception e) {
            log.error("[BinanceHandler] Failed to handle agg trade: {}", message, e);
        }
    }

    /**
     * 处理24小时统计（Ticker）
     */
    private void handleTicker(JSONObject message) {
        tickerMessagesHandled++;

        try {
            String symbol = message.getString("s");
            long eventTime = message.getLongValue("E");

            // 价格变化
            long priceChange = parsePrice(message.getString("p"));
            double priceChangePercent = message.getDoubleValue("P");

            // 统计价格
            long weightedAvgPrice = parsePrice(message.getString("w"));
            long openPrice = parsePrice(message.getString("o"));
            long highPrice = parsePrice(message.getString("h"));
            long lowPrice = parsePrice(message.getString("l"));
            long lastPrice = parsePrice(message.getString("c"));
            long lastQty = parseQuantity(message.getString("Q"));

            // 成交量（quote volume是USDT数量）
            long volume = parseQuantity(message.getString("v"));
            long quoteVolume = parsePrice(message.getString("q")); // 使用parsePrice处理大数值

            // 统计ID
            long firstTradeId = message.getLongValue("F");
            long lastTradeId = message.getLongValue("L");
            int tradeCount = message.getIntValue("n");

            // 时间
            long openTime = message.getLongValue("O");
            long closeTime = message.getLongValue("C");

            // 构建Ticker对象（复用BinanceTrade作为Ticker载体）
            BinanceTrade ticker = BinanceTrade.builder()
                    .symbol(symbol)
                    .eventTime(eventTime)
                    .tradeId(lastTradeId)
                    .price(lastPrice)
                    .quantity(lastQty)
                    .tradeTime(eventTime)
                    .isBuyerMaker(false)
                    .isTicker(true)
                    .openPrice(openPrice)
                    .highPrice(highPrice)
                    .lowPrice(lowPrice)
                    .volume(volume)
                    .quoteVolume(quoteVolume)
                    .priceChange(priceChange)
                    .priceChangePercent(priceChangePercent)
                    .weightedAvgPrice(weightedAvgPrice)
                    .tradeCount(tradeCount)
                    .firstTradeId(firstTradeId)
                    .openTime(openTime)
                    .closeTime(closeTime)
                    .build();

            publisher.publishTicker(ticker);

        } catch (Exception e) {
            log.error("[BinanceHandler] Failed to handle ticker: {}", message, e);
        }
    }

    /**
     * 处理K线事件
     */
    private void handleKline(JSONObject message) {
        try {
            String symbol = message.getString("s");
            long eventTime = message.getLongValue("E");
            JSONObject kline = message.getJSONObject("k");
            if (symbol == null || symbol.isBlank() || kline == null) {
                return;
            }

            String interval = kline.getString("i");
            if (interval == null || interval.isBlank()) {
                return;
            }

            publisher.publishKline(symbol, interval, eventTime, kline);
        } catch (Exception e) {
            log.error("[BinanceHandler] Failed to handle kline: {}", message, e);
        }
    }

    /**
     * 处理最优挂单（BBO）
     */
    private void handleBookTicker(JSONObject message) {
        // 可以扩展支持bookTicker，用于更快速的BBO更新
        // 当前忽略
    }

    /**
     * 解析价格档位
     *
     * @param array JSON数组 [[price, qty], ...]
     * @return 内部格式列表 [[price(8位精度), qty(8位精度)], ...]
     */
    private List<long[]> parsePriceLevels(JSONArray array) {
        List<long[]> levels = new ArrayList<>();

        if (array == null) {
            return levels;
        }

        for (int i = 0; i < array.size(); i++) {
            JSONArray level = array.getJSONArray(i);
            if (level != null && level.size() >= 2) {
                String priceStr = level.getString(0);
                String qtyStr = level.getString(1);

                long price = parsePrice(priceStr);
                long qty = parseQuantity(qtyStr);

                // qty为0表示删除该档位
                levels.add(new long[]{price, qty});
            }
        }

        return levels;
    }

    /**
     * 解析价格字符串为long（8位精度）
     *
     * 示例："50000.50" -> 5000050000000
     *
     * @param priceStr 价格字符串
     * @return 8位精度long值
     */
    private long parsePrice(String priceStr) {
        if (priceStr == null || priceStr.isEmpty()) {
            return 0;
        }

        try {
            BigDecimal price = new BigDecimal(priceStr);
            return price.multiply(BigDecimal.valueOf(PRICE_MULTIPLIER))
                    .setScale(0, RoundingMode.DOWN)
                    .longValue();
        } catch (NumberFormatException e) {
            log.warn("[BinanceHandler] Invalid price format: {}", priceStr);
            return 0;
        }
    }

    /**
     * 解析数量字符串为long（8位精度）
     */
    private long parseQuantity(String qtyStr) {
        // 数量和价格使用相同的精度处理
        return parsePrice(qtyStr);
    }

    // ========== 订单簿查询接口（供Controller使用） ==========

    /**
     * 获取订单簿快照
     */
    public OrderBookManager.Snapshot getOrderBookSnapshot(String symbol, int depth) {
        OrderBookManager manager = orderBookManagers.get(symbol.toUpperCase());
        if (manager == null) {
            return null;
        }
        return manager.getSnapshot(depth);
    }

    /**
     * 获取BBO
     */
    public OrderBookManager.BBO getBBO(String symbol) {
        OrderBookManager manager = orderBookManagers.get(symbol.toUpperCase());
        if (manager == null) {
            return null;
        }
        return manager.getBBO();
    }

    /**
     * 获取订单簿指标
     */
    public OrderBookManager.OrderBookMetrics getOrderBookMetrics(String symbol) {
        OrderBookManager manager = orderBookManagers.get(symbol.toUpperCase());
        if (manager == null) {
            return null;
        }
        return manager.getMetrics();
    }

    /**
     * 获取所有订单簿管理器
     */
    public ConcurrentHashMap<String, OrderBookManager> getAllOrderBookManagers() {
        return orderBookManagers;
    }

    /**
     * 手动触发重建
     */
    public void manualRebuild(String symbol) {
        OrderBookManager manager = orderBookManagers.get(symbol.toUpperCase());
        if (manager != null) {
            log.warn("[BinanceHandler] Manual rebuild triggered for {}", symbol);
            rebuildOrderBookAsync(manager);
        }
    }

    /**
     * 获取统计指标
     */
    public HandlerMetrics getMetrics() {
        return new HandlerMetrics(
                totalMessagesHandled,
                depthMessagesHandled,
                tradeMessagesHandled,
                tickerMessagesHandled,
                orderBookManagers.size()
        );
    }

    /**
     * 统计指标
     */
    public record HandlerMetrics(
            long totalMessagesHandled,
            long depthMessagesHandled,
            long tradeMessagesHandled,
            long tickerMessagesHandled,
            int activeOrderBooks
    ) {}
}
