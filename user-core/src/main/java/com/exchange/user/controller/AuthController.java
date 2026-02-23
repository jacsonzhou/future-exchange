package com.exchange.user.controller;

import com.exchange.user.dto.Result;
import com.exchange.user.security.*;
import com.exchange.user.service.client.LedgerClient;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 认证控制器
 * 
 * 提供登录态管理相关接口：
 * 1. Token刷新
 * 2. 登出
 * 3. 多设备管理
 * 4. 查询在线设备
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TokenManager tokenManager;
    private final JwtUtil jwtUtil;

    // ==================== Token 刷新 ====================

    /**
     * 刷新Token
     * 
     * 在Token即将过期时调用，换取新的Token
     * 旧Token会被加入黑名单
     * 
     * @param request 包含旧Token的请求
     * @return 新Token
     */
    @PostMapping("/refresh")
    public Result<RefreshResponse> refreshToken(HttpServletRequest request) {
        String oldToken = extractToken(request);
        
        if (oldToken == null) {
            return Result.error(401, "Missing token");
        }

        try {
            String newToken = tokenManager.refreshToken(oldToken);
            
            RefreshResponse response = new RefreshResponse();
            response.setToken(newToken);
            response.setTokenType("Bearer");
            response.setExpiresIn(jwtUtil.getExpiration() / 1000);
            
            log.info("[AuthController] Token refreshed");
            return Result.success(response);
            
        } catch (RuntimeException e) {
            log.warn("[AuthController] Token refresh failed: {}", e.getMessage());
            return Result.error(401, e.getMessage());
        }
    }

    // ==================== 登出 ====================

    /**
     * 登出（当前设备）
     * 
     * 使当前Token失效
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String token = extractToken(request);
        
        if (token == null) {
            return Result.error(401, "Missing token");
        }

        Long userId = jwtUtil.getUserIdFromToken(token);
        String deviceType = getDeviceTypeFromRequest(request);
        
        tokenManager.invalidateToken(token, userId, deviceType);
        
        log.info("[AuthController] User logged out, userId: {}, device: {}", userId, deviceType);
        return Result.success();
    }

    /**
     * 登出所有设备
     * 
     * 使该用户所有Token失效
     */
    @PostMapping("/logout/all")
    public Result<Void> logoutAll(HttpServletRequest request) {
        String token = extractToken(request);
        
        if (token == null) {
            return Result.error(401, "Missing token");
        }

        Long userId = jwtUtil.getUserIdFromToken(token);
        
        tokenManager.invalidateAllUserTokens(userId);
        
        log.info("[AuthController] User logged out from all devices, userId: {}", userId);
        return Result.success();
    }

    // ==================== 多设备管理 ====================

    /**
     * 查询在线设备
     * 
     * 查看当前所有登录的设备
     */
    @GetMapping("/devices")
    public Result<List<DeviceInfoResponse>> getOnlineDevices(HttpServletRequest request) {
        String token = extractToken(request);
        
        if (token == null) {
            return Result.error(401, "Missing token");
        }

        Long userId = jwtUtil.getUserIdFromToken(token);
        
        Map<Object, Object> tokens = tokenManager.getUserTokens(userId);
        List<DeviceInfoResponse> devices = new ArrayList<>();
        
        tokens.forEach((deviceType, deviceToken) -> {
            DeviceInfoResponse device = new DeviceInfoResponse();
            device.setDeviceType((String) deviceType);
            device.setCurrent(deviceToken.equals(token));
            
            // 获取设备详细信息
            TokenManager.DeviceInfo deviceInfo = getDeviceInfo((String) deviceToken);
            if (deviceInfo != null) {
                device.setDeviceName(deviceInfo.getDeviceName());
                device.setLoginTime(deviceInfo.getLoginTime());
                device.setIp(deviceInfo.getIp());
            }
            
            devices.add(device);
        });
        
        return Result.success(devices);
    }

    /**
     * 踢掉其他设备
     * 
     * 保持当前设备登录，其他设备强制下线
     */
    @PostMapping("/devices/kick-others")
    public Result<Void> kickOtherDevices(HttpServletRequest request) {
        String token = extractToken(request);
        
        if (token == null) {
            return Result.error(401, "Missing token");
        }

        Long userId = jwtUtil.getUserIdFromToken(token);
        String deviceType = getDeviceTypeFromRequest(request);
        
        tokenManager.kickOtherDevices(userId, deviceType);
        
        log.info("[AuthController] Kicked other devices, userId: {}", userId);
        return Result.success();
    }

    /**
     * 踢掉指定设备
     * 
     * @param deviceType 要踢掉的设备类型
     */
    @PostMapping("/devices/kick/{deviceType}")
    public Result<Void> kickDevice(@PathVariable String deviceType, 
                                    HttpServletRequest request) {
        String token = extractToken(request);
        
        if (token == null) {
            return Result.error(401, "Missing token");
        }

        Long userId = jwtUtil.getUserIdFromToken(token);
        
        // 获取该设备的Token
        Map<Object, Object> tokens = tokenManager.getUserTokens(userId);
        String targetToken = (String) tokens.get(deviceType);
        
        if (targetToken != null) {
            tokenManager.invalidateToken(targetToken, userId, deviceType);
        }
        
        log.info("[AuthController] Kicked device: {}, userId: {}", deviceType, userId);
        return Result.success();
    }

    // ==================== 辅助方法 ====================

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    private String getDeviceTypeFromRequest(HttpServletRequest request) {
        // 可以从Header中获取设备类型，或从Token反查
        String deviceType = request.getHeader("X-Device-Type");
        if (deviceType == null) {
            String userAgent = request.getHeader("User-Agent");
            if (userAgent != null) {
                if (userAgent.contains("Mobile")) {
                    return "APP";
                } else {
                    return "WEB";
                }
            }
            return "UNKNOWN";
        }
        return deviceType;
    }

    private TokenManager.DeviceInfo getDeviceInfo(String token) {
        // 这里简化处理，实际可以从Redis获取
        return null;
    }

    // ==================== DTO ====================

    @lombok.Data
    public static class RefreshResponse {
        private String token;
        private String tokenType;
        private Long expiresIn;  // 秒
    }

    @lombok.Data
    public static class DeviceInfoResponse {
        private String deviceType;
        private String deviceName;
        private String ip;
        private Long loginTime;
        private Boolean current;  // 是否是当前设备
    }
}
