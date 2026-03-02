package com.exchange.market.service.impl;

import com.exchange.market.engine.KlineEngineWithStorage;
import com.exchange.market.engine.OrderBook;
import com.exchange.market.engine.TradeEngine;
import com.exchange.market.entity.Kline;
import com.exchange.market.entity.Ticker24h;
import com.exchange.market.model.Trade;
import com.exchange.market.config.ExternalMarketProperties;
import com.exchange.market.service.MarketDataEngineService;
import com.exchange.market.service.MarketDataService;
import com.exchange.market.cache.MarketDataCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 行情数据服务实现（支持 ClickHouse 存储）
 * 
 * 特性：
 * - K 线数据存储到 ClickHouse
 * - 支持历史数据查询
 * - 兼容原有 API
 * 
 * 查询路径：
 * 1. ClickHouse (历史 K 线)
 * 2. 本地缓存（当前 K 线）
 * 3. 内存引擎（实时计算）
 */
@Slf4j
@Service
@Primary
public class MarketDataServiceWithClickHouseImpl implements MarketDataService {

    @Autowired
    private MarketDataEngineService engineService;
    
    @Autowired
    private MarketDataCache cache;
    
    @Autowired
    private KlineEngineWithStorage klineEngine;

    @Autowired(required = false)
    private ExternalMarketProperties externalMarketProperties;

    @PostConstruct
    public void init() {
        log.info("[MarketDataServiceWithClickHouseImpl] Initialized with ClickHouse storage");
    }

    @Override
    public List<Kline> getKlines(String symbol, String interval, Long startTime, Long endTime, Integer limit) {
        if (limit == null || limit <= 0) {
            limit = 500;
        }
        limit = Math.min(limit, 1000); // 最大1000条

        try {
            // 使用 KlineEngineWithStorage 查询（包含 ClickHouse 查询）
            List<com.exchange.market.model.Kline> modelKlines;

            if (startTime != null || endTime != null) {
                // 时间范围查询 - 从 ClickHouse 查询
                modelKlines = klineEngine.queryKlines(symbol, interval, startTime, endTime, limit);
            } else {
                // 最新数据查询
                modelKlines = klineEngine.getKlines(symbol, interval, limit);
            }

            // 转换为 entity.Kline
            return modelKlines.stream()
                    .map(this::convertToEntityKline)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // ClickHouse 不可用时降级到缓存，避免 API 500
            log.warn("[MarketDataServiceWithClickHouseImpl] ClickHouse query failed, fallback to cache. symbol={}, interval={}, reason={}",
                    symbol, interval, e.getMessage());
            return getKlinesFromCache(symbol, interval, startTime, endTime, limit);
        }
    }

    @Override
    public Kline getLatestKline(String symbol, String interval) {
        try {
            com.exchange.market.model.Kline modelKline = klineEngine.getCurrentKline(symbol, interval);

            if (modelKline == null) {
                // 尝试从 ClickHouse 查询最新
                List<com.exchange.market.model.Kline> recent = klineEngine.getKlines(symbol, interval, 1);
                if (!recent.isEmpty()) {
                    modelKline = recent.get(0);
                }
            }

            return modelKline != null ? convertToEntityKline(modelKline) : null;
        } catch (Exception e) {
            log.warn("[MarketDataServiceWithClickHouseImpl] getLatestKline fallback to cache. symbol={}, interval={}, reason={}",
                    symbol, interval, e.getMessage());
            com.exchange.market.model.Kline cached = cache.getCurrentKline(symbol, interval);
            return cached != null ? convertToEntityKline(cached) : null;
        }
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
        Set<String> symbols = new LinkedHashSet<>(engineService.getSymbols());
        if (externalMarketProperties != null
                && externalMarketProperties.isEnabled()
                && externalMarketProperties.getSymbols() != null) {
            externalMarketProperties.getSymbols().stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> s.trim().toUpperCase(Locale.ROOT))
                    .forEach(symbols::add);
        }

        return symbols.stream()
                .map(this::getTicker24h)
                .filter(t -> t != null)
                .collect(Collectors.toList());
    }

    @Override
    public void onTradeEvent(String symbol, Long price, Long quantity, Long tradeTime, boolean isBuyerMaker) {
        // 1. 更新 K 线引擎（存储到 ClickHouse）
        klineEngine.onTrade(symbol, price, quantity, tradeTime, isBuyerMaker);
        
        // 2. 更新 TradeEngine
        TradeEngine tradeEngine = engineService.getTradeEngine(symbol);
        if (tradeEngine != null) {
            Trade trade = new Trade();
            trade.setSymbol(symbol);
            trade.setPrice(price);
            trade.setQuantity(quantity);
            trade.setTimestamp(tradeTime);
            trade.setBuyerMaker(isBuyerMaker);
            tradeEngine.onTrade(trade);
        }
    }

    /**
     * 获取深度快照
     */
    public OrderBook.DepthSnapshot getDepthSnapshot(String symbol, int limit) {
        OrderBook orderBook = engineService.getOrderBook(symbol);
        if (orderBook == null) {
            return null;
        }
        return orderBook.getSnapshot(limit);
    }

    /**
     * 获取 BBO（最优盘口）
     */
    public long[] getBBO(String symbol) {
        OrderBook orderBook = engineService.getOrderBook(symbol);
        if (orderBook == null) {
            return new long[]{0, 0, Long.MAX_VALUE, 0};
        }
        return orderBook.getBBO();
    }

    /**
     * 转换 model.Kline 到 entity.Kline
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
     * 转换 TradeStats24h 到 Ticker24h 实体
     */
    private Ticker24h convertToTicker24h(TradeEngine.TradeStats24h stats) {
        Ticker24h ticker = new Ticker24h();
        ticker.setSymbol(stats.getSymbol());
        ticker.setLastPrice(stats.getLastPrice());
        ticker.setLastQty(stats.getLastQty());
        ticker.setPriceChange(stats.getPriceChange());
        ticker.setPriceChangePercent((long) (stats.getPriceChangePercent() * 100));
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

    private List<Kline> getKlinesFromCache(String symbol, String interval, Long startTime, Long endTime, int limit) {
        List<com.exchange.market.model.Kline> history = cache.getKlineHistory(symbol, interval, limit);
        List<Kline> klines = history != null
                ? history.stream().map(this::convertToEntityKline).collect(Collectors.toCollection(ArrayList::new))
                : new ArrayList<>();

        com.exchange.market.model.Kline modelCurrent = cache.getCurrentKline(symbol, interval);
        Kline current = modelCurrent != null ? convertToEntityKline(modelCurrent) : null;
        if (current != null && klines.stream().noneMatch(k -> k.getOpenTime() == current.getOpenTime())) {
            klines.add(0, current);
        }

        return klines.stream()
                .filter(k -> startTime == null || k.getOpenTime() >= startTime)
                .filter(k -> endTime == null || k.getOpenTime() <= endTime)
                .limit(limit)
                .collect(Collectors.toList());
    }
}
