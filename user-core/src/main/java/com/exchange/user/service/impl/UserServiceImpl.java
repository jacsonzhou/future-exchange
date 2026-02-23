package com.exchange.user.service.impl;

import com.exchange.user.dto.*;
import com.exchange.user.entity.TradingAccount;
import com.exchange.user.entity.User;
import com.exchange.user.mapper.TradingAccountMapper;
import com.exchange.user.mapper.UserMapper;
import com.exchange.user.security.JwtUtil;
import com.exchange.user.service.UserService;
import com.exchange.user.service.client.LedgerClient;
import com.exchange.user.security.TokenManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;

import java.math.BigDecimal;

/**
 * 用户服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final TradingAccountMapper accountMapper;
    private final LedgerClient ledgerClient;
    private final JwtUtil jwtUtil;
    private final TokenManager tokenManager;

    @Value("${initial.funding.enabled:true}")
    private Boolean initialFundingEnabled;

    @Value("${initial.funding.amount:100000}")
    private BigDecimal initialFundingAmount;

    @Value("${initial.funding.asset:USDT}")
    private String initialFundingAsset;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<UserResponse> register(RegisterRequest request) {
        log.info("[UserService] Starting user registration, username: {}", request.getUsername());

        // 1. 检查用户名是否已存在
        if (userMapper.countByUsername(request.getUsername()) > 0) {
            log.warn("[UserService] Username already exists: {}", request.getUsername());
            return Result.error(400, "用户名已存在");
        }

        // 2. 创建用户
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPasswordHash(BCrypt.hashpw(request.getPassword(), BCrypt.gensalt()));
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setStatus(1);  // 正常状态
        user.setUserType("RETAIL");  // 零售用户
        user.setRegisterIp(request.getRegisterIp());
        
        userMapper.insert(user);
        log.info("[UserService] User created, userId: {}", user.getId());

        // 3. 创建交易账户
        TradingAccount account = new TradingAccount();
        account.setUserId(user.getId());
        account.setAccountType("STANDARD");
        account.setMarginMode("CROSS");  // 默认全仓
        account.setDefaultLeverage(10);  // 默认10倍杠杆
        account.setStatus(1);
        account.setFunded(false);
        
        accountMapper.insert(account);
        log.info("[UserService] Trading account created, accountId: {}", account.getId());

        // 4. 初始化资金
        if (initialFundingEnabled) {
            try {
                initUserFunding(user.getId(), account.getId());
                accountMapper.markAsFunded(account.getId());
                log.info("[UserService] Initial funding completed for accountId: {}", account.getId());
            } catch (Exception e) {
                log.error("[UserService] Failed to initialize funding, accountId: {}", account.getId(), e);
                // 资金初始化失败不影响注册成功，用户可以后续再申请
            }
        }

        // 5. 构建响应
        UserResponse response = new UserResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setPhone(user.getPhone());
        response.setStatus(user.getStatus());
        response.setUserType(user.getUserType());
        response.setCreatedAt(user.getCreatedAt());
        response.setAccountId(account.getId());

        log.info("[UserService] User registration completed, userId: {}, accountId: {}", 
                user.getId(), account.getId());
        return Result.success(response);
    }

    /**
     * 初始化用户资金
     * 
     * 调用ledger-core创建初始资金分录
     */
    private void initUserFunding(Long userId, Long accountId) {
        log.info("[UserService] Initializing funding for userId: {}, accountId: {}, amount: {} {}", 
                userId, accountId, initialFundingAmount, initialFundingAsset);

        LedgerClient.InitialFundingRequest request = new LedgerClient.InitialFundingRequest(
                accountId,
                userId,
                initialFundingAsset,
                initialFundingAmount,
                "INITIAL_FUNDING"
        );

        Result<LedgerClient.InitialFundingResponse> result = ledgerClient.createInitialFunding(request);
        
        if (result.getCode() != 200) {
            throw new RuntimeException("Failed to create initial funding: " + result.getMessage());
        }

        log.info("[UserService] Funding initialized, entryId: {}, finalBalance: {}",
                result.getData().entryId, result.getData().finalBalance);
    }

    @Override
    public Result<UserResponse> login(LoginRequest request, HttpServletRequest httpRequest) {
        log.info("[UserService] User login attempt, username: {}", request.getUsername());

        // 1. 查询用户
        User user = userMapper.selectByUsername(request.getUsername());
        if (user == null) {
            log.warn("[UserService] User not found: {}", request.getUsername());
            return Result.error(401, "用户名或密码错误");
        }

        // 2. 验证密码
        if (!BCrypt.checkpw(request.getPassword(), user.getPasswordHash())) {
            log.warn("[UserService] Invalid password for user: {}", request.getUsername());
            return Result.error(401, "用户名或密码错误");
        }

        // 3. 检查用户状态
        if (user.getStatus() != 1) {
            log.warn("[UserService] User account disabled: {}", request.getUsername());
            return Result.error(403, "账户已被禁用");
        }

        // 4. 更新登录信息
        user.setLastLoginTime(java.time.LocalDateTime.now());
        user.setLastLoginIp(request.getLoginIp());
        userMapper.updateById(user);

        // 5. 查询默认账户
        TradingAccount account = accountMapper.selectDefaultByUserId(user.getId());

        // 6. 生成JWT Token
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), 
                account != null ? account.getId() : null);

        // 7. 存储Token到Redis（分布式会话）
        String deviceType = getDeviceType(httpRequest);
        TokenManager.DeviceInfo deviceInfo = buildDeviceInfo(httpRequest);
        tokenManager.storeToken(user.getId(), token, deviceType, deviceInfo);

        // 8. 构建响应
        UserResponse response = new UserResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setPhone(user.getPhone());
        response.setStatus(user.getStatus());
        response.setUserType(user.getUserType());
        response.setLastLoginTime(user.getLastLoginTime());
        response.setCreatedAt(user.getCreatedAt());
        response.setAccountId(account != null ? account.getId() : null);
        response.setToken(token);

        log.info("[UserService] User login success, userId: {}, deviceType: {}", user.getId(), deviceType);
        return Result.success(response);
    }

    /**
     * 获取设备类型
     */
    private String getDeviceType(HttpServletRequest request) {
        String deviceType = request.getHeader("X-Device-Type");
        if (deviceType != null) {
            return deviceType;
        }
        
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null) {
            if (userAgent.contains("Mobile") || userAgent.contains("Android") || userAgent.contains("iPhone")) {
                return "APP";
            }
            return "WEB";
        }
        return "API";
    }

    /**
     * 构建设备信息
     */
    private TokenManager.DeviceInfo buildDeviceInfo(HttpServletRequest request) {
        TokenManager.DeviceInfo info = new TokenManager.DeviceInfo();
        info.setDeviceType(getDeviceType(request));
        info.setDeviceId(request.getHeader("X-Device-Id"));
        info.setDeviceName(request.getHeader("X-Device-Name"));
        info.setIp(request.getRemoteAddr());
        info.setUserAgent(request.getHeader("User-Agent"));
        info.setLoginTime(System.currentTimeMillis());
        return info;
    }

    @Override
    public Result<UserResponse> getCurrentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.error(404, "用户不存在");
        }

        TradingAccount account = accountMapper.selectDefaultByUserId(userId);

        UserResponse response = new UserResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setPhone(user.getPhone());
        response.setStatus(user.getStatus());
        response.setUserType(user.getUserType());
        response.setLastLoginTime(user.getLastLoginTime());
        response.setCreatedAt(user.getCreatedAt());
        response.setAccountId(account != null ? account.getId() : null);

        return Result.success(response);
    }

    @Override
    public Result<TradingAccountResponse> getDefaultAccount(Long userId) {
        TradingAccount account = accountMapper.selectDefaultByUserId(userId);
        if (account == null) {
            return Result.error(404, "交易账户不存在");
        }

        TradingAccountResponse response = new TradingAccountResponse();
        response.setAccountId(account.getId());
        response.setUserId(account.getUserId());
        response.setAccountType(account.getAccountType());
        response.setMarginMode(account.getMarginMode());
        response.setDefaultLeverage(account.getDefaultLeverage());
        response.setStatus(account.getStatus());
        response.setFunded(account.getFunded());
        response.setCreatedAt(account.getCreatedAt());

        return Result.success(response);
    }
}
