package com.exchange.user.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Token 认证过滤器
 * 
 * 职责：
 * 1. 从请求头中提取Token
 * 2. 验证Token有效性（JWT格式 + 未过期 + 不在黑名单）
 * 3. 将用户信息存入ThreadLocal供后续使用
 * 
 * 注意：此过滤器需要在 Gateway 或 user-core 中使用
 * 推荐放在 API Gateway 统一处理
 */
// 注意：认证已在 API Gateway 统一处理，此过滤器已禁用
// @Component
@Slf4j
@Order(1)
@RequiredArgsConstructor
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private final TokenManager tokenManager;
    private final JwtUtil jwtUtil;

    // 不需要认证的路径
    private static final String[] SKIP_PATHS = {
            "/api/v1/user/register",
            "/api/v1/user/login",
            "/api/v1/user/refresh",
            "/actuator/health",
            "/actuator/info"
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                     HttpServletResponse response, 
                                     FilterChain filterChain) throws ServletException, IOException {
        
        String path = request.getRequestURI();
        
        // 1. 跳过不需要认证的路径
        if (shouldSkip(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // 2. 提取Token
        String token = extractToken(request);
        
        if (token == null) {
            log.warn("[TokenFilter] No token found for path: {}", path);
            writeErrorResponse(response, 401, "Missing token");
            return;
        }
        
        // 3. 验证Token
        if (!tokenManager.validateToken(token)) {
            log.warn("[TokenFilter] Invalid or expired token for path: {}", path);
            writeErrorResponse(response, 401, "Invalid or expired token");
            return;
        }
        
        // 4. 提取用户信息存入ThreadLocal
        try {
            Long userId = jwtUtil.getUserIdFromToken(token);
            String username = jwtUtil.getUsernameFromToken(token);
            Long accountId = jwtUtil.getAccountIdFromToken(token);
            
            UserContext.setCurrentUser(userId, username, accountId, token);
            
            log.debug("[TokenFilter] User authenticated: userId={}, path={}", userId, path);
            
            // 继续处理请求
            filterChain.doFilter(request, response);
            
        } finally {
            // 清理ThreadLocal
            UserContext.clear();
        }
    }

    /**
     * 提取Token
     */
    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    /**
     * 检查是否跳过认证
     */
    private boolean shouldSkip(String path) {
        for (String skipPath : SKIP_PATHS) {
            if (path.startsWith(skipPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 写入错误响应
     */
    private void writeErrorResponse(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format(
                "{\"code\":%d,\"message\":\"%s\",\"timestamp\":%d}",
                code, message, System.currentTimeMillis()
        ));
    }
}
