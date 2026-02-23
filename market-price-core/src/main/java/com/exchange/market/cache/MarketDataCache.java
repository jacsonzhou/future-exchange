package com.exchange.market.cache;

import com.exchange.market.model.Kline;
import com.exchange.market.model.Trade;
import com.exchange.market.publisher.MarketDataPublisher.AggTrade;
import com.exchange.market.publisher.MarketDataPublisher.DepthUpdate;
import com.exchange.market.publisher.MarketDataPublisher.MarkPrice;
import com.exchange.market.engine.TradeEngine.TradeStats24h;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 行情数据本地缓存（Market Data Cache）
 * 
 * 职责：
 * - 热点数据本地缓存（Caffeine）
 * - 减轻Redis压力
 * - 提供快速查询能力
 * 
 * 缓存策略：
 * - 最新成交：30秒过期
 * - K线数据：实时更新，永不过期
 * - 深度数据：1秒过期（快速变化）
 * - Ticker数据：5秒过期
 * 
 * 对标：Binance/OKX 行情缓存层
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketDataCache {

    private final RedisTemplate<String, Object> redisTemplate;

    // 本地缓存
    private Cache<String, Trade> lastTradeCache;
    private Cache<String, AggTrade> aggTradeCache;
    private Cache<String, Kline> klineCache;
    private Cache<String, TradeStats24h> tickerCache;
    private Cache<String, DepthUpdate> depthCache;
    private Cache<String, MarkPrice> markPriceCache;

    @PostConstruct
    public void init() {
        // 最新成交缓存（30秒）
        lastTradeCache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .recordStats()
                .build();

        // 聚合成交缓存（30秒）
        aggTradeCache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .build();

        // K线缓存（1小时，实时更新）
        klineCache = Caffeine.newBuilder()
                .maximumSize(50000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .recordStats()
                .build();

        // Ticker缓存（5秒）
        tickerCache = Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(5, TimeUnit.SECONDS)
                .recordStats()
                .build();

        // 深度缓存（1秒，快速变化）
        depthCache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(1, TimeUnit.SECONDS)
                .recordStats()
                .build();

        // 标记价格缓存（10秒）
        markPriceCache = Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(10, TimeUnit.SECONDS)
                .recordStats()
                .build();

        log.info("[MarketDataCache] Initialized");
    }

    // ========== 更新方法 ==========

    public void updateLastTrade(String symbol, Trade trade) {
        lastTradeCache.put("trade:" + symbol, trade);
        
        // 同时更新Redis
        redisTemplate.opsForValue().set("market:trade:" + symbol + ":last", trade, 30, TimeUnit.SECONDS);
    }

    public void updateAggTrade(String symbol, AggTrade aggTrade) {
        aggTradeCache.put("aggtrade:" + symbol, aggTrade);
    }

    public void updateKline(String symbol, String interval, Kline kline) {
        String key = String.format("kline:%s:%s", symbol, interval);
        klineCache.put(key, kline);
        
        // 更新当前K线到Redis
        redisTemplate.opsForValue().set(
            String.format("market:kline:%s:%s:current", symbol, interval), 
            kline, 10, TimeUnit.MINUTES
        );
    }

    public void closeKline(String symbol, String interval, Kline kline) {
        // 添加到历史K线列表
        String key = String.format("market:kline:%s:%s:history", symbol, interval);
        redisTemplate.opsForZSet().add(key, kline, kline.getOpenTime());
        
        // 限制历史K线数量（保留最近2000根）
        redisTemplate.opsForZSet().removeRange(key, 0, -2001);
    }

    public void updateTicker(String symbol, TradeStats24h stats) {
        tickerCache.put("ticker:" + symbol, stats);
        
        // 更新Redis
        redisTemplate.opsForValue().set("market:ticker:" + symbol, stats, 10, TimeUnit.SECONDS);
    }

    public void updateDepth(String symbol, DepthUpdate depth) {
        depthCache.put("depth:" + symbol, depth);
        
        // 更新Redis（1秒过期）
        redisTemplate.opsForValue().set("market:depth:" + symbol, depth, 1, TimeUnit.SECONDS);
    }

    public void updateMarkPrice(String symbol, MarkPrice markPrice) {
        markPriceCache.put("markprice:" + symbol, markPrice);
        
        // 更新Redis
        redisTemplate.opsForValue().set("market:markprice:" + symbol, markPrice, 10, TimeUnit.SECONDS);
    }

    // ========== 查询方法 ==========

    public Trade getLastTrade(String symbol) {
        // 先查本地缓存
        Trade trade = lastTradeCache.getIfPresent("trade:" + symbol);
        if (trade != null) {
            return trade;
        }
        
        // 再查Redis
        trade = (Trade) redisTemplate.opsForValue().get("market:trade:" + symbol + ":last");
        if (trade != null) {
            lastTradeCache.put("trade:" + symbol, trade);
        }
        return trade;
    }

    public AggTrade getAggTrade(String symbol) {
        return aggTradeCache.getIfPresent("aggtrade:" + symbol);
    }

    public Kline getCurrentKline(String symbol, String interval) {
        String key = String.format("kline:%s:%s", symbol, interval);
        
        // 先查本地缓存
        Kline kline = klineCache.getIfPresent(key);
        if (kline != null) {
            return kline;
        }
        
        // 再查Redis
        kline = (Kline) redisTemplate.opsForValue().get(
            String.format("market:kline:%s:%s:current", symbol, interval)
        );
        if (kline != null) {
            klineCache.put(key, kline);
        }
        return kline;
    }

    @SuppressWarnings("unchecked")
    public List<Kline> getKlineHistory(String symbol, String interval, int limit) {
        String key = String.format("market:kline:%s:%s:history", symbol, interval);
        
        // 从Redis获取历史K线（按时间倒序）
        return (List<Kline>) (List<?>) redisTemplate.opsForZSet()
                .reverseRange(key, 0, limit - 1);
    }

    public TradeStats24h getTicker(String symbol) {
        // 先查本地缓存
        TradeStats24h stats = tickerCache.getIfPresent("ticker:" + symbol);
        if (stats != null) {
            return stats;
        }
        
        // 再查Redis
        stats = (TradeStats24h) redisTemplate.opsForValue().get("market:ticker:" + symbol);
        if (stats != null) {
            tickerCache.put("ticker:" + symbol, stats);
        }
        return stats;
    }

    public DepthUpdate getDepth(String symbol) {
        // 先查本地缓存
        DepthUpdate depth = depthCache.getIfPresent("depth:" + symbol);
        if (depth != null) {
            return depth;
        }
        
        // 再查Redis
        depth = (DepthUpdate) redisTemplate.opsForValue().get("market:depth:" + symbol);
        if (depth != null) {
            depthCache.put("depth:" + symbol, depth);
        }
        return depth;
    }

    public MarkPrice getMarkPrice(String symbol) {
        // 先查本地缓存
        MarkPrice markPrice = markPriceCache.getIfPresent("markprice:" + symbol);
        if (markPrice != null) {
            return markPrice;
        }
        
        // 再查Redis
        markPrice = (MarkPrice) redisTemplate.opsForValue().get("market:markprice:" + symbol);
        if (markPrice != null) {
            markPriceCache.put("markprice:" + symbol, markPrice);
        }
        return markPrice;
    }

    // ========== 统计方法 ==========

    public String getCacheStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Market Data Cache Stats ===\n");
        sb.append("Last Trade: ").append(lastTradeCache.stats()).append("\n");
        sb.append("Agg Trade: ").append(aggTradeCache.stats()).append("\n");
        sb.append("Kline: ").append(klineCache.stats()).append("\n");
        sb.append("Ticker: ").append(tickerCache.stats()).append("\n");
        sb.append("Depth: ").append(depthCache.stats()).append("\n");
        sb.append("Mark Price: ").append(markPriceCache.stats());
        return sb.toString();
    }
}
