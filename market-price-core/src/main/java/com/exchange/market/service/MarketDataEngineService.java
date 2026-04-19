package com.exchange.market.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.market.cache.MarketDataCache;
import com.exchange.market.client.MatchEngineClient;
import com.exchange.market.engine.KlineEngine;
import com.exchange.market.engine.KlineEngineWithStorage;
import com.exchange.market.engine.OrderBook;
import com.exchange.market.engine.TickerEngine;
import com.exchange.market.engine.TradeEngine;
import com.exchange.market.model.Trade;
import com.exchange.market.publisher.MarketDataPublisher;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 行情数据引擎服务（Market Data Engine Service）
 * 
 * 职责：
 * - 管理所有symbol的行情引擎
 * - 协调Trade/OrderBook/Kline/Ticker引擎
 * - 定时推送统计更新
 * 
 * 架构：
 * - 每个symbol独立一组引擎
 * - 按需创建，延迟初始化
 * - 支持动态添加symbol
 */
@Slf4j
@Service
public class MarketDataEngineService {

    private final MarketDataPublisher publisher;
    private final MarketDataCache cache;
    private final KlineEngineWithStorage klineEngineWithStorage;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MatchEngineClient matchEngineClient;
    
    // Symbol -> 引擎组映射
    private final Map<String, SymbolEngines> symbolEnginesMap;
    
    // 深度序号不连续计数器（健康检查）
    private final Map<String, AtomicInteger> depthGapCounter;
    
    // 默认价格精度
    private static final int DEFAULT_PRICE_PRECISION = 8;
    private static final int DEFAULT_QTY_PRECISION = 8;
    
    // 深度健康检查阈值：连续 N 次序号不连续则触发主动重建
    private static final int MAX_CONSECUTIVE_GAPS = 3;
    
    // Redis 深度快照 Key 前缀
    private static final String SNAPSHOT_DEPTH_KEY = "market:snapshot:depth:";
    
    // 价格精度缩放系数（8位小数）
    private static final long PRICE_SCALE = 100_000_000L;
    
    // 定时任务执行器
    private ScheduledExecutorService scheduler;

    public MarketDataEngineService(MarketDataPublisher publisher, MarketDataCache cache,
                                   KlineEngineWithStorage klineEngineWithStorage,
                                   RedisTemplate<String, Object> redisTemplate,
                                   MatchEngineClient matchEngineClient) {
        this.publisher = publisher;
        this.cache = cache;
        this.klineEngineWithStorage = klineEngineWithStorage;
        this.redisTemplate = redisTemplate;
        this.matchEngineClient = matchEngineClient;
        this.symbolEnginesMap = new ConcurrentHashMap<>();
        this.depthGapCounter = new ConcurrentHashMap<>();
    }

    @PostConstruct
    public void init() {
        // 启动定时任务
        scheduler = Executors.newScheduledThreadPool(2);
        
        // 每秒推送Ticker更新
        scheduler.scheduleAtFixedRate(this::publishAllTickers, 1, 1, TimeUnit.SECONDS);
        
        // 每100ms检查聚合窗口
        scheduler.scheduleAtFixedRate(this::flushAggTrades, 100, 100, TimeUnit.MILLISECONDS);
        
        log.info("[MarketDataEngineService] Initialized");
    }

    /**
     * 获取或创建Symbol引擎组
     */
    private SymbolEngines getOrCreateEngines(String symbol) {
        return symbolEnginesMap.computeIfAbsent(symbol, s -> {
            log.info("[EngineService] Creating engines for symbol: {}", s);
            return new SymbolEngines(s, publisher);
        });
    }

    /**
     * 处理成交事件
     */
    public void onTrade(String symbol, Trade trade) {
        SymbolEngines engines = getOrCreateEngines(symbol);
        
        // 更新Trade引擎
        engines.getTradeEngine().onTrade(trade);
        
        // 使用 KlineEngineWithStorage 替代 KlineEngine，确保撮合K线持久化到 ClickHouse
        klineEngineWithStorage.onTrade(symbol, trade.getPrice(), trade.getQuantity(), 
                trade.getTimestamp(), trade.isBuyerMaker(), trade.getSequence());
        
        // 启用 TickerEngine 进行正确的24h滑动窗口统计
        engines.getTickerEngine().onTrade(trade.getPrice(), trade.getQuantity(), 
                trade.getTimestamp(), trade.getTradeId(), trade.getSequence());
    }

    /**
     * 处理深度增量更新
     * 
     * 重启恢复策略：
     * - 首次接收增量时若订单簿为空，先尝试从 Redis 加载快照
     * - 序号不连续时增加健康检查计数，超过阈值则主动请求 Match Engine 快照重建
     */
    public void onDepthDelta(String symbol, List<long[]> bids, List<long[]> asks, 
                             long sequence, long timestamp) {
        SymbolEngines engines = getOrCreateEngines(symbol);
        OrderBook orderBook = engines.getOrderBook();
        
        // 应用增量
        boolean success = orderBook.applyDelta(bids, asks, sequence, timestamp);
        
        if (!success) {
            // 序号不连续，增加健康检查计数
            AtomicInteger counter = depthGapCounter.computeIfAbsent(symbol, s -> new AtomicInteger(0));
            int gaps = counter.incrementAndGet();
            log.warn("[EngineService] {} OrderBook sequence gap detected, consecutiveGaps={}", symbol, gaps);
            
            // 超过阈值：触发主动重建
            if (gaps >= MAX_CONSECUTIVE_GAPS) {
                log.error("[EngineService] {} OrderBook unhealthy ({} consecutive gaps), triggering snapshot rebuild", 
                        symbol, gaps);
                tryRebuildOrderBook(symbol);
                counter.set(0);
            }
            return;
        }
        
        // 成功应用增量，清零健康计数
        depthGapCounter.put(symbol, new AtomicInteger(0));
        
        // 发布深度增量到 Kafka（供下游按增量语义消费）
        MarketDataPublisher.DepthUpdate deltaUpdate = new MarketDataPublisher.DepthUpdate();
        deltaUpdate.setSymbol(symbol);
        deltaUpdate.setFirstUpdateId(sequence);
        deltaUpdate.setLastUpdateId(sequence);
        deltaUpdate.setBids(bids != null ? bids : new ArrayList<>());
        deltaUpdate.setAsks(asks != null ? asks : new ArrayList<>());
        deltaUpdate.setTimestamp(timestamp);
        deltaUpdate.setSnapshot(false);
        
        publisher.publishDepthDelta(symbol, deltaUpdate);
        
        // 更新 Redis 完整快照（供查询和新订阅者获取初始状态）
        OrderBook.DepthSnapshot snapshot = orderBook.getSnapshot(100);
        MarketDataPublisher.DepthUpdate snapshotUpdate = new MarketDataPublisher.DepthUpdate();
        snapshotUpdate.setSymbol(symbol);
        snapshotUpdate.setLastUpdateId(sequence);
        snapshotUpdate.setBids(snapshot.getBids());
        snapshotUpdate.setAsks(snapshot.getAsks());
        snapshotUpdate.setTimestamp(timestamp);
        snapshotUpdate.setSnapshot(true);
        
        publisher.updateDepthSnapshot(symbol, snapshotUpdate);
    }

    /**
     * 重建订单簿（快照恢复）
     */
    public void rebuildOrderBook(String symbol, List<long[]> bids, List<long[]> asks,
                                  long lastSequence, long timestamp) {
        SymbolEngines engines = getOrCreateEngines(symbol);
        OrderBook orderBook = engines.getOrderBook();
        
        orderBook.rebuild(bids, asks, lastSequence, timestamp);
        
        log.info("[EngineService] {} OrderBook rebuilt with lastSequence={}", symbol, lastSequence);
        
        // 发布快照
        OrderBook.DepthSnapshot snapshot = orderBook.getSnapshot(100);
        MarketDataPublisher.DepthUpdate update = new MarketDataPublisher.DepthUpdate();
        update.setSymbol(symbol);
        update.setFirstUpdateId(lastSequence);
        update.setLastUpdateId(lastSequence);
        update.setBids(snapshot.getBids());
        update.setAsks(snapshot.getAsks());
        update.setTimestamp(timestamp);
        update.setSnapshot(true);
        
        publisher.publishDepth(symbol, update);
    }

    /**
     * 尝试重建订单簿（重启恢复/健康检查触发）
     * 优先级：1. Redis 快照 → 2. Match Engine HTTP 接口
     */
    private void tryRebuildOrderBook(String symbol) {
        // 1. 优先从 Redis 加载快照
        List<long[]> redisBids = null;
        List<long[]> redisAsks = null;
        Long redisLastSeq = null;
        try {
            Object value = redisTemplate.opsForValue().get(SNAPSHOT_DEPTH_KEY + symbol);
            if (value != null) {
                JSONObject json = value instanceof String 
                        ? JSON.parseObject((String) value) 
                        : JSON.parseObject(JSON.toJSONString(value));
                redisBids = parseDepthLevelsFromJson(json.getJSONArray("b"));
                redisAsks = parseDepthLevelsFromJson(json.getJSONArray("a"));
                redisLastSeq = json.getLongValue("u");
                log.info("[EngineService] {} Loaded depth snapshot from Redis, bids={}, asks={}, lastSeq={}",
                        symbol, redisBids.size(), redisAsks.size(), redisLastSeq);
            }
        } catch (Exception e) {
            log.warn("[EngineService] {} Failed to load depth snapshot from Redis: {}", symbol, e.getMessage());
        }

        if (redisBids != null && redisAsks != null) {
            rebuildOrderBook(symbol, redisBids, redisAsks, 
                    redisLastSeq != null ? redisLastSeq : 0, System.currentTimeMillis());
            return;
        }

        // 2. Redis 无快照，主动向 Match Engine 请求
        try {
            log.info("[EngineService] {} Redis snapshot not available, requesting from Match Engine...", symbol);
            Map<String, Object> response = matchEngineClient.getOrderBookDepth(symbol, 100);
            if (response == null) {
                log.warn("[EngineService] {} Match Engine returned null snapshot", symbol);
                return;
            }

            List<long[]> matchBids = parseDepthLevelsFromMatchEngine(response.get("bids"));
            List<long[]> matchAsks = parseDepthLevelsFromMatchEngine(response.get("asks"));
            Long matchSeq = response.get("timestamp") instanceof Number 
                    ? ((Number) response.get("timestamp")).longValue() 
                    : System.currentTimeMillis();

            if (matchBids != null && matchAsks != null) {
                rebuildOrderBook(symbol, matchBids, matchAsks, matchSeq, System.currentTimeMillis());
                log.info("[EngineService] {} Rebuilt OrderBook from Match Engine snapshot, bids={}, asks={}",
                        symbol, matchBids.size(), matchAsks.size());
            } else {
                log.warn("[EngineService] {} Match Engine snapshot missing bids/asks", symbol);
            }
        } catch (Exception e) {
            log.error("[EngineService] {} Failed to request snapshot from Match Engine: {}", symbol, e.getMessage());
        }
    }

    /**
     * 从 Redis JSON 解析深度档位 [[priceStr, qtyStr], ...] -> List<long[]>
     */
    @SuppressWarnings("unchecked")
    private List<long[]> parseDepthLevelsFromJson(JSONArray jsonArray) {
        if (jsonArray == null || jsonArray.isEmpty()) {
            return new ArrayList<>();
        }
        List<long[]> result = new ArrayList<>(jsonArray.size());
        for (int i = 0; i < jsonArray.size(); i++) {
            JSONArray pair = jsonArray.getJSONArray(i);
            if (pair == null || pair.size() < 2) {
                continue;
            }
            String priceStr = pair.getString(0);
            String qtyStr = pair.getString(1);
            long price = parseScaledString(priceStr);
            long qty = parseScaledString(qtyStr);
            result.add(new long[]{price, qty});
        }
        return result;
    }

    /**
     * 从 Match Engine 响应解析深度档位
     */
    @SuppressWarnings("unchecked")
    private List<long[]> parseDepthLevelsFromMatchEngine(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            List<List<Object>> list = (List<List<Object>>) raw;
            List<long[]> result = new ArrayList<>(list.size());
            for (List<Object> pair : list) {
                if (pair == null || pair.size() < 2) {
                    continue;
                }
                String priceStr = pair.get(0).toString();
                String qtyStr = pair.get(1).toString();
                long price = parseScaledString(priceStr);
                long qty = parseScaledString(qtyStr);
                result.add(new long[]{price, qty});
            }
            return result;
        } catch (Exception e) {
            log.warn("[EngineService] Failed to parse depth levels from Match Engine: {}", e.getMessage());
            return null;
        }
    }

    private long parseScaledString(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return new BigDecimal(value.trim())
                    .multiply(BigDecimal.valueOf(PRICE_SCALE))
                    .setScale(0, RoundingMode.DOWN)
                    .longValue();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 获取订单簿
     */
    public OrderBook getOrderBook(String symbol) {
        SymbolEngines engines = symbolEnginesMap.get(symbol);
        if (engines == null) {
            return null;
        }
        return engines.getOrderBook();
    }

    /**
     * 获取或创建订单簿
     */
    public OrderBook getOrCreateOrderBook(String symbol) {
        return getOrCreateEngines(symbol).getOrderBook();
    }

    /**
     * 获取Trade引擎
     */
    public TradeEngine getTradeEngine(String symbol) {
        SymbolEngines engines = symbolEnginesMap.get(symbol);
        if (engines == null) {
            return null;
        }
        return engines.getTradeEngine();
    }

    /**
     * 获取Kline引擎
     */
    public KlineEngine getKlineEngine(String symbol) {
        SymbolEngines engines = symbolEnginesMap.get(symbol);
        if (engines == null) {
            return null;
        }
        return engines.getKlineEngine();
    }

    /**
     * 推送所有symbol的Ticker
     * 
     * 重启恢复策略：
     * - 优先从 cache 获取（Ticker24hFallbackRefreshJob 可能已从 K 线/外部恢复了24h统计）
     * - cache 为空时 fallback 到 TickerEngine 实时滑动窗口
     */
    private void publishAllTickers() {
        symbolEnginesMap.forEach((symbol, engines) -> {
            try {
                // 优先从 cache 获取（重启后可能由 fallback job 恢复）
                TradeEngine.TradeStats24h stats = cache.getTicker(symbol);
                if (stats == null || isEmptyTicker(stats)) {
                    // fallback 到 TickerEngine 实时滑动窗口
                    stats = engines.getTickerEngine().getTradeStats24h();
                }
                if (stats != null && !isEmptyTicker(stats)) {
                    publisher.publishTicker(symbol, stats);
                }
            } catch (Exception e) {
                log.error("[EngineService] Failed to publish ticker for {}: {}", symbol, e.getMessage());
            }
        });
    }

    /**
     * 判断 Ticker 是否为空（重启后滑动窗口未积累足够数据）
     */
    private boolean isEmptyTicker(TradeEngine.TradeStats24h stats) {
        if (stats == null) {
            return true;
        }
        return stats.getVolume() == 0 && stats.getCount() == 0
                && stats.getHighPrice() == 0 && stats.getLowPrice() == 0
                && stats.getOpenPrice() == 0;
    }

    /**
     * 刷新聚合成交
     */
    private void flushAggTrades() {
        // 聚合成交在TradeEngine内部处理，这里可以添加额外的刷新逻辑
    }

    /**
     * 获取支持的symbol列表
     */
    public List<String> getSymbols() {
        return List.copyOf(symbolEnginesMap.keySet());
    }

    /**
     * 获取引擎统计信息
     */
    public String getEngineStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Market Data Engine Stats ===\n");
        sb.append("Symbols: ").append(symbolEnginesMap.size()).append("\n");
        
        symbolEnginesMap.forEach((symbol, engines) -> {
            sb.append("  ").append(symbol).append(": ");
            sb.append("bids=").append(engines.getOrderBook().getBidLevelCount());
            sb.append(", asks=").append(engines.getOrderBook().getAskLevelCount());
            sb.append("\n");
        });
        
        return sb.toString();
    }

    /**
     * 单个Symbol的引擎组
     */
    @Getter
    private class SymbolEngines {
        private final String symbol;
        private final OrderBook orderBook;
        private final TradeEngine tradeEngine;
        private final KlineEngine klineEngine;
        private final TickerEngine tickerEngine;

        SymbolEngines(String symbol, MarketDataPublisher publisher) {
            this.symbol = symbol;
            this.orderBook = new OrderBook(symbol, DEFAULT_PRICE_PRECISION, DEFAULT_QTY_PRECISION);
            this.tradeEngine = new TradeEngine(symbol, publisher);
            this.klineEngine = new KlineEngine(symbol, publisher);
            this.tickerEngine = new TickerEngine(symbol, publisher);
        }
    }
    
}
