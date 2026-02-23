package com.exchange.user.controller;

import com.exchange.common.core.context.GatewayHeaders;
import com.exchange.user.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户信息查询接口（演示从 Gateway Header 获取用户信息）
 * 
 * 这些接口需要从 API Gateway 转发，因为依赖 Gateway 设置的 Header
 * 
 * 使用 common-core 提供的 GatewayHeaders 工具类获取用户信息
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/userinfo")
public class UserInfoController {

    /**
     * 获取当前登录用户信息（从 Gateway Header）
     * 
     * 说明：
     * - 此接口需要从 API Gateway (8080) 转发
     * - Gateway 会在 Header 中放入用户信息
     * - user-core 从 Header 读取，无需再次解析 Token
     * 
     * @return 用户信息
     */
    @GetMapping("/from-gateway")
    public Result<UserInfoFromGateway> getUserInfoFromGateway() {
        // 使用 common-core 提供的 GatewayHeaders 工具类
        // 从 Gateway 透传的 Header 获取用户信息
        Long userId = GatewayHeaders.getUserId();
        Long accountId = GatewayHeaders.getAccountId();
        String username = GatewayHeaders.getUsername();
        
        log.info("[UserInfoController] Get user info from gateway headers: userId={}, accountId={}", 
                userId, accountId);
        
        if (userId == null) {
            // 如果直接访问 user-core (8099)，没有 Gateway Header
            return Result.error(401, "Unauthorized - Please access through API Gateway (8080)");
        }
        
        UserInfoFromGateway info = new UserInfoFromGateway();
        info.setUserId(userId);
        info.setAccountId(accountId);
        info.setUsername(username);
        info.setSource("API Gateway Header (via GatewayHeaders)");
        
        return Result.success(info);
    }

    /**
     * 获取完整用户信息（使用 GatewayHeaders.UserInfo）
     */
    @GetMapping("/full")
    public Result<GatewayHeaders.UserInfo> getFullUserInfo() {
        GatewayHeaders.UserInfo userInfo = GatewayHeaders.getUserInfo();
        
        if (userInfo == null) {
            return Result.error(401, "Not authenticated");
        }
        
        log.info("[UserInfoController] Full user info: {}", userInfo);
        return Result.success(userInfo);
    }

    /**
     * 检查登录状态
     */
    @GetMapping("/check")
    public Result<LoginStatus> checkLoginStatus() {
        boolean isAuthenticated = GatewayHeaders.isAuthenticated();
        
        LoginStatus status = new LoginStatus();
        status.setAuthenticated(isAuthenticated);
        
        if (isAuthenticated) {
            status.setUserId(GatewayHeaders.getUserId());
            status.setAccountId(GatewayHeaders.getAccountId());
            status.setMessage("User is logged in");
        } else {
            status.setMessage("User is not logged in - Please access through API Gateway (8080)");
        }
        
        return Result.success(status);
    }

    /**
     * 强制要求登录示例
     */
    @GetMapping("/require")
    public Result<String> requireLogin() {
        try {
            long userId = GatewayHeaders.requireUserId();
            long accountId = GatewayHeaders.requireAccountId();
            return Result.success("Authenticated: userId=" + userId + ", accountId=" + accountId);
        } catch (GatewayHeaders.UnauthorizedException e) {
            return Result.error(401, e.getMessage());
        }
    }

    // ==================== DTO ====================

    @lombok.Data
    public static class UserInfoFromGateway {
        private Long userId;
        private Long accountId;
        private String username;
        private String source;
    }

    @lombok.Data
    public static class LoginStatus {
        private boolean authenticated;
        private Long userId;
        private Long accountId;
        private String message;
    }
}
