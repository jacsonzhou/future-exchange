package com.exchange.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

/**
 * CORS 跨域配置
 * 支持前端开发服务器 (localhost:3000, 8080, 5173, 3001 等)
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        
        // 允许的源 - 支持常见前端开发服务器端口
        config.setAllowedOriginPatterns(Arrays.asList(
            "*",                          // 允许所有源 (开发环境)
            "http://localhost:*",         // localhost 任意端口
            "http://127.0.0.1:*"          // 127.0.0.1 任意端口
        ));
        
        // 允许的方法
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        
        // 允许的请求头
        config.setAllowedHeaders(Arrays.asList(
            "*",
            "Authorization",
            "Content-Type",
            "X-User-Id",
            "X-Trace-Id",
            "X-Idempotency-Key",
            "X-Request-Id"
        ));
        
        // 允许暴露的响应头
        config.setExposedHeaders(Arrays.asList(
            "X-Request-Id",
            "X-Trace-Id"
        ));
        
        // 不允许凭证 (true 时不能用 * 作为源)
        config.setAllowCredentials(false);
        
        // 预检请求缓存时间（秒）
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsWebFilter(source);
    }
}
