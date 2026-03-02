package com.exchange.gateway.filter;

import com.exchange.common.core.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * 认证网关过滤器（响应式）
 * 
 * 职责：
 * 1. JWT Token 校验
 * 2. Redis 黑名单检查
 * 3. 用户身份透传到下游服务（通过 Header）
 * 
 * 执行顺序：Ordered.HIGHEST_PRECEDENCE + 10（在 Trace 之后）
 */
@Slf4j
@Component
public class AuthGatewayFilter implements GlobalFilter, Ordered {
    
    @Autowired(required = false)
    private ReactiveStringRedisTemplate reactiveRedisTemplate;
    
    @Value("${jwt.secret:future-exchange-user-core-secret-key-2024}")
    private String jwtSecret;
    
    @Value("${jwt.expiration:86400000}")
    private Long jwtExpiration;
    
    @Value("${gateway.auth.enabled:true}")
    private boolean authEnabled;
    
    private JwtUtil jwtUtil;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_ACCOUNT_ID = "X-Account-Id";
    private static final String HEADER_USERNAME = "X-Username";
    private static final String HEADER_USER_ROLE = "X-User-Role";
    
    @PostConstruct
    public void init() {
        this.jwtUtil = new JwtUtil(jwtSecret, jwtExpiration);
        log.info("[AuthGatewayFilter] Initialized, authEnabled={}", authEnabled);
    }
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        
        log.info("[AuthGatewayFilter] Request path: {}, authEnabled={}", path, authEnabled);
        
        // 如果认证未启用，直接放行
        if (!authEnabled) {
            log.info("[AuthGatewayFilter] Auth disabled, passing through");
            return chain.filter(exchange);
        }
        
        // 检查白名单
        if (isWhitelistPath(path)) {
            log.info("[AuthGatewayFilter] Whitelist path matched: {}", path);
            // 白名单路径也尝试透传 X-User-Id（从 query 参数或 header 中提取）
            String userId = request.getHeaders().getFirst(HEADER_USER_ID);
            if (userId == null) {
                userId = request.getQueryParams().getFirst("userId");
            }
            if (userId != null && !userId.isEmpty()) {
                ServerHttpRequest mutatedRequest = request.mutate()
                    .header(HEADER_USER_ID, userId)
                    .build();
                return chain.filter(exchange.mutate().request(mutatedRequest).build());
            }
            return chain.filter(exchange);
        }
        log.info("[AuthGatewayFilter] Path not in whitelist: {}", path);
        
        // 提取并验证 Token
        String token = extractToken(request);
        log.info("[AuthGatewayFilter] Extracted token: {}", token != null ? token.substring(0, 30) + "..." : "null");
        if (token == null) {
            log.warn("[AuthGatewayFilter] No token provided, path: {}", path);
            return unauthorized(exchange.getResponse(), "Authentication required");
        }
        
        // 验证 JWT
        boolean valid = jwtUtil.validateToken(token);
        log.info("[AuthGatewayFilter] Token validation result: {}", valid);
        if (!valid) {
            log.warn("[AuthGatewayFilter] Invalid token, path: {}", path);
            return unauthorized(exchange.getResponse(), "Invalid or expired token");
        }
        
        // 检查黑名单
        return checkTokenBlacklist(token)
            .flatMap(isBlacklisted -> {
                if (isBlacklisted) {
                    log.warn("[AuthGatewayFilter] Token is blacklisted, path: {}", path);
                    return unauthorized(exchange.getResponse(), "Token has been revoked");
                }
                
                // 解析用户信息
                Long userId = jwtUtil.getUserIdFromToken(token);
                String username = jwtUtil.getUsernameFromToken(token);
                Long accountId = jwtUtil.getAccountIdFromToken(token);
                
                log.debug("[AuthGatewayFilter] Auth success, userId={}, path={}", userId, path);
                
                // 透传用户信息到下游服务（保留原始 Authorization header）
                ServerHttpRequest mutatedRequest = request.mutate()
                    .header(HEADER_USER_ID, String.valueOf(userId))
                    .header(HEADER_ACCOUNT_ID, accountId != null ? String.valueOf(accountId) : "")
                    .header(HEADER_USERNAME, username != null ? username : "")
                    .header(HEADER_USER_ROLE, "TRADER")
                    .header(HEADER_AUTHORIZATION, "Bearer " + token)
                    .build();
                
                return chain.filter(exchange.mutate().request(mutatedRequest).build());
            })
            .doOnError(e -> log.error("[AuthGatewayFilter] Auth processing error, path: {}", path, e));
    }
    
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10; // 在 Trace 过滤器之后
    }
    
    /**
     * 提取 Token
     */
    private String extractToken(ServerHttpRequest request) {
        List<String> authHeaders = request.getHeaders().get(HEADER_AUTHORIZATION);
        if (authHeaders == null || authHeaders.isEmpty()) {
            return null;
        }
        String bearerToken = authHeaders.get(0);
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
    
    /**
     * 检查 Token 是否在黑名单
     */
    private Mono<Boolean> checkTokenBlacklist(String token) {
        if (reactiveRedisTemplate == null) {
            return Mono.just(false);
        }
        String key = "token:blacklist:" + token;
        return reactiveRedisTemplate.hasKey(key);
    }
    
    /**
     * 检查白名单路径
     */
    private boolean isWhitelistPath(String path) {
        // 从配置读取白名单
        List<String> whitelistPaths = getWhitelistPaths();
        for (String pattern : whitelistPaths) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 获取白名单路径
     */
    private List<String> getWhitelistPaths() {
        // 可以从配置中心动态加载
        return List.of(
            "/api/v1/user/register",
            "/api/v1/user/login",
            // "/api/v1/account/balance/**",  // ❌ 账户余额查询需要认证
            // "/api/v1/position/**",         // ❌ 持仓查询需要认证
            "/api/v1/oms/order/list",        // ✅ OMS 订单列表查询
            "/api/v1/market/**",
            "/api/v1/ticker/**",
            "/api/v1/depth/**",
            "/api/v1/kline/**",
            "/api/v1/klines/**",           // ✅ K线数据接口（复数形式）
            "/api/v1/bookTicker/**",       // ✅ 最优盘口接口
            "/api/binance/**",             // ✅ Binance外部行情公开接口
            "/api/v1/match/orderbook/**",  // 🔥 盘口数据公开接口（无需认证）
            "/actuator/health",
            "/actuator/info",
            "/ws/market/**",
            "/fallback/**"
        );
    }
    
    /**
     * 返回 401 未授权响应
     */
    private Mono<Void> unauthorized(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        String body = String.format(
            "{\"code\":401,\"message\":\"%s\",\"timestamp\":%d}",
            message, System.currentTimeMillis()
        );
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes())));
    }
}
