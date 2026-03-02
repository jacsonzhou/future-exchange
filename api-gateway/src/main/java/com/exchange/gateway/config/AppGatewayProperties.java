package com.exchange.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Gateway 配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "gateway")
public class AppGatewayProperties {
    
    /**
     * 限流配置
     */
    private RateLimitConfig rateLimit = new RateLimitConfig();
    
    /**
     * 熔断配置
     */
    private CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();
    
    /**
     * 交易对开关
     */
    private SymbolSwitchConfig symbolSwitch = new SymbolSwitchConfig();
    
    /**
     * 限流配置
     */
    @Data
    public static class RateLimitConfig {
        /**
         * 是否启用限流
         */
        private boolean enabled = true;
        
        /**
         * 每用户限流（请求/秒）
         */
        private Map<String, Integer> perUser = new HashMap<>();
        
        /**
         * 每IP限流（请求/秒）
         */
        private Map<String, Integer> perIp = new HashMap<>();
        
        /**
         * 每交易对限流（请求/秒）
         */
        private Map<String, Integer> perSymbol = new HashMap<>();
        
        public RateLimitConfig() {
            // 默认配置
            perUser.put("submitOrder", 50);
            perUser.put("cancelOrder", 100);
            perIp.put("submitOrder", 20);
            perSymbol.put("BTCUSDT", 200);
            perSymbol.put("ETHUSDT", 150);
            perSymbol.put("SOLUSDT", 120);
            perSymbol.put("BNBUSDT", 120);
            perSymbol.put("XRPUSDT", 120);
        }
    }
    
    /**
     * 熔断配置
     */
    @Data
    public static class CircuitBreakerConfig {
        /**
         * 是否启用熔断
         */
        private boolean enabled = true;
        
        /**
         * 失败率阈值（百分比）
         */
        private int failureThreshold = 50;
        
        /**
         * 最小请求数
         */
        private int minimumRequests = 10;
        
        /**
         * 熔断时长（秒）
         */
        private int waitDurationInOpenState = 60;
    }
    
    /**
     * 交易对开关配置
     */
    @Data
    public static class SymbolSwitchConfig {
        /**
         * 全局交易开关
         */
        private boolean globalTradingEnabled = true;
        
        /**
         * 禁用的交易对
         */
        private Map<String, Boolean> disabledSymbols = new HashMap<>();
        
        /**
         * 只允许平仓的交易对
         */
        private Map<String, Boolean> closeOnlySymbols = new HashMap<>();
    }
}
