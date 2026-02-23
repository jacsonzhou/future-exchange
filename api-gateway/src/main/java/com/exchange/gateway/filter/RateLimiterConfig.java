package com.exchange.gateway.filter;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * 限流配置
 * 
 * 提供多种 KeyResolver：
 * 1. userKeyResolver - 基于用户ID限流（需先登录）
 * 2. ipKeyResolver - 基于IP限流（公开接口）
 * 3. apiKeyResolver - 基于API Key限流（第三方接入）
 */
@Configuration
public class RateLimiterConfig {
    
    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_API_KEY = "X-API-Key";
    
    /**
     * 用户级限流 KeyResolver
     * 
     * 用于需要认证的业务接口（如下单、撤单）
     */
    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst(HEADER_USER_ID);
            if (userId != null && !userId.isEmpty()) {
                return Mono.just("user:" + userId);
            }
            // 没有用户ID，退回到IP限流
            return ipKeyResolver().resolve(exchange);
        };
    }
    
    /**
     * IP级限流 KeyResolver
     * 
     * 用于公开接口（如行情、登录）
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String ip = extractClientIp(exchange.getRequest());
            return Mono.just("ip:" + ip);
        };
    }
    
    /**
     * API Key限流 KeyResolver
     * 
     * 用于第三方API接入
     */
    @Bean
    public KeyResolver apiKeyResolver() {
        return exchange -> {
            String apiKey = exchange.getRequest().getHeaders().getFirst(HEADER_API_KEY);
            if (apiKey != null && !apiKey.isEmpty()) {
                return Mono.just("apikey:" + apiKey);
            }
            // 没有API Key，退回到IP限流
            return ipKeyResolver().resolve(exchange);
        };
    }
    
    /**
     * 提取客户端IP
     */
    private String extractClientIp(org.springframework.http.server.reactive.ServerHttpRequest request) {
        String ip = request.getHeaders().getFirst("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeaders().getFirst("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddress() != null ? 
                request.getRemoteAddress().getAddress().getHostAddress() : "unknown";
        }
        // 如果有多个IP，取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
