package com.exchange.user.security;

import lombok.Data;

/**
 * 用户上下文
 * 
 * 使用ThreadLocal存储当前请求的用户信息
 * 在任何地方都可以通过 UserContext.getCurrentUser() 获取
 */
public class UserContext {

    private static final ThreadLocal<UserInfo> CURRENT_USER = new ThreadLocal<>();

    /**
     * 设置当前用户
     */
    public static void setCurrentUser(Long userId, String username, Long accountId, String token) {
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(userId);
        userInfo.setUsername(username);
        userInfo.setAccountId(accountId);
        userInfo.setToken(token);
        CURRENT_USER.set(userInfo);
    }

    /**
     * 获取当前用户
     */
    public static UserInfo getCurrentUser() {
        return CURRENT_USER.get();
    }

    /**
     * 获取当前用户ID
     */
    public static Long getCurrentUserId() {
        UserInfo userInfo = CURRENT_USER.get();
        return userInfo != null ? userInfo.getUserId() : null;
    }

    /**
     * 获取当前账户ID
     */
    public static Long getCurrentAccountId() {
        UserInfo userInfo = CURRENT_USER.get();
        return userInfo != null ? userInfo.getAccountId() : null;
    }

    /**
     * 获取当前Token
     */
    public static String getCurrentToken() {
        UserInfo userInfo = CURRENT_USER.get();
        return userInfo != null ? userInfo.getToken() : null;
    }

    /**
     * 清理上下文
     */
    public static void clear() {
        CURRENT_USER.remove();
    }

    /**
     * 检查是否已登录
     */
    public static boolean isAuthenticated() {
        return CURRENT_USER.get() != null;
    }

    @Data
    public static class UserInfo {
        private Long userId;
        private String username;
        private Long accountId;
        private String token;
    }
}
