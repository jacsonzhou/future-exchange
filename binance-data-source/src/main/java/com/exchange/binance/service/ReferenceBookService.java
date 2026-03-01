package com.exchange.binance.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.binance.config.BinanceDataSourceConfig;
import com.exchange.binance.dto.ReferenceBookSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 参考盘口服务
 *
 * 数据来源：
 * 1. Redis 实时深度快照（binance:depth:{symbol}）
 * 2. 输出统一 ReferenceBookSnapshot 契约供 CFD 链路读取
 */
@Service
@RequiredArgsConstructor
public class ReferenceBookService {

    private static final String REDIS_DEPTH_PREFIX = "binance:depth:";
    private static final int MAX_DEPTH_LIMIT = 200;

    private final RedisTemplate<String, Object> redisTemplate;
    private final BinanceDataSourceConfig config;

    public ReferenceBookSnapshot getReferenceBook(String symbol, int depth) {
        String normalizedSymbol = normalizeSymbol(symbol);
        int normalizedDepth = normalizeDepth(depth);

        Object raw = redisTemplate.opsForValue().get(REDIS_DEPTH_PREFIX + normalizedSymbol);
        if (raw == null) {
            return null;
        }

        JSONObject depthJson = JSON.parseObject(raw.toString());
        if (depthJson == null) {
            return null;
        }

        String source = normalizeSource(depthJson.getString("source"));
        String topic = String.format(config.getKafka().getDepthTopicFormat(), source, normalizedSymbol);
        long eventTime = depthJson.getLongValue("E");
        long offset = depthJson.getLongValue("u");

        List<ReferenceBookSnapshot.PriceLevel> bidsTopN = toPriceLevels(depthJson.getJSONArray("b"), normalizedDepth);
        List<ReferenceBookSnapshot.PriceLevel> asksTopN = toPriceLevels(depthJson.getJSONArray("a"), normalizedDepth);

        String bestBid = bidsTopN.isEmpty() ? null : bidsTopN.get(0).getPrice();
        String bestAsk = asksTopN.isEmpty() ? null : asksTopN.get(0).getPrice();
        long now = System.currentTimeMillis();
        long stalenessMs = eventTime <= 0 ? Long.MAX_VALUE : Math.max(0L, now - eventTime);

        ReferenceBookSnapshot snapshot = ReferenceBookSnapshot.builder()
                .symbol(normalizedSymbol)
                .eventTime(eventTime)
                .topic(topic)
                .offset(offset)
                .bestBid(bestBid)
                .bestAsk(bestAsk)
                .bidsTopN(bidsTopN)
                .asksTopN(asksTopN)
                .source(source)
                .stalenessMs(stalenessMs)
                .build();

        return snapshot;
    }

    private List<ReferenceBookSnapshot.PriceLevel> toPriceLevels(JSONArray levels, int depth) {
        List<ReferenceBookSnapshot.PriceLevel> result = new ArrayList<>();
        if (levels == null || levels.isEmpty()) {
            return result;
        }

        int count = Math.min(depth, levels.size());
        for (int i = 0; i < count; i++) {
            JSONArray level = levels.getJSONArray(i);
            if (level == null || level.size() < 2) {
                continue;
            }
            String price = level.getString(0);
            String quantity = level.getString(1);
            result.add(new ReferenceBookSnapshot.PriceLevel(price, quantity));
        }
        return result;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "BTCUSDT";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private int normalizeDepth(int depth) {
        if (depth <= 0) {
            return 20;
        }
        return Math.min(depth, MAX_DEPTH_LIMIT);
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            if (config.getSource() == null || config.getSource().isBlank()) {
                return "binance";
            }
            return config.getSource().trim().toLowerCase(Locale.ROOT);
        }
        return source.trim().toLowerCase(Locale.ROOT);
    }
}
