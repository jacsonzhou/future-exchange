package com.exchange.index.service.support;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 外部行情状态缓存（按symbol维度）
 */
@Component
public class ExternalMarketStateStore {

    private final Map<String, SymbolState> symbolStateMap = new ConcurrentHashMap<>();

    public void updateDepth(String symbol, long bestBid, long bestAsk, long sourceEventTime,
                            String sourceTopic, long sourceOffset) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        String normalized = normalizeSymbol(symbol);
        symbolStateMap.computeIfAbsent(normalized, key -> new SymbolState())
                .updateDepth(bestBid, bestAsk, sourceEventTime, sourceTopic, sourceOffset);
    }

    public void updateTrade(String symbol, long lastTradePrice, long sourceEventTime,
                            String sourceTopic, long sourceOffset) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        String normalized = normalizeSymbol(symbol);
        symbolStateMap.computeIfAbsent(normalized, key -> new SymbolState())
                .updateTrade(lastTradePrice, sourceEventTime, sourceTopic, sourceOffset);
    }

    public MarketSnapshot getSnapshot(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        SymbolState state = symbolStateMap.get(normalizeSymbol(symbol));
        return state == null ? null : state.snapshot(normalizeSymbol(symbol));
    }

    public Map<String, MarketSnapshot> getAllSnapshots() {
        return symbolStateMap.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().snapshot(entry.getKey())
                ));
    }

    private String normalizeSymbol(String symbol) {
        return symbol.trim().toUpperCase();
    }

    private static class SymbolState {
        private long bestBid;
        private long bestAsk;
        private long lastTradePrice;
        private long sourceEventTime;
        private String sourceTopic;
        private long sourceOffset = -1;
        private long updatedAt;

        synchronized void updateDepth(long bestBid, long bestAsk, long sourceEventTime,
                                      String sourceTopic, long sourceOffset) {
            this.bestBid = bestBid;
            this.bestAsk = bestAsk;
            this.sourceEventTime = sourceEventTime;
            this.sourceTopic = sourceTopic;
            this.sourceOffset = sourceOffset;
            this.updatedAt = System.currentTimeMillis();
        }

        synchronized void updateTrade(long lastTradePrice, long sourceEventTime,
                                      String sourceTopic, long sourceOffset) {
            this.lastTradePrice = lastTradePrice;
            this.sourceEventTime = sourceEventTime;
            this.sourceTopic = sourceTopic;
            this.sourceOffset = sourceOffset;
            this.updatedAt = System.currentTimeMillis();
        }

        synchronized MarketSnapshot snapshot(String symbol) {
            return new MarketSnapshot(
                    symbol,
                    bestBid,
                    bestAsk,
                    lastTradePrice,
                    sourceEventTime,
                    sourceTopic,
                    sourceOffset,
                    updatedAt
            );
        }
    }

    @Getter
    public static class MarketSnapshot {
        private final String symbol;
        private final long bestBid;
        private final long bestAsk;
        private final long lastTradePrice;
        private final long sourceEventTime;
        private final String sourceTopic;
        private final long sourceOffset;
        private final long updatedAt;

        public MarketSnapshot(String symbol, long bestBid, long bestAsk, long lastTradePrice,
                              long sourceEventTime, String sourceTopic, long sourceOffset,
                              long updatedAt) {
            this.symbol = symbol;
            this.bestBid = bestBid;
            this.bestAsk = bestAsk;
            this.lastTradePrice = lastTradePrice;
            this.sourceEventTime = sourceEventTime;
            this.sourceTopic = sourceTopic;
            this.sourceOffset = sourceOffset;
            this.updatedAt = updatedAt;
        }
    }
}
