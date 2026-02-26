package com.exchange.market.service.impl;

import com.exchange.market.engine.KlineEngine;
import com.exchange.market.engine.OrderBook;
import com.exchange.market.engine.TradeEngine;
import com.exchange.market.entity.Kline;
import com.exchange.market.entity.Ticker24h;
import com.exchange.market.service.MarketDataEngineService;
import com.exchange.market.service.MarketDataService;
import com.exchange.market.cache.MarketDataCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 行情数据服务实现
 * 
 * 提供REST API查询接口
 * 
 * 查询路径：
 * 1. 本地缓存（Caffeine）
 * 2. Redis
 * 3. 内存引擎（实时计算）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataServiceImpl implements MarketDataService {

    private final MarketDataEngineService engineService;
    private final MarketDataCache cache;

    @Override
    public List<Kline> getKlines(String symbol, String interval, Long startTime, Long endTime, Integer limit) {
        if (limit == null || limit <= 0) {
            limit = 500;
        }
        limit = Math.min(limit, 1000); // 最大1000条
        
        // 从缓存获取历史K线（model.Kline -> entity.Kline）
        List<com.exchange.market.model.Kline> modelKlines = cache.getKlineHistory(symbol, interval, limit);
        List<Kline> klines = modelKlines != null ? modelKlines.stream()
                .map(this::convertToEntityKline)
                .collect(Collectors.toList()) : new ArrayList<>();
        
        // 获取当前K线
        com.exchange.market.model.Kline modelCurrentKline = cache.getCurrentKline(symbol, interval);
        Kline currentKline = modelCurrentKline != null ? convertToEntityKline(modelCurrentKline) : null;
        if (currentKline != null) {
            // 添加到结果开头
            List<Kline> result = new ArrayList<>(klines.size() + 1);
            result.add(currentKline);
            result.addAll(klines);
            
            // 过滤时间范围
            if (startTime != null || endTime != null) {
                return result.stream()
                        .filter(k -> (startTime == null || k.getOpenTime() >= startTime))
                        .filter(k -> (endTime == null || k.getOpenTime() <= endTime))
                        .limit(limit)
                        .collect(Collectors.toList());
            }
            
            return result.stream().limit(limit).collect(Collectors.toList());
        }
        
        return klines;
    }

    @Override
    public Kline getLatestKline(String symbol, String interval) {
        com.exchange.market.model.Kline modelKline = cache.getCurrentKline(symbol, interval);
        return modelKline != null ? convertToEntityKline(modelKline) : null;
    }

    @Override
    public Ticker24h getTicker24h(String symbol) {
        // 从缓存获取
        TradeEngine.TradeStats24h stats = cache.getTicker(symbol);
        if (stats != null) {
            return convertToTicker24h(stats);
        }
        
        // 从引擎获取
        TradeEngine tradeEngine = engineService.getTradeEngine(symbol);
        if (tradeEngine != null) {
            stats = tradeEngine.getTradeStats24h();
            return convertToTicker24h(stats);
        }
        
        return null;
    }

    @Override
    public List<Ticker24h> getAllTickers24h() {
        List<String> symbols = engineService.getSymbols();
        return symbols.stream()
                .map(this::getTicker24h)
                .filter(t -> t != null)
                .collect(Collectors.toList());
    }

    @Override
    public void onTradeEvent(String symbol, Long price, Long quantity, Long tradeTime, boolean isBuyerMaker) {
        // 直接交给引擎处理
        // 注意：这里应该通过Kafka消费，而不是直接调用
        // 此方法用于内部测试或回灌场景
    }

    /**
     * 获取深度快照
     */
    public OrderBook.DepthSnapshot getDepthSnapshot(String symbol, int limit) {
        OrderBook orderBook = engineService.getOrCreateOrderBook(symbol);
        return orderBook.getSnapshot(limit);
    }

    /**
     * 获取BBO（最优盘口）
     */
    public long[] getBBO(String symbol) {
        OrderBook orderBook = engineService.getOrderBook(symbol);
        if (orderBook == null) {
            return new long[]{0, 0, Long.MAX_VALUE, 0};
        }
        return orderBook.getBBO();
    }

    /**
     * 转换model.Kline到entity.Kline
     */
    private Kline convertToEntityKline(com.exchange.market.model.Kline modelKline) {
        if (modelKline == null) {
            return null;
        }
        Kline entity = new Kline();
        entity.setSymbol(modelKline.getSymbol());
        entity.setInterval(modelKline.getInterval());
        entity.setOpenTime(modelKline.getOpenTime());
        entity.setCloseTime(modelKline.getCloseTime());
        entity.setOpenPrice(modelKline.getOpenPrice());
        entity.setHighPrice(modelKline.getHighPrice());
        entity.setLowPrice(modelKline.getLowPrice());
        entity.setClosePrice(modelKline.getClosePrice());
        entity.setVolume(modelKline.getVolume());
        entity.setQuoteVolume(modelKline.getQuoteVolume());
        entity.setTradeCount(modelKline.getTradeCount());
        entity.setTakerBuyVolume(modelKline.getTakerBuyVolume());
        entity.setTakerBuyQuoteVolume(modelKline.getTakerBuyQuoteVolume());
        return entity;
    }

    /**
     * 转换TradeStats24h到Ticker24h实体
     */
    private Ticker24h convertToTicker24h(TradeEngine.TradeStats24h stats) {
        Ticker24h ticker = new Ticker24h();
        ticker.setSymbol(stats.getSymbol());
        ticker.setLastPrice(stats.getLastPrice());
        ticker.setLastQty(stats.getLastQty());
        ticker.setPriceChange(stats.getPriceChange());
        ticker.setPriceChangePercent((long) (stats.getPriceChangePercent() * 100)); // 存储为百分比*100
        ticker.setWeightedAvgPrice(stats.getWeightedAvgPrice());
        ticker.setOpenPrice(stats.getOpenPrice());
        ticker.setHighPrice(stats.getHighPrice());
        ticker.setLowPrice(stats.getLowPrice());
        ticker.setVolume(stats.getVolume());
        ticker.setQuoteVolume(stats.getQuoteVolume());
        ticker.setOpenTime(stats.getOpenTime());
        ticker.setCloseTime(stats.getCloseTime());
        ticker.setFirstId(stats.getFirstId());
        ticker.setLastId(stats.getLastId());
        ticker.setCount(stats.getCount());
        ticker.setTimestamp(System.currentTimeMillis());
        return ticker;
    }
}
