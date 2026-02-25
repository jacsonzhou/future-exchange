package com.exchange.market.service;

import com.exchange.market.cache.MarketDataCache;
import com.exchange.market.engine.KlineEngineWithStorage;
import com.exchange.market.engine.OrderBook;
import com.exchange.market.engine.TradeEngine;
import com.exchange.market.model.Trade;
import com.exchange.market.publisher.MarketDataPublisher;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    
    // Symbol -> 引擎组映射
    private final Map<String, SymbolEngines> symbolEnginesMap;
    
    // 默认价格精度
    private static final int DEFAULT_PRICE_PRECISION = 8;
    private static final int DEFAULT_QTY_PRECISION = 8;
    
    // 定时任务执行器
    private ScheduledExecutorService scheduler;

    public MarketDataEngineService(MarketDataPublisher publisher,
                                   MarketDataCache cache,
                                   KlineEngineWithStorage klineEngineWithStorage) {
        this.publisher = publisher;
        this.cache = cache;
        this.klineEngineWithStorage = klineEngineWithStorage;
        this.symbolEnginesMap = new ConcurrentHashMap<>();
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
        
        // 更新带存储的Kline引擎（实时推送 + ClickHouse持久化）
        klineEngineWithStorage.onTrade(
            symbol,
            trade.getPrice(), 
            trade.getQuantity(), 
            trade.getTimestamp(), 
            trade.isBuyerMaker()
        );
    }

    /**
     * 处理深度增量更新
     */
    public void onDepthDelta(String symbol, List<long[]> bids, List<long[]> asks, 
                             long sequence, long timestamp) {
        SymbolEngines engines = getOrCreateEngines(symbol);
        OrderBook orderBook = engines.getOrderBook();
        
        // 应用增量
        boolean success = orderBook.applyDelta(bids, asks, sequence, timestamp);
        
        if (!success) {
            // 序号不连续，需要重建
            log.warn("[EngineService] {} OrderBook sequence gap, requesting rebuild", symbol);
            // 这里可以触发重建逻辑，比如从Redis获取快照
        } else {
            // 发布深度更新
            OrderBook.DepthSnapshot snapshot = orderBook.getSnapshot(20); // 推送20档
            
            // 使用 snapshot 的数据（已经是 List<long[]> 格式）
            List<long[]> finalBids = snapshot.getBids() != null ? snapshot.getBids() : (bids != null ? bids : new ArrayList<>());
            List<long[]> finalAsks = snapshot.getAsks() != null ? snapshot.getAsks() : (asks != null ? asks : new ArrayList<>());
            
            MarketDataPublisher.DepthUpdate update = new MarketDataPublisher.DepthUpdate();
            update.setSymbol(symbol);
            update.setFirstUpdateId(sequence);
            update.setLastUpdateId(sequence);
            update.setBids(finalBids);
            update.setAsks(finalAsks);
            update.setTimestamp(timestamp);
            update.setSnapshot(false);
            
            log.debug("[EngineService] Publish depth update, symbol={}, bids={}, asks={}", 
                symbol, finalBids.size(), finalAsks.size());
            
            publisher.publishDepth(symbol, update);
        }
    }

    /**
     * 重建订单簿（快照恢复）
     */
    public void rebuildOrderBook(String symbol, List<long[]> bids, List<long[]> asks,
                                  long lastSequence, long timestamp) {
        SymbolEngines engines = getOrCreateEngines(symbol);
        OrderBook orderBook = engines.getOrderBook();

        // Match Engine 会周期性发送同序列号心跳快照（U=u=lastSeq）。
        // 对已处理过的序列直接跳过，避免重复重建和重复推送导致前端“波动”。
        long currentSequence = orderBook.getLastUpdateId();
        if (currentSequence > 0 && lastSequence <= currentSequence) {
            log.debug("[EngineService] {} Skip stale depth snapshot, incomingSeq={}, currentSeq={}",
                symbol, lastSequence, currentSequence);
            return;
        }
        
        orderBook.rebuild(bids, asks, lastSequence, timestamp);

        // 若由于并发或内部保护导致未应用该快照，则不再重复发布。
        if (orderBook.getLastUpdateId() != lastSequence) {
            log.debug("[EngineService] {} Snapshot not applied, skip publish. incomingSeq={}, actualSeq={}",
                symbol, lastSequence, orderBook.getLastUpdateId());
            return;
        }
        
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
     * 推送所有symbol的Ticker
     */
    private void publishAllTickers() {
        symbolEnginesMap.forEach((symbol, engines) -> {
            try {
                TradeEngine.TradeStats24h stats = engines.getTradeEngine().getTradeStats24h();
                publisher.publishTicker(symbol, stats);
            } catch (Exception e) {
                log.error("[EngineService] Failed to publish ticker for {}: {}", symbol, e.getMessage());
            }
        });
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

        SymbolEngines(String symbol, MarketDataPublisher publisher) {
            this.symbol = symbol;
            this.orderBook = new OrderBook(symbol, DEFAULT_PRICE_PRECISION, DEFAULT_QTY_PRECISION);
            this.tradeEngine = new TradeEngine(symbol, publisher);
        }
    }
    
}
