package com.exchange.user.service;

import com.exchange.user.dto.*;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户服务接口
 */
public interface UserService {

    /**
     * 用户注册
     * 
     * 流程：
     * 1. 校验用户名是否已存在
     * 2. 创建用户记录
     * 3. 创建交易账户
     * 4. 初始化资金 (调用ledger-core)
     * 
     * @param request 注册请求
     * @return 用户信息
     */
    Result<UserResponse> register(RegisterRequest request);

    /**
     * 用户登录
     * 
     * @param request 登录请求
     * @param httpRequest HTTP请求（获取设备信息）
     * @return 用户信息（包含JWT Token）
     */
    Result<UserResponse> login(LoginRequest request, HttpServletRequest httpRequest);

    /**
     * 获取当前用户信息
     * 
     * @param userId 用户ID
     * @return 用户信息
     */
    Result<UserResponse> getCurrentUser(Long userId);

    /**
     * 获取用户默认交易账户
     * 
     * @param userId 用户ID
     * @return 交易账户信息
     */
    Result<TradingAccountResponse> getDefaultAccount(Long userId);
}
