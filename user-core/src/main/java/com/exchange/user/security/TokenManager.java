package com.exchange.user.security;

import com.alibaba.fastjson2.JSON;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 分布式 Token 管理器
 * 
 * 职责：
 * 1. 管理用户登录Token（多设备支持）
 * 2. Token黑名单（强制登出）
 * 3. Token续期刷新
 * 
 * Redis Key设计：
 * - user:tokens:{userId}      -> Hash {deviceType: token}
 * - token:blacklist:{token}   -> String (TTL=JWT剩余时间)
 * - token:refresh:{token}     -> String userId (用于刷新)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenManager {

    private final StringRedisTemplate redisTemplate;
    private final JwtUtil jwtUtil;

    // ==================== Key 常量 ====================
    
    private static final String USER_TOKENS_KEY = "user:tokens:%d";
    private static final String TOKEN_BLACKLIST_KEY = "token:blacklist:%s";
    private static final String TOKEN_REFRESH_KEY = "token:refresh:%s";
    private static final String DEVICE_INFO_KEY = "device:info:%s";

    // ==================== 登录时：存储Token ====================

    /**
     * 存储用户登录Token
     * 
     * @param userId 用户ID
     * @param token JWT Token
     * @param deviceType 设备类型 (WEB/APP/API)
     * @param deviceInfo 设备信息
     */
    public void storeToken(Long userId, String token, String deviceType, DeviceInfo deviceInfo) {
        String userTokensKey = String.format(USER_TOKENS_KEY, userId);
        
        // 1. 存储Token到用户Token列表 (支持多设备)
        redisTemplate.opsForHash().put(userTokensKey, deviceType, token);
        
        // 2. 设置Token有效期（与JWT一致）
        long ttl = jwtUtil.getExpiration() / 1000; // 秒
        redisTemplate.expire(userTokensKey, ttl, TimeUnit.SECONDS);
        
        // 3. 存储设备信息
        String deviceKey = String.format(DEVICE_INFO_KEY, token);
        redisTemplate.opsForValue().set(deviceKey, JSON.toJSONString(deviceInfo), ttl, TimeUnit.SECONDS);
        
        // 4. 存储刷新映射
        String refreshKey = String.format(TOKEN_REFRESH_KEY, token);
        redisTemplate.opsForValue().set(refreshKey, String.valueOf(userId), ttl, TimeUnit.SECONDS);
        
        log.info("[TokenManager] Token stored for userId: {}, deviceType: {}, ttl: {}s", 
                userId, deviceType, ttl);
    }

    /**
     * 获取用户所有登录设备
     */
    public java.util.Map<Object, Object> getUserTokens(Long userId) {
        String userTokensKey = String.format(USER_TOKENS_KEY, userId);
        return redisTemplate.opsForHash().entries(userTokensKey);
    }

    // ==================== 验证时：检查Token ====================

    /**
     * 验证Token是否有效
     * 
     * 1. JWT格式正确且未过期
     * 2. 不在黑名单中
     */
    public boolean validateToken(String token) {
        // 1. JWT基础验证
        if (!jwtUtil.validateToken(token)) {
            return false;
        }
        
        // 2. 检查黑名单
        if (isTokenBlacklisted(token)) {
            log.warn("[TokenManager] Token is blacklisted");
            return false;
        }
        
        return true;
    }

    /**
     * 检查Token是否在黑名单
     */
    public boolean isTokenBlacklisted(String token) {
        String blacklistKey = String.format(TOKEN_BLACKLIST_KEY, token);
        return Boolean.TRUE.equals(redisTemplate.hasKey(blacklistKey));
    }

    // ==================== 登出时：失效Token ====================

    /**
     * 登出：使Token失效
     * 
     * @param token JWT Token
     * @param userId 用户ID
     * @param deviceType 设备类型
     */
    public void invalidateToken(String token, Long userId, String deviceType) {
        // 1. 从用户Token列表中移除
        String userTokensKey = String.format(USER_TOKENS_KEY, userId);
        redisTemplate.opsForHash().delete(userTokensKey, deviceType);
        
        // 2. 加入黑名单（TTL设为JWT剩余时间）
        long remainingTime = jwtUtil.getRemainingTime(token);
        if (remainingTime > 0) {
            String blacklistKey = String.format(TOKEN_BLACKLIST_KEY, token);
            redisTemplate.opsForValue().set(blacklistKey, "1", remainingTime, TimeUnit.MILLISECONDS);
        }
        
        // 3. 删除设备信息
        String deviceKey = String.format(DEVICE_INFO_KEY, token);
        redisTemplate.delete(deviceKey);
        
        // 4. 删除刷新映射
        String refreshKey = String.format(TOKEN_REFRESH_KEY, token);
        redisTemplate.delete(refreshKey);
        
        log.info("[TokenManager] Token invalidated for userId: {}, deviceType: {}", userId, deviceType);
    }

    /**
     * 强制登出：使某用户所有Token失效
     * 
     * @param userId 用户ID
     */
    public void invalidateAllUserTokens(Long userId) {
        String userTokensKey = String.format(USER_TOKENS_KEY, userId);
        java.util.Map<Object, Object> tokens = redisTemplate.opsForHash().entries(userTokensKey);
        
        tokens.forEach((deviceType, token) -> {
            invalidateToken((String) token, userId, (String) deviceType);
        });
        
        // 删除整个用户Token列表
        redisTemplate.delete(userTokensKey);
        
        log.info("[TokenManager] All tokens invalidated for userId: {}", userId);
    }

    /**
     * 强制单设备登录：踢掉其他设备
     * 
     * @param userId 用户ID
     * @param keepDeviceType 保留的设备类型
     */
    public void kickOtherDevices(Long userId, String keepDeviceType) {
        String userTokensKey = String.format(USER_TOKENS_KEY, userId);
        java.util.Map<Object, Object> tokens = redisTemplate.opsForHash().entries(userTokensKey);
        
        tokens.forEach((deviceType, token) -> {
            if (!deviceType.equals(keepDeviceType)) {
                invalidateToken((String) token, userId, (String) deviceType);
            }
        });
        
        log.info("[TokenManager] Kicked other devices for userId: {}, keep: {}", userId, keepDeviceType);
    }

    // ==================== Token 刷新 ====================

    /**
     * 刷新Token
     * 
     * @param oldToken 旧Token
     * @return 新Token
     */
    public String refreshToken(String oldToken) {
        // 1. 验证旧Token有效
        if (!jwtUtil.validateToken(oldToken)) {
            throw new RuntimeException("Invalid token");
        }
        
        // 2. 检查是否在黑名单
        if (isTokenBlacklisted(oldToken)) {
            throw new RuntimeException("Token has been revoked");
        }
        
        // 3. 获取用户信息
        Long userId = jwtUtil.getUserIdFromToken(oldToken);
        String username = jwtUtil.getUsernameFromToken(oldToken);
        Long accountId = jwtUtil.getAccountIdFromToken(oldToken);
        
        // 4. 生成新Token
        String newToken = jwtUtil.generateToken(userId, username, accountId);
        
        // 5. 旧Token加入黑名单（短TTL）
        long remainingTime = jwtUtil.getRemainingTime(oldToken);
        if (remainingTime > 0) {
            String blacklistKey = String.format(TOKEN_BLACKLIST_KEY, oldToken);
            redisTemplate.opsForValue().set(blacklistKey, "1", remainingTime, TimeUnit.MILLISECONDS);
        }
        
        // 6. 存储新Token
        String deviceType = getDeviceTypeFromToken(oldToken);
        DeviceInfo deviceInfo = getDeviceInfo(oldToken);
        storeToken(userId, newToken, deviceType, deviceInfo);
        
        log.info("[TokenManager] Token refreshed for userId: {}", userId);
        return newToken;
    }

    // ==================== 辅助方法 ====================

    private String getDeviceTypeFromToken(String token) {
        // 从Redis查找设备类型
        Long userId = jwtUtil.getUserIdFromToken(token);
        String userTokensKey = String.format(USER_TOKENS_KEY, userId);
        java.util.Map<Object, Object> entries = redisTemplate.opsForHash().entries(userTokensKey);
        
        for (java.util.Map.Entry<Object, Object> entry : entries.entrySet()) {
            if (token.equals(entry.getValue())) {
                return (String) entry.getKey();
            }
        }
        return "UNKNOWN";
    }

    private DeviceInfo getDeviceInfo(String token) {
        String deviceKey = String.format(DEVICE_INFO_KEY, token);
        String json = redisTemplate.opsForValue().get(deviceKey);
        if (json != null) {
            return JSON.parseObject(json, DeviceInfo.class);
        }
        return new DeviceInfo();
    }

    @Data
    public static class DeviceInfo {
        private String deviceType;      // WEB/APP/API
        private String deviceId;        // 设备ID
        private String deviceName;      // 设备名称
        private String ip;              // IP地址
        private String userAgent;       // User-Agent
        private Long loginTime;         // 登录时间
    }
}
