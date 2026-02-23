package com.exchange.gateway.config;

import com.exchange.common.core.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.web.client.RestTemplate;

/**
 * Gateway Bean 配置
 */
@Slf4j
@Configuration
public class GatewayBeanConfig {
    
    @Value("${jwt.secret:future-exchange-user-core-secret-key-2024}")
    private String jwtSecret;
    
    @Value("${jwt.expiration:86400000}")
    private Long jwtExpiration;
    
    /**
     * 响应式 Redis Template
     */
    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        return new ReactiveStringRedisTemplate(
            connectionFactory,
            RedisSerializationContext.string()
        );
    }
    
    /**
     * JWT 工具（共享配置）
     */
    @Bean
    public JwtUtil jwtUtil() {
        return new JwtUtil(jwtSecret, jwtExpiration);
    }
    
    /**
     * RestTemplate（用于同步调用下游服务）
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
