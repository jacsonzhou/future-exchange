package com.exchange.market.service.support;

import com.exchange.market.engine.TradeEngine;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部 24h ticker 最新状态缓存。
 */
@Service
public class ExternalTickerStateService {

    private final Map<String, TimedStats> latestBySymbol = new ConcurrentHashMap<>();

    public void update(String symbol, TradeEngine.TradeStats24h stats, long eventTime) {
        if (symbol == null || symbol.isBlank() || stats == null) {
            return;
        }
        latestBySymbol.put(symbol, new TimedStats(stats, eventTime > 0 ? eventTime : System.currentTimeMillis()));
    }

    public TradeEngine.TradeStats24h getFresh(String symbol, long freshnessMs) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        TimedStats timed = latestBySymbol.get(symbol);
        if (timed == null) {
            return null;
        }
        long age = System.currentTimeMillis() - timed.eventTime;
        if (age > freshnessMs) {
            return null;
        }
        return timed.stats;
    }

    private record TimedStats(TradeEngine.TradeStats24h stats, long eventTime) {
    }
}

