package com.exchange.market.engine;

import com.exchange.market.model.Trade;
import com.exchange.market.publisher.MarketDataPublisher;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 成交处理器（Trade Engine）
 * 
 * 职责：
 * - 处理实时成交事件
 * - 维护最新成交价
 * - 成交流聚合（100ms窗口）
 * - 触发K线和Ticker更新
 * 
 * 性能目标：
 * - 处理延迟 < 1ms
 * - 支持 200k trades/s
 * 
 * 对标：Binance Trade Stream
 */
@Slf4j
public class TradeEngine {

    private final String symbol;
    private final MarketDataPublisher publisher;
    
    // 最新成交信息
    private volatile long lastPrice;
    private volatile long lastQty;
    private volatile long lastTradeTime;
    private volatile long lastTradeId;
    
    // 统计计数器（高性能原子操作）
    private final LongAdder totalVolume;
    private final LongAdder totalQuoteVolume;
    private final LongAdder tradeCount;
    
    // 24小时统计（滑动窗口）
    private final ArrayBlockingQueue<Trade> recentTrades;
    private static final int MAX_RECENT_TRADES = 10000;
    
    // 聚合窗口（100ms）
    private final Object aggLock = new Object();
    private volatile List<Trade> currentAggBatch;
    private volatile long aggWindowStart;
    private static final long AGG_WINDOW_MS = 100;
    
    // 价格统计
    private volatile long high24h;
    private volatile long low24h;
    private volatile long open24h;
    private volatile long weightedAvgPrice;

    public TradeEngine(String symbol, MarketDataPublisher publisher) {
        this.symbol = symbol;
        this.publisher = publisher;
        this.totalVolume = new LongAdder();
        this.totalQuoteVolume = new LongAdder();
        this.tradeCount = new LongAdder();
        this.recentTrades = new ArrayBlockingQueue<>(MAX_RECENT_TRADES);
        this.currentAggBatch = new ArrayList<>();
        this.aggWindowStart = System.currentTimeMillis();
        this.high24h = 0;
        this.low24h = Long.MAX_VALUE;
    }

    /**
     * 处理成交事件
     * 
     * @param trade 成交信息
     */
    public void onTrade(Trade trade) {
        long now = System.currentTimeMillis();
        
        // 更新最新成交
        this.lastPrice = trade.getPrice();
        this.lastQty = trade.getQuantity();
        this.lastTradeTime = trade.getTimestamp();
        this.lastTradeId = trade.getTradeId();
        
        // 更新统计
        totalVolume.add(trade.getQuantity());
        totalQuoteVolume.add(trade.getPrice() * trade.getQuantity());
        tradeCount.increment();
        
        // 更新24h高低价
        updatePriceStats(trade.getPrice());
        
        // 添加到滑动窗口
        addToRecentTrades(trade);
        
        // 实时推送（每条都推）
        publisher.publishTrade(symbol, trade);
        
        // 聚合处理
        aggregateTrade(trade, now);
    }

    /**
     * 更新价格统计
     */
    private void updatePriceStats(long price) {
        // 使用CAS循环确保原子性
        long currentHigh;
        do {
            currentHigh = high24h;
            if (price <= currentHigh) break;
        } while (!compareAndSetHigh(currentHigh, price));
        
        long currentLow;
        do {
            currentLow = low24h;
            if (price >= currentLow) break;
        } while (!compareAndSetLow(currentLow, price));
    }
    
    private boolean compareAndSetHigh(long expect, long update) {
        // 简化的CAS实现，实际使用VarHandle或AtomicLongFieldUpdater
        if (high24h == expect) {
            high24h = update;
            return true;
        }
        return false;
    }
    
    private boolean compareAndSetLow(long expect, long update) {
        if (low24h == expect) {
            low24h = update;
            return true;
        }
        return false;
    }

    /**
     * 添加到最近成交队列（滑动窗口）
     */
    private void addToRecentTrades(Trade trade) {
        if (!recentTrades.offer(trade)) {
            // 队列满，移除最老的
            recentTrades.poll();
            recentTrades.offer(trade);
        }
    }

    /**
     * 聚合成交（100ms窗口）
     */
    private void aggregateTrade(Trade trade, long now) {
        synchronized (aggLock) {
            // 检查窗口是否过期
            if (now - aggWindowStart >= AGG_WINDOW_MS) {
                // 发送上一批聚合
                if (!currentAggBatch.isEmpty()) {
                    publisher.publishAggTrade(symbol, currentAggBatch, aggWindowStart);
                }
                // 新开窗口
                currentAggBatch = new ArrayList<>();
                aggWindowStart = now;
            }
            // 添加到当前窗口
            currentAggBatch.add(trade);
        }
    }

    /**
     * 获取最新成交信息
     */
    public LastTradeInfo getLastTradeInfo() {
        return new LastTradeInfo(lastPrice, lastQty, lastTradeTime, lastTradeId);
    }

    /**
     * 获取24小时统计
     */
    public TradeStats24h getTradeStats24h() {
        TradeStats24h stats = new TradeStats24h();
        stats.setSymbol(symbol);
        stats.setLastPrice(lastPrice);
        stats.setLastQty(lastQty);
        stats.setHigh24h(high24h);
        stats.setLow24h(low24h == Long.MAX_VALUE ? 0 : low24h);
        stats.setOpen24h(open24h);
        stats.setVolume24h(totalVolume.sum());
        stats.setQuoteVolume24h(totalQuoteVolume.sum());
        stats.setTradeCount((int) tradeCount.sum());
        
        // 计算加权平均价
        long vol = totalVolume.sum();
        stats.setWeightedAvgPrice(vol > 0 ? totalQuoteVolume.sum() / vol : 0);
        
        // 计算涨跌幅
        if (open24h > 0) {
            long change = lastPrice - open24h;
            stats.setPriceChange(change);
            stats.setPriceChangePercent((double) change / open24h);
        }
        
        return stats;
    }

    /**
     * 重置24小时统计（每日凌晨）
     */
    public void resetDailyStats() {
        open24h = lastPrice;
        high24h = lastPrice;
        low24h = lastPrice;
        totalVolume.reset();
        totalQuoteVolume.reset();
        tradeCount.reset();
        recentTrades.clear();
        
        log.info("[TradeEngine] {} daily stats reset, openPrice={}", symbol, open24h);
    }

    /**
     * 获取最近成交列表
     */
    public List<Trade> getRecentTrades(int limit) {
        List<Trade> trades = new ArrayList<>(recentTrades);
        int size = trades.size();
        if (size <= limit) {
            return trades;
        }
        return trades.subList(size - limit, size);
    }

    /**
     * 最新成交信息
     */
    @Data
    public static class LastTradeInfo {
        private final long price;
        private final long quantity;
        private final long timestamp;
        private final long tradeId;
    }

    /**
     * 24小时统计
     */
    @Data
    public static class TradeStats24h {
        private String symbol;
        private long lastPrice;
        private long lastQty;
        private long priceChange;
        private double priceChangePercent;
        private long weightedAvgPrice;
        private long openPrice;
        private long highPrice;
        private long lowPrice;
        private long volume;
        private long quoteVolume;
        private long openTime;
        private long closeTime;
        private long firstId;
        private long lastId;
        private int count;
        
        // BBO字段
        private long bidPrice;
        private long bidQuantity;
        private long askPrice;
        private long askQuantity;
        
        // 兼容字段
        private long high24h;
        private long low24h;
        private long open24h;
        private long volume24h;
        private long quoteVolume24h;
        private int tradeCount;
        
        // 兼容方法
        public long getPrevClosePrice() { return openPrice; }
        public long getLastQuantity() { return lastQty; }
        public long getFirstTradeId() { return firstId; }
        public long getLastTradeId() { return lastId; }
        public int getTradeCount() { return count; }
    }
}
