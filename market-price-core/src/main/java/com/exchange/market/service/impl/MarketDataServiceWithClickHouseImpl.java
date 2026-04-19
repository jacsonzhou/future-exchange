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
import java.util.Comparator;
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
            List<Kline> klines = modelKlines.stream()
                    .map(this::convertToEntityKline)
                    .collect(Collectors.toList());
            return fillMissingKlineBars(klines, interval, limit);
        } catch (Exception e) {
            // ClickHouse 不可用时降级到缓存，避免 API 500
            log.warn("[MarketDataServiceWithClickHouseImpl] ClickHouse query failed, fallback to cache. symbol={}, interval={}, reason={}",
                    symbol, interval, e.getMessage());
            List<Kline> fallback = getKlinesFromCache(symbol, interval, startTime, endTime, limit);
            return fillMissingKlineBars(fallback, interval, limit);
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
        klineEngine.onTrade(symbol, price, quantity, tradeTime, isBuyerMaker, -1);
        
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

    /**
     * 补齐缺失K线，避免前端判定断档后回退外部源。
     * 规则：缺失周期使用上一根close平铺，volume/turnover/tradeCount置0。
     */
    private List<Kline> fillMissingKlineBars(List<Kline> input, String interval, int limit) {
        if (input == null || input.size() < 2) {
            return input == null ? List.of() : input;
        }
        long intervalMs = parseIntervalMillis(interval);
        if (intervalMs <= 0) {
            return input;
        }

        List<Kline> asc = input.stream()
                .filter(k -> k != null && k.getOpenTime() != null)
                .sorted(Comparator.comparingLong(Kline::getOpenTime))
                .collect(Collectors.toCollection(ArrayList::new));
        if (asc.size() < 2) {
            return input;
        }

        List<Kline> filled = new ArrayList<>(asc.size() + 32);
        Kline prev = cloneKline(asc.get(0));
        filled.add(prev);

        int maxSynthetic = Math.max(128, limit * 6);
        int syntheticCount = 0;
        for (int i = 1; i < asc.size(); i++) {
            Kline cur = asc.get(i);
            if (cur == null || cur.getOpenTime() == null) {
                continue;
            }

            long expected = prev.getOpenTime() + intervalMs;
            while (expected < cur.getOpenTime() && syntheticCount < maxSynthetic) {
                Kline gap = syntheticFrom(prev, expected, intervalMs);
                filled.add(gap);
                prev = gap;
                expected += intervalMs;
                syntheticCount++;
            }

            Kline curCopy = cloneKline(cur);
            filled.add(curCopy);
            prev = curCopy;
        }

        filled.sort((a, b) -> Long.compare(b.getOpenTime(), a.getOpenTime()));
        if (filled.size() > limit) {
            return new ArrayList<>(filled.subList(0, limit));
        }
        return filled;
    }

    private long parseIntervalMillis(String interval) {
        if (interval == null || interval.isBlank()) {
            return -1L;
        }
        String raw = interval.trim();
        if (raw.length() < 2) {
            return -1L;
        }
        char unit = raw.charAt(raw.length() - 1);
        int value;
        try {
            value = Integer.parseInt(raw.substring(0, raw.length() - 1));
        } catch (NumberFormatException e) {
            return -1L;
        }
        return switch (unit) {
            case 's' -> value * 1000L;
            case 'm' -> value * 60_000L;
            case 'h' -> value * 3_600_000L;
            case 'd' -> value * 86_400_000L;
            case 'w' -> value * 7L * 86_400_000L;
            case 'M' -> value * 30L * 86_400_000L;
            default -> -1L;
        };
    }

    private Kline syntheticFrom(Kline prev, long openTime, long intervalMs) {
        Kline gap = new Kline();
        gap.setSymbol(prev.getSymbol());
        gap.setInterval(prev.getInterval());
        gap.setOpenTime(openTime);
        gap.setCloseTime(openTime + intervalMs - 1);

        long close = prev.getClosePrice() == null ? 0L : prev.getClosePrice();
        gap.setOpenPrice(close);
        gap.setHighPrice(close);
        gap.setLowPrice(close);
        gap.setClosePrice(close);
        gap.setVolume(0L);
        gap.setQuoteVolume(0L);
        gap.setTradeCount(0);
        gap.setTakerBuyVolume(0L);
        gap.setTakerBuyQuoteVolume(0L);
        return gap;
    }

    private Kline cloneKline(Kline src) {
        Kline copy = new Kline();
        copy.setSymbol(src.getSymbol());
        copy.setInterval(src.getInterval());
        copy.setOpenTime(src.getOpenTime());
        copy.setCloseTime(src.getCloseTime());
        copy.setOpenPrice(src.getOpenPrice());
        copy.setHighPrice(src.getHighPrice());
        copy.setLowPrice(src.getLowPrice());
        copy.setClosePrice(src.getClosePrice());
        copy.setVolume(src.getVolume());
        copy.setQuoteVolume(src.getQuoteVolume());
        copy.setTradeCount(src.getTradeCount());
        copy.setTakerBuyVolume(src.getTakerBuyVolume());
        copy.setTakerBuyQuoteVolume(src.getTakerBuyQuoteVolume());
        return copy;
    }
}
