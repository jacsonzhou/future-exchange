package com.exchange.binance.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.binance.client.BinanceWebSocketClient;
import com.exchange.binance.config.BinanceDataSourceConfig;
import com.exchange.binance.fetcher.BinanceSnapshotFetcher;
import com.exchange.binance.handler.BinanceMessageHandler;
import com.exchange.binance.manager.OrderBookManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 币安数据API控制器（增强版）
 *
 * 提供REST API用于：
 * - 查询币安深度快照（从本地订单簿）
 * - 查询订单簿状态与指标
 * - 查询币安最新成交
 * - 查询币安Ticker
 * - 查询服务状态
 * - 手动触发订单簿重建
 */
@RestController
@RequestMapping("/api/binance")
@RequiredArgsConstructor
public class BinanceDataController {

    @Autowired
    private final BinanceWebSocketClient wsClient;

    @Autowired
    private final BinanceDataSourceConfig config;

    @Autowired
    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private final BinanceMessageHandler messageHandler;

    @Autowired
    private final BinanceSnapshotFetcher snapshotFetcher;

    private static final String REDIS_DEPTH_PREFIX = "binance:depth:";
    private static final String REDIS_TRADE_PREFIX = "binance:trade:";
    private static final String REDIS_TICKER_PREFIX = "binance:ticker:";

    /**
     * 获取服务状态
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("connected", wsClient.isConnected());
        status.put("subscribedSymbols", config.getSymbols());
        status.put("uptimeSeconds", wsClient.getUptimeSeconds());
        status.put("messagesReceived", wsClient.getMessagesReceived());
        status.put("reconnectCount", wsClient.getReconnectCount());
        status.put("depthLevels", config.getDepthLevels());
        status.put("batchWindowMs", config.getBatchWindowMs());
        return status;
    }

    /**
     * 获取深度快照
     * 
     * @param symbol 交易对，如 BTCUSDT
     * @param limit 档位数（可选，默认全部）
     */
    @GetMapping("/depth/{symbol}")
    public Map<String, Object> getDepthSnapshot(
            @PathVariable String symbol,
            @RequestParam(required = false) Integer limit) {
        
        Map<String, Object> result = new HashMap<>();
        
        String redisKey = REDIS_DEPTH_PREFIX + symbol.toUpperCase();
        Object data = redisTemplate.opsForValue().get(redisKey);
        
        if (data == null) {
            result.put("success", false);
            result.put("message", "No depth data available for " + symbol);
            return result;
        }
        
        try {
            JSONObject depth = JSON.parseObject(data.toString());
            
            // 如果指定了limit，裁剪深度
            if (limit != null && limit > 0) {
                List<?> bids = depth.getList("b", Object.class);
                List<?> asks = depth.getList("a", Object.class);
                
                if (bids != null && bids.size() > limit) {
                    depth.put("b", bids.subList(0, limit));
                }
                if (asks != null && asks.size() > limit) {
                    depth.put("a", asks.subList(0, limit));
                }
            }
            
            result.put("success", true);
            result.put("data", depth);
            result.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Failed to parse depth data: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取最新成交
     * 
     * @param symbol 交易对
     * @param limit 数量（默认20，最大100）
     */
    @GetMapping("/trades/{symbol}")
    public Map<String, Object> getRecentTrades(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "20") int limit) {
        
        Map<String, Object> result = new HashMap<>();
        
        String redisKey = REDIS_TRADE_PREFIX + symbol.toUpperCase();
        limit = Math.min(limit, 100);
        
        List<Object> trades = redisTemplate.opsForList().range(redisKey, 0, limit - 1);
        
        result.put("success", true);
        result.put("symbol", symbol.toUpperCase());
        result.put("count", trades != null ? trades.size() : 0);
        result.put("data", trades);
        result.put("timestamp", System.currentTimeMillis());
        
        return result;
    }

    /**
     * 获取24小时统计（Ticker）
     * 
     * @param symbol 交易对
     */
    @GetMapping("/ticker/{symbol}")
    public Map<String, Object> getTicker(@PathVariable String symbol) {
        Map<String, Object> result = new HashMap<>();
        
        String redisKey = REDIS_TICKER_PREFIX + symbol.toUpperCase();
        Object data = redisTemplate.opsForValue().get(redisKey);
        
        if (data == null) {
            result.put("success", false);
            result.put("message", "No ticker data available for " + symbol);
            return result;
        }
        
        try {
            JSONObject ticker = JSON.parseObject(data.toString());
            result.put("success", true);
            result.put("data", ticker);
            result.put("timestamp", System.currentTimeMillis());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Failed to parse ticker data: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取所有配置的symbol
     */
    @GetMapping("/symbols")
    public Map<String, Object> getSymbols() {
        Map<String, Object> result = new HashMap<>();
        result.put("symbols", config.getSymbols());
        result.put("count", config.getSymbols().size());
        return result;
    }

    /**
     * 手动触发重连（管理接口）
     */
    @PostMapping("/reconnect")
    public Map<String, Object> reconnect() {
        Map<String, Object> result = new HashMap<>();

        try {
            wsClient.disconnect();
            Thread.sleep(500);
            wsClient.connect();

            result.put("success", true);
            result.put("message", "Reconnection triggered");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Reconnection failed: " + e.getMessage());
        }

        return result;
    }

    // ========== 新增：订单簿管理接口 ==========

    /**
     * 获取订单簿快照（从本地OrderBookManager）
     *
     * 与 /depth/{symbol} 的区别：
     * - /depth/{symbol}: 返回Redis缓存（可能过期）
     * - /orderbook/{symbol}: 返回本地订单簿（实时，经过序号校验）
     *
     * @param symbol 交易对
     * @param depth 档位数（默认20）
     */
    @GetMapping("/orderbook/{symbol}")
    public Map<String, Object> getOrderBook(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "20") int depth) {

        Map<String, Object> result = new HashMap<>();

        OrderBookManager.Snapshot snapshot = messageHandler.getOrderBookSnapshot(
                symbol.toUpperCase(),
                depth
        );

        if (snapshot == null) {
            result.put("success", false);
            result.put("message", "OrderBook not available for " + symbol);
            return result;
        }

        // 转换为可读格式
        Map<String, Object> data = new HashMap<>();
        data.put("symbol", snapshot.getSymbol());
        data.put("lastUpdateId", snapshot.getLastUpdateId());
        data.put("status", snapshot.getStatus().name());
        data.put("bids", convertLevelsToStrings(snapshot.getBids()));
        data.put("asks", convertLevelsToStrings(snapshot.getAsks()));

        result.put("success", true);
        result.put("data", data);
        result.put("timestamp", System.currentTimeMillis());

        return result;
    }

    /**
     * 获取BBO（最优买卖价）
     */
    @GetMapping("/orderbook/{symbol}/bbo")
    public Map<String, Object> getBBO(@PathVariable String symbol) {
        Map<String, Object> result = new HashMap<>();

        OrderBookManager.BBO bbo = messageHandler.getBBO(symbol.toUpperCase());

        if (bbo == null) {
            result.put("success", false);
            result.put("message", "BBO not available for " + symbol);
            return result;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("symbol", symbol.toUpperCase());
        data.put("bidPrice", bbo.getBidPrice());
        data.put("bidQty", bbo.getBidQty());
        data.put("askPrice", bbo.getAskPrice());
        data.put("askQty", bbo.getAskQty());
        data.put("spread", bbo.getSpread());

        result.put("success", true);
        result.put("data", data);
        result.put("timestamp", System.currentTimeMillis());

        return result;
    }

    /**
     * 获取所有订单簿状态
     */
    @GetMapping("/orderbook/status")
    public Map<String, Object> getAllOrderBookStatus() {
        Map<String, Object> result = new HashMap<>();

        Map<String, Map<String, Object>> orderBooks = messageHandler.getAllOrderBookManagers()
                .entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            OrderBookManager.OrderBookMetrics metrics = entry.getValue().getMetrics();
                            Map<String, Object> status = new HashMap<>();
                            status.put("status", metrics.getStatus().name());
                            status.put("lastUpdateId", metrics.getLastUpdateId());
                            status.put("bidLevels", metrics.getBidLevels());
                            status.put("askLevels", metrics.getAskLevels());
                            status.put("updateCount", metrics.getUpdateCount());
                            status.put("gapCount", metrics.getGapCount());
                            status.put("rebuildCount", metrics.getRebuildCount());
                            status.put("priceAnomalyCount", metrics.getPriceAnomalyCount());
                            status.put("staleUpdateCount", metrics.getStaleUpdateCount());
                            return status;
                        }
                ));

        result.put("orderBooks", orderBooks);
        result.put("count", orderBooks.size());
        result.put("timestamp", System.currentTimeMillis());

        return result;
    }

    /**
     * 获取单个订单簿指标
     */
    @GetMapping("/orderbook/{symbol}/metrics")
    public Map<String, Object> getOrderBookMetrics(@PathVariable String symbol) {
        Map<String, Object> result = new HashMap<>();

        OrderBookManager.OrderBookMetrics metrics = messageHandler.getOrderBookMetrics(
                symbol.toUpperCase()
        );

        if (metrics == null) {
            result.put("success", false);
            result.put("message", "OrderBook not found for " + symbol);
            return result;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("symbol", metrics.getSymbol());
        data.put("status", metrics.getStatus().name());
        data.put("lastUpdateId", metrics.getLastUpdateId());
        data.put("bidLevels", metrics.getBidLevels());
        data.put("askLevels", metrics.getAskLevels());
        data.put("updateCount", metrics.getUpdateCount());
        data.put("gapCount", metrics.getGapCount());
        data.put("rebuildCount", metrics.getRebuildCount());
        data.put("priceAnomalyCount", metrics.getPriceAnomalyCount());
        data.put("staleUpdateCount", metrics.getStaleUpdateCount());

        result.put("success", true);
        result.put("data", data);
        result.put("timestamp", System.currentTimeMillis());

        return result;
    }

    /**
     * 手动触发订单簿重建
     */
    @PostMapping("/orderbook/{symbol}/rebuild")
    public Map<String, Object> rebuildOrderBook(@PathVariable String symbol) {
        Map<String, Object> result = new HashMap<>();

        try {
            messageHandler.manualRebuild(symbol.toUpperCase());
            result.put("success", true);
            result.put("message", "OrderBook rebuild triggered for " + symbol);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Rebuild failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * 获取快照获取器指标
     */
    @GetMapping("/fetcher/metrics")
    public Map<String, Object> getFetcherMetrics() {
        Map<String, Object> result = new HashMap<>();

        BinanceSnapshotFetcher.FetcherMetrics metrics = snapshotFetcher.getMetrics();

        Map<String, Object> data = new HashMap<>();
        data.put("successCount", metrics.getSuccessCount());
        data.put("failCount", metrics.getFailCount());
        data.put("successRate", metrics.getSuccessRate());
        data.put("avgLatencyMs", metrics.getAvgLatencyMs());

        result.put("data", data);
        result.put("timestamp", System.currentTimeMillis());

        return result;
    }

    /**
     * 获取消息处理器指标
     */
    @GetMapping("/handler/metrics")
    public Map<String, Object> getHandlerMetrics() {
        Map<String, Object> result = new HashMap<>();

        BinanceMessageHandler.HandlerMetrics metrics = messageHandler.getMetrics();

        Map<String, Object> data = new HashMap<>();
        data.put("totalMessagesHandled", metrics.totalMessagesHandled());
        data.put("depthMessagesHandled", metrics.depthMessagesHandled());
        data.put("tradeMessagesHandled", metrics.tradeMessagesHandled());
        data.put("tickerMessagesHandled", metrics.tickerMessagesHandled());
        data.put("activeOrderBooks", metrics.activeOrderBooks());

        result.put("data", data);
        result.put("timestamp", System.currentTimeMillis());

        return result;
    }

    // ========== 工具方法 ==========

    /**
     * 转换价格档位为字符串格式（便于前端显示）
     */
    private List<List<String>> convertLevelsToStrings(List<long[]> levels) {
        return levels.stream()
                .map(level -> List.of(
                        formatPrice(level[0]),
                        formatPrice(level[1])
                ))
                .collect(Collectors.toList());
    }

    /**
     * 格式化价格（8位精度 long -> 字符串）
     */
    private String formatPrice(long price) {
        double value = price / 100_000_000.0;
        return String.format("%.8f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}

