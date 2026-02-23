package com.exchange.market.engine;

import com.exchange.market.entity.Ticker24h;
import com.exchange.market.publisher.MarketDataPublisher;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 24小时统计引擎（Ticker Engine）
 * 
 * 职责：
 * - 维护24小时成交统计
 * - 计算涨跌幅、高低价、成交量
 * - 滑动窗口实现
 * 
 * 性能目标：
 * - 更新延迟 < 1ms
 * - 内存占用固定（滑动窗口大小）
 * 
 * 对标：Binance Ticker API
 */
@Slf4j
public class TickerEngine {

    private final String symbol;
    private final MarketDataPublisher publisher;
    
    // 24小时窗口（毫秒）
    private static final long WINDOW_MS = TimeUnit.DAYS.toMillis(1);
    
    // 滑动窗口存储
    private final CircularTradeBuffer tradeBuffer;
    
    // 统计数据（原子操作）
    private final LongAdder volume24h;
    private final LongAdder quoteVolume24h;
    private final AtomicLong tradeCount;
    
    // 价格统计
    private volatile long openPrice;      // 24小时开盘价
    private volatile long highPrice;      // 24小时最高价
    private volatile long lowPrice;       // 24小时最低价
    private volatile long lastPrice;      // 最新成交价
    private volatile long weightedAvgPrice; // 加权平均价
    private volatile long lastQty;        // 最新成交量
    
    // 时间戳
    private volatile long openTime;       // 统计开始时间
    private volatile long closeTime;      // 统计结束时间
    private volatile long firstTradeId;   // 第一笔成交ID
    private volatile long lastTradeId;    // 最后一笔成交ID

    public TickerEngine(String symbol, MarketDataPublisher publisher) {
        this.symbol = symbol;
        this.publisher = publisher;
        this.tradeBuffer = new CircularTradeBuffer(100000); // 最近10万笔成交
        this.volume24h = new LongAdder();
        this.quoteVolume24h = new LongAdder();
        this.tradeCount = new AtomicLong(0);
        this.openTime = System.currentTimeMillis();
        this.closeTime = openTime + WINDOW_MS;
        this.highPrice = 0;
        this.lowPrice = Long.MAX_VALUE;
    }

    /**
     * 处理成交事件
     */
    public void onTrade(long price, long quantity, long timestamp, long tradeId) {
        // 更新最新成交
        this.lastPrice = price;
        this.lastQty = quantity;
        this.lastTradeId = tradeId;
        
        // 第一笔成交
        if (firstTradeId == 0) {
            this.firstTradeId = tradeId;
            this.openPrice = price;
        }
        
        // 添加到滑动窗口
        tradeBuffer.add(new TradeInfo(price, quantity, timestamp, tradeId));
        
        // 更新统计
        recalculateStats();
        
        // 推送ticker更新（转换为TradeStats24h格式）
        Ticker24h ticker = buildTicker();
        TradeEngine.TradeStats24h stats = convertToTradeStats24h(ticker);
        publisher.publishTicker(symbol, stats);
    }

    /**
     * 重新计算统计（滑动窗口）
     */
    private void recalculateStats() {
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MS;
        
        // 重置统计
        long totalVol = 0;
        long totalQuoteVol = 0;
        long newHigh = 0;
        long newLow = Long.MAX_VALUE;
        long count = 0;
        
        // 遍历滑动窗口
        for (TradeInfo trade : tradeBuffer.getAll()) {
            if (trade.timestamp >= windowStart) {
                totalVol += trade.quantity;
                totalQuoteVol += trade.price * trade.quantity;
                newHigh = Math.max(newHigh, trade.price);
                newLow = Math.min(newLow, trade.price);
                count++;
            }
        }
        
        // 更新统计值
        volume24h.reset();
        volume24h.add(totalVol);
        quoteVolume24h.reset();
        quoteVolume24h.add(totalQuoteVol);
        tradeCount.set(count);
        
        if (newHigh > 0) {
            highPrice = newHigh;
        }
        if (newLow < Long.MAX_VALUE) {
            lowPrice = newLow;
        }
        
        // 计算加权平均价
        if (totalVol > 0) {
            weightedAvgPrice = totalQuoteVol / totalVol;
        }
        
        // 更新时间窗口
        closeTime = now;
    }

    /**
     * 构建Ticker对象
     */
    public Ticker24h buildTicker() {
        Ticker24h ticker = new Ticker24h();
        ticker.setSymbol(symbol);
        long change = lastPrice - openPrice;
        ticker.setPriceChange(change);
        // priceChangePercent存储为百分比*10000（例如0.45%存储为4500）
        ticker.setPriceChangePercent(openPrice > 0 ? (change * 10000L / openPrice) : 0L);
        ticker.setWeightedAvgPrice(weightedAvgPrice);
        ticker.setLastPrice(lastPrice);
        ticker.setLastQty(lastQty);
        ticker.setOpenPrice(openPrice);
        ticker.setHighPrice(highPrice);
        ticker.setLowPrice(lowPrice == Long.MAX_VALUE ? 0 : lowPrice);
        ticker.setVolume(volume24h.sum());
        ticker.setQuoteVolume(quoteVolume24h.sum());
        ticker.setOpenTime(openTime);
        ticker.setCloseTime(closeTime);
        ticker.setFirstId(firstTradeId);
        ticker.setLastId(lastTradeId);
        ticker.setCount((int) tradeCount.get());
        ticker.setTimestamp(System.currentTimeMillis());
        
        return ticker;
    }

    /**
     * 重置24小时统计
     */
    public void reset() {
        tradeBuffer.clear();
        volume24h.reset();
        quoteVolume24h.reset();
        tradeCount.set(0);
        
        long now = System.currentTimeMillis();
        openTime = now;
        closeTime = now + WINDOW_MS;
        openPrice = lastPrice;
        highPrice = lastPrice;
        lowPrice = lastPrice;
        firstTradeId = lastTradeId;
        
        log.info("[TickerEngine] {} 24h stats reset", symbol);
    }

    /**
     * 转换Ticker24h到TradeStats24h（用于发布）
     */
    private TradeEngine.TradeStats24h convertToTradeStats24h(Ticker24h ticker) {
        TradeEngine.TradeStats24h stats = new TradeEngine.TradeStats24h();
        stats.setSymbol(ticker.getSymbol());
        stats.setLastPrice(ticker.getLastPrice());
        stats.setLastQty(ticker.getLastQty());
        stats.setPriceChange(ticker.getPriceChange());
        stats.setPriceChangePercent(ticker.getPriceChangePercent() / 10000.0); // 转换回小数
        stats.setWeightedAvgPrice(ticker.getWeightedAvgPrice());
        stats.setOpenPrice(ticker.getOpenPrice());
        stats.setHighPrice(ticker.getHighPrice());
        stats.setLowPrice(ticker.getLowPrice());
        stats.setVolume(ticker.getVolume());
        stats.setQuoteVolume(ticker.getQuoteVolume());
        stats.setOpenTime(ticker.getOpenTime());
        stats.setCloseTime(ticker.getCloseTime());
        stats.setFirstId(ticker.getFirstId());
        stats.setLastId(ticker.getLastId());
        stats.setCount(ticker.getCount());
        return stats;
    }

    /**
     * 成交信息内部类
     */
    private static class TradeInfo {
        final long price;
        final long quantity;
        final long timestamp;
        final long tradeId;
        
        TradeInfo(long price, long quantity, long timestamp, long tradeId) {
            this.price = price;
            this.quantity = quantity;
            this.timestamp = timestamp;
            this.tradeId = tradeId;
        }
    }

    /**
     * 环形缓冲区（固定大小）
     */
    private static class CircularTradeBuffer {
        private final TradeInfo[] buffer;
        private final int capacity;
        private volatile int head = 0;
        private volatile int size = 0;
        
        CircularTradeBuffer(int capacity) {
            this.capacity = capacity;
            this.buffer = new TradeInfo[capacity];
        }
        
        synchronized void add(TradeInfo trade) {
            buffer[head] = trade;
            head = (head + 1) % capacity;
            if (size < capacity) {
                size++;
            }
        }
        
        synchronized TradeInfo[] getAll() {
            TradeInfo[] result = new TradeInfo[size];
            for (int i = 0; i < size; i++) {
                int index = (head - size + i + capacity) % capacity;
                result[i] = buffer[index];
            }
            return result;
        }
        
        synchronized void clear() {
            head = 0;
            size = 0;
        }
    }
}
