package com.exchange.market.job;

import com.exchange.market.cache.MarketDataCache;
import com.exchange.market.config.ExternalMarketProperties;
import com.exchange.market.engine.TradeEngine;
import com.exchange.market.model.Kline;
import com.exchange.market.service.KlineService;
import com.exchange.market.service.support.ExternalTickerStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 24h Ticker 回退刷新任务：
 * - 优先使用外部 ticker（Binance）
 * - 外部陈旧时从本地 1m K 线聚合
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Ticker24hFallbackRefreshJob {

    private final ExternalMarketProperties externalProperties;
    private final ExternalTickerStateService externalTickerStateService;
    private final KlineService klineService;
    private final MarketDataCache marketDataCache;

    @Scheduled(fixedDelayString = "${market-data.external.ticker.fallback-refresh-ms:1000}")
    public void refreshTickerCache() {
        if (!externalProperties.isEnabled()) {
            return;
        }

        long freshMs = Math.max(1000L, externalProperties.getTicker().getExternalFreshMs());
        for (String symbol : resolveSymbols()) {
            try {
                TradeEngine.TradeStats24h external = externalTickerStateService.getFresh(symbol, freshMs);
                if (external != null) {
                    marketDataCache.updateTicker(symbol, external);
                    continue;
                }

                TradeEngine.TradeStats24h fallback = aggregateFromKline(symbol);
                if (fallback != null) {
                    marketDataCache.updateTicker(symbol, fallback);
                }
            } catch (Exception e) {
                log.warn("[TickerFallback] Failed to refresh ticker for {}", symbol, e);
            }
        }
    }

    private TradeEngine.TradeStats24h aggregateFromKline(String symbol) {
        long now = System.currentTimeMillis();
        long windowStart = now - TimeUnit.DAYS.toMillis(1);

        List<Kline> klines = new ArrayList<>(klineService.getKlines(symbol, "1m", windowStart, now, 2000));
        Kline realtime = klineService.getLatestKline(symbol, "1m");
        if (realtime != null && realtime.getOpenTime() >= windowStart) {
            boolean exists = klines.stream().anyMatch(k -> k.getOpenTime() == realtime.getOpenTime());
            if (!exists) {
                klines.add(realtime);
            }
        }

        if (klines.isEmpty()) {
            return null;
        }

        Kline oldest = null;
        Kline latest = null;
        long high = Long.MIN_VALUE;
        long low = Long.MAX_VALUE;
        long volume = 0L;
        long quoteVolume = 0L;
        int tradeCount = 0;

        for (Kline kline : klines) {
            if (kline.getOpenTime() < windowStart) {
                continue;
            }

            if (oldest == null || kline.getOpenTime() < oldest.getOpenTime()) {
                oldest = kline;
            }
            if (latest == null || kline.getOpenTime() > latest.getOpenTime()) {
                latest = kline;
            }

            high = Math.max(high, kline.getHighPrice());
            low = Math.min(low, kline.getLowPrice());
            volume += kline.getVolume();
            quoteVolume += kline.getQuoteVolume();
            tradeCount += kline.getTradeCount();
        }

        if (oldest == null || latest == null) {
            return null;
        }

        long openPrice = oldest.getOpenPrice();
        long lastPrice = latest.getClosePrice();
        long priceChange = lastPrice - openPrice;
        double priceChangePercent = openPrice > 0
                ? (double) priceChange * 100.0 / openPrice
                : 0D;

        TradeEngine.TradeStats24h stats = new TradeEngine.TradeStats24h();
        stats.setSymbol(symbol);
        stats.setLastPrice(lastPrice);
        stats.setLastQty(0L);
        stats.setPriceChange(priceChange);
        stats.setPriceChangePercent(priceChangePercent);
        stats.setWeightedAvgPrice(volume > 0 ? quoteVolume / volume : 0L);
        stats.setOpenPrice(openPrice);
        stats.setHighPrice(high == Long.MIN_VALUE ? 0L : high);
        stats.setLowPrice(low == Long.MAX_VALUE ? 0L : low);
        stats.setVolume(volume);
        stats.setQuoteVolume(quoteVolume);
        stats.setOpenTime(oldest.getOpenTime());
        stats.setCloseTime(now);
        stats.setFirstId(0L);
        stats.setLastId(0L);
        stats.setCount(Math.max(0, tradeCount));

        // 兼容字段
        stats.setHigh24h(stats.getHighPrice());
        stats.setLow24h(stats.getLowPrice());
        stats.setOpen24h(stats.getOpenPrice());
        stats.setVolume24h(stats.getVolume());
        stats.setQuoteVolume24h(stats.getQuoteVolume());
        stats.setTradeCount(stats.getCount());
        return stats;
    }

    private List<String> resolveSymbols() {
        Set<String> symbols = new HashSet<>();
        if (externalProperties.getSymbols() != null) {
            for (String symbol : externalProperties.getSymbols()) {
                if (symbol == null || symbol.isBlank()) {
                    continue;
                }
                symbols.add(symbol.trim().toUpperCase(Locale.ROOT));
            }
        }
        return List.copyOf(symbols);
    }
}

