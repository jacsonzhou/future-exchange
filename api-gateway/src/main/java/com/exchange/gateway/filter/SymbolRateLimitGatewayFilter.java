package com.exchange.gateway.filter;

import com.exchange.gateway.config.AppGatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 交易对级限流过滤器
 * 
 * 针对热点交易对（如 BTCUSDT）进行限流保护
 * 
 * 使用方式：
 * filters:
 *   - SymbolRateLimit
 */
@Component
@Slf4j
public class SymbolRateLimitGatewayFilter extends AbstractGatewayFilterFactory<SymbolRateLimitGatewayFilter.Config> {
    
    @Autowired
    private AppGatewayProperties gatewayProperties;
    
    @Autowired(required = false)
    private ReactiveStringRedisTemplate redisTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    public SymbolRateLimitGatewayFilter() {
        super(Config.class);
    }
    
    @Override
    public GatewayFilter apply(Config config) {
        GatewayFilter filter = (exchange, chain) -> {
            if (!gatewayProperties.getRateLimit().isEnabled()) {
                return chain.filter(exchange);
            }
            
            // 只处理下单请求
            String path = exchange.getRequest().getURI().getPath();
            if (!path.contains("/order/") || !exchange.getRequest().getMethod().name().equals("POST")) {
                return chain.filter(exchange);
            }
            
            // 提取 symbol
            return extractSymbol(exchange)
                .flatMap(symbol -> checkSymbolRateLimit(symbol)
                    .flatMap(allowed -> {
                        if (!allowed) {
                            log.warn("[SymbolRateLimit] Rate limit exceeded for symbol: {}", symbol);
                            return rateLimitResponse(exchange.getResponse(), symbol);
                        }
                        return chain.filter(exchange);
                    })
                )
                .switchIfEmpty(chain.filter(exchange)); // 无法提取 symbol，放行
        };
        
        // 设置执行顺序（在认证之后）
        return new OrderedGatewayFilter(filter, 20);
    }
    
    /**
     * 从请求体中提取 symbol
     */
    private Mono<String> extractSymbol(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        
        // 优先从请求头获取（如果前端传递了）
        String symbol = request.getHeaders().getFirst("X-Symbol");
        if (symbol != null && !symbol.isEmpty()) {
            return Mono.just(symbol.toUpperCase());
        }
        
        // 从查询参数获取
        symbol = request.getQueryParams().getFirst("symbol");
        if (symbol != null && !symbol.isEmpty()) {
            return Mono.just(symbol.toUpperCase());
        }
        
        // 从请求体获取（JSON）
        return request.getBody()
            .collectList()
            .flatMap(dataBuffers -> {
                // 合并 DataBuffer
                StringBuilder body = new StringBuilder();
                for (DataBuffer buffer : dataBuffers) {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    body.append(new String(bytes, StandardCharsets.UTF_8));
                    DataBufferUtils.release(buffer);
                }
                
                try {
                    JsonNode jsonNode = objectMapper.readTree(body.toString());
                    if (jsonNode.has("symbol")) {
                        return Mono.just(jsonNode.get("symbol").asText().toUpperCase());
                    }
                } catch (Exception e) {
                    log.debug("[SymbolRateLimit] Failed to parse request body: {}", e.getMessage());
                }
                
                return Mono.empty();
            });
    }
    
    /**
     * 检查交易对限流
     */
    private Mono<Boolean> checkSymbolRateLimit(String symbol) {
        if (redisTemplate == null) {
            return Mono.just(true);
        }
        
        Integer limit = gatewayProperties.getRateLimit().getPerSymbol().get(symbol);
        if (limit == null) {
            return Mono.just(true); // 未配置，允许
        }
        
        String key = "ratelimit:symbol:" + symbol;
        
        return redisTemplate.opsForValue()
            .increment(key)
            .flatMap(count -> {
                if (count == 1) {
                    // 第一次访问，设置过期时间
                    return redisTemplate.expire(key, Duration.ofSeconds(1))
                        .thenReturn(count <= limit);
                }
                return Mono.just(count <= limit);
            })
            .onErrorResume(e -> {
                log.error("[SymbolRateLimit] Redis error: {}", e.getMessage());
                return Mono.just(true); // fail-open
            });
    }
    
    /**
     * 返回限流响应
     */
    private Mono<Void> rateLimitResponse(ServerHttpResponse response, String symbol) {
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        String body = String.format(
            "{\"success\":false,\"code\":429,\"message\":\"Rate limit exceeded for symbol: %s\",\"timestamp\":%d}",
            symbol, System.currentTimeMillis()
        );
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes())));
    }
    
    public static class Config {
        // 配置属性（如果有）
    }
}
