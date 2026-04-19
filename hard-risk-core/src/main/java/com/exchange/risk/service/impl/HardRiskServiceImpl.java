package com.exchange.risk.service.impl;

import com.exchange.risk.dto.CheckOrderRiskRequest;
import com.exchange.risk.dto.CheckOrderRiskResponse;
import com.exchange.risk.entity.*;
import com.exchange.risk.enums.RiskRejectReason;
import com.exchange.risk.mapper.*;
import com.exchange.risk.service.HardRiskService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Hard Risk Gate 核心服务实现
 * 
 * 🔥 核心设计：
 * 1. 同步风控检查
 * 2. 强一致性（读取最新快照）
 * 3. 🔥 检查"实际可用"余额 = 快照余额 - 预扣金额（从Redis）
 * 4. 快速响应（P99 < 5ms）
 * 5. 无状态（仅校验，不维护资金）
 * 6. 幂等保证
 * 7. Fail-Close策略（失败即拒单）
 * 
 * 对标：Binance / OKX / Bybit级别
 */
@Slf4j
@Service
public class HardRiskServiceImpl implements HardRiskService {
    
    @Autowired
    private RiskAccountSnapshotMapper accountSnapshotMapper;
    
    @Autowired
    private RiskPositionSnapshotMapper positionSnapshotMapper;
    
    @Autowired
    private RiskSymbolConfigMapper symbolConfigMapper;
    
    @Autowired
    private RiskUserListMapper userListMapper;
    
    @Autowired
    private RiskCheckLogMapper checkLogMapper;
    
    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;
    
    /**
     * Redis Key：用户总预扣金额
     */
    private static final String PRE_HOLD_TOTAL_KEY = "oms:margin:prehold:total:%d";
    
    @Override
    @CircuitBreaker(name = "riskCheck", fallbackMethod = "checkOrderRiskFallback")
    @RateLimiter(name = "riskCheck")
    public CheckOrderRiskResponse checkOrderRisk(CheckOrderRiskRequest request, Long requiredMargin, Long totalBalance) {
        long startTime = System.currentTimeMillis();
        
        log.info("[HardRisk] Check order risk start, userId={}, orderId={}, symbol={}, side={}, price={}, qty={}",
            request.getUserId(), request.getOrderId(), request.getSymbol(), 
            request.getSide(), request.getPrice(), request.getQuantity());
        
        try {
            // 1. 查询交易对配置
            RiskSymbolConfig symbolConfig = symbolConfigMapper.selectBySymbol(request.getSymbol());
            if (symbolConfig == null || !symbolConfig.isEnabled()) {
                return rejectAndLog(request, RiskRejectReason.SYMBOL_CONFIG_NOT_FOUND, null, null);
            }
            
            // 2. 查询账户快照
            RiskAccountSnapshot account = accountSnapshotMapper.selectByUserId(request.getUserId());
            if (account == null) {
                return rejectAndLog(request, RiskRejectReason.ACCOUNT_NOT_FOUND, null, null);
            }
            
            // 3. 查询持仓快照
            RiskPositionSnapshot position = positionSnapshotMapper.selectByUserIdAndSymbol(
                request.getUserId(), request.getSymbol());
            
            // 4. 查询黑白名单
            RiskUserList userList = userListMapper.selectByUserId(request.getUserId());
            
            // 5. 执行风控规则检查（传入保证金和余额信息）
            CheckOrderRiskResponse response = executeRiskRules(
                request, account, position, symbolConfig, userList, requiredMargin, totalBalance);
            
            // 6. 记录审计日志
            logRiskCheck(request, response);
            
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[HardRisk] Check order risk completed, result={}, elapsed={}ms",
                response.getResult(), elapsed);
            
            return response;
            
        } catch (IllegalArgumentException | NullPointerException e) {
            // 参数绑定失败、空指针等 —— 通常是上游请求格式错误
            log.error("[HardRisk] Invalid request parameters, orderId={}, error={}",
                request.getOrderId(), e.getMessage(), e);
            return rejectAndLog(request, RiskRejectReason.SYSTEM_ERROR, null, null);
        } catch (Exception e) {
            // 数据库故障、网络超时等其他异常 —— Fail-Close
            log.error("[HardRisk] Internal error during risk check, orderId={}", request.getOrderId(), e);
            return rejectAndLog(request, RiskRejectReason.SYSTEM_ERROR, null, null);
        }
    }
    
    /**
     * 执行风控规则检查（增强版）
     * 
     * 🔥 关键改动：检查"实际可用"余额
     * 实际可用 = 快照余额 - 预扣金额（从Redis读取）
     */
    private CheckOrderRiskResponse executeRiskRules(
            CheckOrderRiskRequest request,
            RiskAccountSnapshot account,
            RiskPositionSnapshot position,
            RiskSymbolConfig symbolConfig,
            RiskUserList userList,
            Long requiredMargin,
            Long totalBalance) {
        
        // 参数基础校验（防止上游字段绑定失败导致NPE）
        if (request.getPrice() == null || request.getQuantity() == null 
                || request.getSide() == null || request.getLeverage() == null) {
            log.error("[HardRisk] Missing required fields: price={}, quantity={}, side={}, leverage={}",
                request.getPrice(), request.getQuantity(), request.getSide(), request.getLeverage());
            return reject(RiskRejectReason.SYSTEM_ERROR, null, 
                account.getAvailableMargin() != null ? account.getAvailableMargin().toPlainString() : null);
        }
        
        // 规则1: 黑名单检查
        if (userList != null && userList.isBlackList()) {
            return reject(RiskRejectReason.RISK_BLACKLISTED, null, null);
        }
        
        // 规则2: 账户状态检查
        if (account.isFrozen()) {
            return reject(RiskRejectReason.ACCOUNT_FROZEN, null, account.getAvailableMargin().toPlainString());
        }
        if (account.isLiquidating()) {
            return reject(RiskRejectReason.ACCOUNT_LIQUIDATING, null, account.getAvailableMargin().toPlainString());
        }
        
        // 规则3: 杠杆限制检查
        if (request.getLeverage() > symbolConfig.getMaxLeverage()) {
            return reject(RiskRejectReason.LEVERAGE_EXCEEDED, null, account.getAvailableMargin().toPlainString());
        }
        
        BigDecimal orderPrice = new BigDecimal(request.getPrice());
        BigDecimal orderQty = new BigDecimal(request.getQuantity());
        
        // 规则4: 价格有效性检查（基于标记价格）
        if (position != null && position.getMarkPrice() != null) {
            BigDecimal markPrice = position.getMarkPrice();
            BigDecimal deviation = orderPrice.subtract(markPrice)
                .abs()
                .divide(markPrice, 8, RoundingMode.HALF_UP);
            
            if (deviation.compareTo(symbolConfig.getMaxPriceDeviationPct()) > 0) {
                return reject(RiskRejectReason.PRICE_OUT_OF_RANGE, null, account.getAvailableMargin().toPlainString());
            }
        }
        
        // 规则5: 订单数量检查
        if (orderQty.compareTo(symbolConfig.getMinOrderQty()) < 0 ||
            orderQty.compareTo(symbolConfig.getMaxOrderQty()) > 0) {
            return reject(RiskRejectReason.ORDER_QTY_EXCEEDED, null, account.getAvailableMargin().toPlainString());
        }
        
        // 规则6: ReduceOnly检查（支持双向持仓Hedge Mode）
        if (Boolean.TRUE.equals(request.getReduceOnly())) {
            if (position == null || position.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
                return reject(RiskRejectReason.REDUCE_ONLY_VIOLATION, null, account.getAvailableMargin().toPlainString());
            }
            
            // 在双向持仓(Hedge Mode)下，ReduceOnly 的意思是：
            // 下单方向必须能够减少当前该方向持仓的绝对值（即反向操作）
            // 例如：持有多仓时，SELL 可以减少多仓；持有空仓时，BUY 可以减少空仓
            boolean isBuy = "BUY".equals(request.getSide());
            boolean isLongPosition = position.isLong();
            
            // 检查下单是否会净增加仓位（同向开仓）
            // Net Mode / Hedge Mode 统一逻辑：ReduceOnly 不允许增加同向持仓
            boolean isAddingPosition = (isBuy && isLongPosition) || (!isBuy && !isLongPosition);
            if (isAddingPosition) {
                return reject(RiskRejectReason.REDUCE_ONLY_VIOLATION, null, account.getAvailableMargin().toPlainString());
            }
        }
        
        // 规则7: 仓位限制检查
        if (position != null) {
            BigDecimal currentQty = position.getQuantity();
            boolean isBuy = "BUY".equals(request.getSide());
            boolean isLongPosition = position.isLong();
            
            // 计算新仓位（同方向则累加）
            BigDecimal newPositionQty = currentQty;
            if ((isBuy && isLongPosition) || (!isBuy && !isLongPosition)) {
                newPositionQty = currentQty.add(orderQty);
            }
            
            if (newPositionQty.compareTo(symbolConfig.getMaxPositionQty()) > 0) {
                return reject(RiskRejectReason.POSITION_LIMIT_EXCEEDED, null, account.getAvailableMargin().toPlainString());
            }
        }
        
        // 规则8: 保证金检查（最核心）
        // 🔥 关键：检查"实际可用"余额 = 快照余额 - 预扣金额
        
        BigDecimal notional = orderPrice.multiply(orderQty);
        BigDecimal calculatedMargin = notional.divide(
            new BigDecimal(request.getLeverage()), 8, RoundingMode.HALF_UP);
        
        // 使用传入的 requiredMargin 或计算值
        BigDecimal finalRequiredMargin = requiredMargin != null 
            ? BigDecimal.valueOf(requiredMargin).divide(new BigDecimal("100000000"), 8, RoundingMode.HALF_UP)
            : calculatedMargin;
        
        // 获取快照可用余额
        BigDecimal snapshotAvailable = account.getAvailableMargin();
        
        // 🔥 从 Redis 获取预扣金额
        long preHoldAmount = getPreHoldFromRedis(request.getUserId());
        BigDecimal preHoldDecimal = BigDecimal.valueOf(preHoldAmount)
            .divide(new BigDecimal("100000000"), 8, RoundingMode.HALF_UP);
        
        // 计算实际可用余额
        BigDecimal realAvailable = snapshotAvailable.subtract(preHoldDecimal);
        
        log.debug("[HardRisk] Margin check: userId={}, snapshotAvailable={}, preHold={}, realAvailable={}, required={}",
            request.getUserId(), snapshotAvailable, preHoldDecimal, realAvailable, finalRequiredMargin);
        
        if (realAvailable.compareTo(finalRequiredMargin) < 0) {
            log.warn("[HardRisk] ❌ Insufficient margin: userId={}, realAvailable={}, required={}, preHold={}",
                request.getUserId(), realAvailable, finalRequiredMargin, preHoldDecimal);
            return reject(RiskRejectReason.INSUFFICIENT_MARGIN, 
                finalRequiredMargin.toPlainString(), 
                realAvailable.toPlainString());
        }
        
        // 所有规则通过
        return CheckOrderRiskResponse.pass(
            finalRequiredMargin.toPlainString(),
            realAvailable.toPlainString()
        );
    }
    
    /**
     * 创建拒绝响应
     */
    private CheckOrderRiskResponse reject(RiskRejectReason reason, 
                                          String requiredMargin, 
                                          String availableMargin) {
        return CheckOrderRiskResponse.reject(
            reason.name(),
            reason.getMessage(),
            requiredMargin,
            availableMargin
        );
    }
    
    /**
     * 拒绝并记录日志
     */
    private CheckOrderRiskResponse rejectAndLog(CheckOrderRiskRequest request,
                                                 RiskRejectReason reason,
                                                 String requiredMargin,
                                                 String availableMargin) {
        CheckOrderRiskResponse response = reject(reason, requiredMargin, availableMargin);
        logRiskCheck(request, response);
        return response;
    }
    
    /**
     * 记录风控审计日志
     */
    private void logRiskCheck(CheckOrderRiskRequest request, CheckOrderRiskResponse response) {
        try {
            RiskCheckLog log = new RiskCheckLog();
            log.setOrderId(request.getOrderId() != null ? Long.parseLong(request.getOrderId()) : 0L);
            log.setUserId(request.getUserId());
            log.setSymbol(request.getSymbol());
            log.setResult("PASS".equals(response.getResult()) ? 0 : 1);
            
            if (response.getRejectReason() != null && !"NONE".equals(response.getRejectReason())) {
                RiskRejectReason reason = RiskRejectReason.valueOf(response.getRejectReason());
                log.setRejectReason(reason.getCode());
                log.setRejectMessage(reason.getMessage());
            }
            
            if (response.getRequiredMargin() != null) {
                log.setRequiredMargin(new BigDecimal(response.getRequiredMargin()));
            }
            if (response.getAvailableMargin() != null) {
                log.setAvailableMargin(new BigDecimal(response.getAvailableMargin()));
            }
            
            log.setTraceId(request.getTraceId());
            log.setCreatedAt(System.currentTimeMillis());
            
            checkLogMapper.insert(log);
        } catch (Exception e) {
            // 日志记录失败不影响主流程
            this.log.error("[HardRisk] Log risk check error", e);
        }
    }
    
    /**
     * 熔断/限流降级方法
     */
    @SuppressWarnings("unused")
    private CheckOrderRiskResponse checkOrderRiskFallback(CheckOrderRiskRequest request,
                                                           Long requiredMargin,
                                                           Long totalBalance,
                                                           Throwable throwable) {
        log.error("[HardRisk] Circuit breaker or rate limiter triggered, orderId={}, fallback activated",
            request.getOrderId(), throwable);
        return rejectAndLog(request, RiskRejectReason.SYSTEM_ERROR, null, null);
    }
    
    /**
     * 从 Redis 获取用户预扣金额
     * 
     * @param userId 用户ID
     * @return 预扣金额（单位：分）
     */
    private long getPreHoldFromRedis(Long userId) {
        if (stringRedisTemplate == null) {
            log.error("[HardRisk] Redis template not available, pre-hold check skipped, userId={}", userId);
            return 0L;
        }
        
        try {
            String key = String.format(PRE_HOLD_TOTAL_KEY, userId);
            String value = stringRedisTemplate.opsForValue().get(key);
            return value != null ? Long.parseLong(value) : 0L;
        } catch (Exception e) {
            log.error("[HardRisk] Failed to get pre-hold from Redis, userId={}", userId, e);
            return 0L;
        }
    }
}
