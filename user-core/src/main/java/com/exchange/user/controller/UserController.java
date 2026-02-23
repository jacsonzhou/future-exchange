package com.exchange.user.controller;

import com.exchange.user.dto.*;
import com.exchange.user.security.JwtUtil;
import com.exchange.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 用户控制器
 * 
 * 提供用户注册、登录、查询等接口
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    /**
     * 用户注册
     * 
     * 注册成功后：
     * 1. 创建用户
     * 2. 创建交易账户
     * 3. 自动注入初始资金（100,000 USDT）
     * 
     * @param request 注册请求
     * @param servletRequest HTTP请求（获取IP）
     * @return 用户信息
     */
    @PostMapping("/register")
    public Result<UserResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest servletRequest) {
        
        // 设置注册IP
        request.setRegisterIp(getClientIp(servletRequest));
        
        log.info("[UserController] Register request, username: {}", request.getUsername());
        return userService.register(request);
    }

    /**
     * 用户登录
     * 
     * @param request 登录请求
     * @param servletRequest HTTP请求（获取IP）
     * @return 用户信息（包含JWT Token）
     */
    @PostMapping("/login")
    public Result<UserResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        
        // 设置登录IP
        request.setLoginIp(getClientIp(servletRequest));
        
        log.info("[UserController] Login request, username: {}", request.getUsername());
        return userService.login(request, servletRequest);
    }

    /**
     * 获取当前用户信息
     * 
     * @param token JWT Token
     * @return 用户信息
     */
    @GetMapping("/me")
    public Result<UserResponse> getCurrentUser(
            @RequestHeader("Authorization") String token) {
        
        // 去掉Bearer前缀
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        // 验证Token
        if (!jwtUtil.validateToken(token)) {
            return Result.error(401, "无效的Token");
        }

        Long userId = jwtUtil.getUserIdFromToken(token);
        log.info("[UserController] Get current user, userId: {}", userId);
        
        return userService.getCurrentUser(userId);
    }

    /**
     * 获取用户默认交易账户
     * 
     * @param token JWT Token
     * @return 交易账户信息
     */
    @GetMapping("/account")
    public Result<TradingAccountResponse> getDefaultAccount(
            @RequestHeader("Authorization") String token) {
        
        // 去掉Bearer前缀
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        // 验证Token
        if (!jwtUtil.validateToken(token)) {
            return Result.error(401, "无效的Token");
        }

        Long userId = jwtUtil.getUserIdFromToken(token);
        log.info("[UserController] Get default account, userId: {}", userId);
        
        return userService.getDefaultAccount(userId);
    }

    /**
     * 获取客户端IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 如果有多个IP，取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
