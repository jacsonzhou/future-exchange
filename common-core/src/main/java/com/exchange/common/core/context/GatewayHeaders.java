package com.exchange.common.core.context;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Gateway 透传 Header 获取工具
 * 
 * 使用场景：
 * - 下游服务（oms-core, ledger-core 等）获取当前登录用户信息
 * - 替代 Token 解析，直接从 Header 读取（Gateway 已验证）
 * 
 * 前置条件：
 * - 请求必须经过 API Gateway (8080)
 * - Gateway 在 AuthFilter 中设置了以下 Header：
 *   - X-User-Id: 用户ID
 *   - X-Account-Id: 账户ID  
 *   - X-Username: 用户名
 * 
 * 使用示例：
 * <pre>
 * Long userId = GatewayHeaders.getUserId();
 * Long accountId = GatewayHeaders.getAccountId();
 * </pre>
 * 
 * @author Exchange Team
 * @since 1.0.0
 */
public class GatewayHeaders {

    // ==================== Header 常量 ====================
    
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_ACCOUNT_ID = "X-Account-Id";
    public static final String HEADER_USERNAME = "X-Username";
    
    // ==================== 获取当前请求 ====================
    
    /**
     * 获取当前 HTTP 请求
     * 
     * @return HttpServletRequest 或 null（非 Web 上下文）
     */
    public static HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes attributes = 
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            return attributes.getRequest();
        }
        return null;
    }
    
    // ==================== 获取用户信息 ====================
    
    /**
     * 获取当前用户ID
     * 
     * @return 用户ID，如果未登录返回 null
     */
    public static Long getUserId() {
        HttpServletRequest request = getCurrentRequest();
        if (request != null) {
            String value = request.getHeader(HEADER_USER_ID);
            if (value != null && !value.isEmpty()) {
                try {
                    return Long.valueOf(value);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }
    
    /**
     * 获取当前账户ID
     * 
     * @return 账户ID，如果未设置返回 null
     */
    public static Long getAccountId() {
        HttpServletRequest request = getCurrentRequest();
        if (request != null) {
            String value = request.getHeader(HEADER_ACCOUNT_ID);
            if (value != null && !value.isEmpty()) {
                try {
                    return Long.valueOf(value);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }
    
    /**
     * 获取当前用户名
     * 
     * @return 用户名，如果未设置返回 null
     */
    public static String getUsername() {
        HttpServletRequest request = getCurrentRequest();
        if (request != null) {
            return request.getHeader(HEADER_USERNAME);
        }
        return null;
    }
    
    /**
     * 获取完整的用户信息
     * 
     * @return UserInfo 对象，如果未登录返回 null
     */
    public static UserInfo getUserInfo() {
        Long userId = getUserId();
        if (userId != null) {
            return new UserInfo(userId, getAccountId(), getUsername());
        }
        return null;
    }
    
    /**
     * 获取用户ID（带默认值）
     * 
     * @param defaultValue 默认值
     * @return 用户ID，如果未登录返回默认值
     */
    public static long getUserIdOrDefault(long defaultValue) {
        Long userId = getUserId();
        return userId != null ? userId : defaultValue;
    }
    
    /**
     * 获取账户ID（带默认值）
     * 
     * @param defaultValue 默认值
     * @return 账户ID，如果未设置返回默认值
     */
    public static long getAccountIdOrDefault(long defaultValue) {
        Long accountId = getAccountId();
        return accountId != null ? accountId : defaultValue;
    }
    
    // ==================== 状态检查 ====================
    
    /**
     * 检查是否已登录
     * 
     * @return true 如果已登录（Header 中有 X-User-Id）
     */
    public static boolean isAuthenticated() {
        return getUserId() != null;
    }
    
    /**
     * 检查是否未登录
     * 
     * @return true 如果未登录
     */
    public static boolean isAnonymous() {
        return getUserId() == null;
    }
    
    /**
     * 要求必须登录，否则抛出异常
     * 
     * @return 用户ID
     * @throws UnauthorizedException 如果未登录
     */
    public static long requireUserId() {
        Long userId = getUserId();
        if (userId == null) {
            throw new UnauthorizedException("User not authenticated");
        }
        return userId;
    }
    
    /**
     * 要求必须有账户ID，否则抛出异常
     * 
     * @return 账户ID
     * @throws UnauthorizedException 如果未设置账户ID
     */
    public static long requireAccountId() {
        Long accountId = getAccountId();
        if (accountId == null) {
            throw new UnauthorizedException("Account not set");
        }
        return accountId;
    }
    
    // ==================== 原始 Header 获取 ====================
    
    /**
     * 获取指定 Header 的值
     * 
     * @param name Header 名称
     * @return Header 值，如果不存在返回 null
     */
    public static String getHeader(String name) {
        HttpServletRequest request = getCurrentRequest();
        if (request != null) {
            return request.getHeader(name);
        }
        return null;
    }
    
    /**
     * 获取指定 Header 的值（带默认值）
     * 
     * @param name Header 名称
     * @param defaultValue 默认值
     * @return Header 值，如果不存在返回默认值
     */
    public static String getHeaderOrDefault(String name, String defaultValue) {
        String value = getHeader(name);
        return value != null ? value : defaultValue;
    }
    
    // ==================== DTO ====================
    
    /**
     * 用户信息封装
     */
    public static class UserInfo {
        private final Long userId;
        private final Long accountId;
        private final String username;
        
        public UserInfo(Long userId, Long accountId, String username) {
            this.userId = userId;
            this.accountId = accountId;
            this.username = username;
        }
        
        public Long getUserId() { return userId; }
        public Long getAccountId() { return accountId; }
        public String getUsername() { return username; }
        
        @Override
        public String toString() {
            return String.format("UserInfo{userId=%d, accountId=%d, username='%s'}", 
                    userId, accountId, username);
        }
    }
    
    // ==================== 异常 ====================
    
    /**
     * 未认证异常
     */
    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) {
            super(message);
        }
    }
}
