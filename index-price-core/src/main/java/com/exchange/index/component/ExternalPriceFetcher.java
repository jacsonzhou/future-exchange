package com.exchange.index.component;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部交易所价格获取器
 * 
 * 模拟从外部交易所获取价格数据
 * 实际生产环境需要对接各交易所API
 */
@Slf4j
@Component
public class ExternalPriceFetcher {

    private final WebClient webClient;
    private final Map<String, ExchangePriceCache> priceCache = new ConcurrentHashMap<>();

    public ExternalPriceFetcher() {
        this.webClient = WebClient.builder().build();
    }

    /**
     * 从指定交易所获取价格
     *
     * @param exchange 交易所代码 (binance, okx, coinbase)
     * @param symbol   交易对
     * @return 价格（8位精度）
     */
    public Long fetchPrice(String exchange, String symbol) {
        try {
            // 实际生产环境调用交易所API
            // 这里使用模拟数据
            Long mockPrice = getMockPrice(exchange, symbol);
            
            // 更新缓存
            priceCache.put(exchange + ":" + symbol, 
                    new ExchangePriceCache(mockPrice, System.currentTimeMillis()));
            
            return mockPrice;
        } catch (Exception e) {
            log.error("Failed to fetch price from {} for {}", exchange, symbol, e);
            // 返回缓存的价格
            ExchangePriceCache cache = priceCache.get(exchange + ":" + symbol);
            return cache != null ? cache.getPrice() : null;
        }
    }

    /**
     * 批量获取多个交易所的价格
     */
    public Map<String, Long> fetchPricesFromMultipleExchanges(String[] exchanges, String symbol) {
        Map<String, Long> prices = new ConcurrentHashMap<>();
        for (String exchange : exchanges) {
            Long price = fetchPrice(exchange, symbol);
            if (price != null) {
                prices.put(exchange, price);
            }
        }
        return prices;
    }

    /**
     * 模拟价格数据
     * 生产环境需要替换为真实API调用
     */
    private Long getMockPrice(String exchange, String symbol) {
        // 基准价格
        long basePrice = "BTCUSDT".equals(symbol) ? 50000_00000000L : 
                        ("ETHUSDT".equals(symbol) ? 3000_00000000L : 100_00000000L);
        
        // 根据交易所添加小幅偏差模拟真实场景
        double deviation = switch (exchange.toLowerCase()) {
            case "binance" -> 1.0;
            case "okx" -> 1.0002;
            case "coinbase" -> 0.9998;
            default -> 1.0;
        };
        
        // 添加随机波动
        double randomFactor = 0.9995 + Math.random() * 0.001;
        
        return (long) (basePrice * deviation * randomFactor);
    }

    @Data
    private static class ExchangePriceCache {
        private final Long price;
        private final Long timestamp;
    }
}
