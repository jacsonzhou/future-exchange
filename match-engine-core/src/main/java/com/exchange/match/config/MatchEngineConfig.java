package com.exchange.match.config;

import com.exchange.match.orderbook.OrderBook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 撮合引擎配置
 */
@Configuration
public class MatchEngineConfig {
    
    @Value("${match.symbol:BTCUSDT}")
    private String symbol;
    
    /**
     * 订单簿Bean
     * 
     * 每个Symbol一个OrderBook实例
     */
    @Bean
    public OrderBook orderBook() {
        return new OrderBook(symbol);
    }
}

