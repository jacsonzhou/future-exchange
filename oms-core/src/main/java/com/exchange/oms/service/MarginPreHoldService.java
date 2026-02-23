package com.exchange.oms.service;

import com.exchange.common.core.Money;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 保证金预扣服务
 * 
 * 🔥 核心职责：
 * 1. 下单时预扣保证金（防止并发超卖）
 * 2. 成交/撤单时释放预扣
 * 3. OMS重启时恢复预扣状态
 * 
 * 🔥 设计要点：
 * - 使用 Redis 原子操作保证并发安全
 * - Lua 脚本保证"检查+扣减"原子性
 * - 设置过期时间防止死锁
 * 
 * 对标：Binance / OKX / Bybit级别
 */
@Slf4j
@Service
public class MarginPreHoldService {
    
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    
    /**
     * Redis Key 前缀
     */
    private static final String PRE_HOLD_TOTAL_KEY = "oms:margin:prehold:total:%d";      // 用户总预扣
    private static final String PRE_HOLD_ORDER_KEY = "oms:margin:prehold:order:%d:%d";    // 订单预扣明细
    private static final String PRE_HOLD_ORDERS_SET = "oms:margin:prehold:orders:%d";     // 用户预扣订单集合
    
    /**
     * 预扣过期时间（秒）- 1小时
     * 防止 OMS 崩溃导致预扣无法释放
     */
    private static final long PRE_HOLD_EXPIRE_SECONDS = 3600;
    
    /**
     * Lua 脚本：原子性预扣保证金
     * 
     * KEYS[1]: 用户总预扣 key
     * KEYS[2]: 订单预扣明细 key
     * KEYS[3]: 用户预扣订单集合 key
     * ARGV[1]: 预扣金额
     * ARGV[2]: 用户总余额
     * ARGV[3]: 订单ID
     * ARGV[4]: 过期时间（秒）
     * 
     * 返回：1=成功, 0=余额不足, -1=订单已存在
     */
    private static final String PRE_HOLD_LUA_SCRIPT = 
        "local currentHold = redis.call('GET', KEYS[1]) or '0';" +
        "local orderKey = KEYS[2];" +
        "if redis.call('EXISTS', orderKey) == 1 then " +
        "  return -1;" +  // 订单已存在
        "end;" +
        "local newHold = tonumber(currentHold) + tonumber(ARGV[1]);" +
        "if newHold > tonumber(ARGV[2]) then " +
        "  return 0;" +  // 余额不足
        "end;" +
        "redis.call('SET', KEYS[1], tostring(newHold));" +
        "redis.call('SET', orderKey, ARGV[1]);" +
        "redis.call('EXPIRE', orderKey, ARGV[4]);" +
        "redis.call('SADD', KEYS[3], ARGV[3]);" +
        "redis.call('EXPIRE', KEYS[3], ARGV[4]);" +
        "return 1;";
    
    /**
     * Lua 脚本：释放预扣
     * 
     * KEYS[1]: 用户总预扣 key
     * KEYS[2]: 订单预扣明细 key
     * KEYS[3]: 用户预扣订单集合 key
     * 
     * 返回：实际释放的金额（long）
     */
    private static final String RELEASE_LUA_SCRIPT = 
        "local heldAmount = redis.call('GET', KEYS[2]) or '0';" +
        "if tonumber(heldAmount) > 0 then " +
        "  redis.call('DECRBY', KEYS[1], heldAmount);" +
        "  redis.call('DEL', KEYS[2]);" +
        "  redis.call('SREM', KEYS[3], KEYS[4]);" +
        "  return heldAmount;" +
        "end;" +
        "return '0';";
    
    /**
     * Lua 脚本：查询总预扣金额
     */
    private static final String GET_TOTAL_HOLD_LUA_SCRIPT = 
        "return redis.call('GET', KEYS[1]) or '0';";
    
    /**
     * 预扣保证金
     * 
     * @param userId 用户ID
     * @param orderId 订单ID
     * @param margin 预扣金额（单位：分）
     * @param totalBalance 用户总余额（单位：分）
     * @return 预扣结果
     */
    public PreHoldResult preHold(Long userId, Long orderId, long margin, long totalBalance) {
        if (margin <= 0) {
            return PreHoldResult.fail("Margin must be positive");
        }
        if (totalBalance <= 0) {
            return PreHoldResult.fail("Insufficient balance");
        }
        
        String totalKey = String.format(PRE_HOLD_TOTAL_KEY, userId);
        String orderKey = String.format(PRE_HOLD_ORDER_KEY, userId, orderId);
        String ordersSetKey = String.format(PRE_HOLD_ORDERS_SET, userId);
        
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(PRE_HOLD_LUA_SCRIPT);
        redisScript.setResultType(Long.class);
        
        List<String> keys = Arrays.asList(totalKey, orderKey, ordersSetKey);
        
        try {
            Long result = stringRedisTemplate.execute(
                redisScript,
                keys,
                String.valueOf(margin),
                String.valueOf(totalBalance),
                String.valueOf(orderId),
                String.valueOf(PRE_HOLD_EXPIRE_SECONDS)
            );
            
            if (result == null) {
                log.error("[PreHold] Redis script returned null, userId={}, orderId={}", userId, orderId);
                return PreHoldResult.fail("System error");
            }
            
            if (result == 1) {
                log.info("[PreHold] ✅ Success, userId={}, orderId={}, margin={}, totalBalance={}", 
                    userId, orderId, Money.format(margin), Money.format(totalBalance));
                return PreHoldResult.success(margin);
            } else if (result == 0) {
                long currentHold = getTotalPreHold(userId);
                log.warn("[PreHold] ❌ Insufficient balance, userId={}, orderId={}, margin={}, " +
                    "totalBalance={}, currentHold={}, available={}", 
                    userId, orderId, Money.format(margin), Money.format(totalBalance), 
                    Money.format(currentHold), Money.format(totalBalance - currentHold));
                return PreHoldResult.fail("Insufficient available margin");
            } else if (result == -1) {
                log.warn("[PreHold] ⚠️ Order already exists, userId={}, orderId={}", userId, orderId);
                return PreHoldResult.fail("Order pre-hold already exists");
            } else {
                log.error("[PreHold] ❌ Unknown result, userId={}, orderId={}, result={}", userId, orderId, result);
                return PreHoldResult.fail("Unknown error");
            }
            
        } catch (Exception e) {
            log.error("[PreHold] ❌ Redis error, userId={}, orderId={}", userId, orderId, e);
            return PreHoldResult.fail("Redis error: " + e.getMessage());
        }
    }
    
    /**
     * 释放预扣保证金
     * 
     * @param userId 用户ID
     * @param orderId 订单ID
     * @return 实际释放的金额
     */
    public long releasePreHold(Long userId, Long orderId) {
        String totalKey = String.format(PRE_HOLD_TOTAL_KEY, userId);
        String orderKey = String.format(PRE_HOLD_ORDER_KEY, userId, orderId);
        String ordersSetKey = String.format(PRE_HOLD_ORDERS_SET, userId);
        
        DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(RELEASE_LUA_SCRIPT);
        redisScript.setResultType(String.class);
        
        List<String> keys = Arrays.asList(totalKey, orderKey, ordersSetKey, String.valueOf(orderId));
        
        try {
            String result = stringRedisTemplate.execute(redisScript, keys);
            long released = result != null ? Long.parseLong(result) : 0;
            
            if (released > 0) {
                log.info("[PreHold] ✅ Released, userId={}, orderId={}, amount={}", 
                    userId, orderId, Money.format(released));
            } else {
                log.debug("[PreHold] ⚠️ No pre-hold to release, userId={}, orderId={}", userId, orderId);
            }
            
            return released;
            
        } catch (Exception e) {
            log.error("[PreHold] ❌ Release error, userId={}, orderId={}", userId, orderId, e);
            return 0;
        }
    }
    
    /**
     * 获取用户总预扣金额
     * 
     * @param userId 用户ID
     * @return 预扣金额（单位：分）
     */
    public long getTotalPreHold(Long userId) {
        String totalKey = String.format(PRE_HOLD_TOTAL_KEY, userId);
        
        try {
            String value = stringRedisTemplate.opsForValue().get(totalKey);
            return value != null ? Long.parseLong(value) : 0;
        } catch (Exception e) {
            log.error("[PreHold] ❌ Get total hold error, userId={}", userId, e);
            return 0;
        }
    }
    
    /**
     * 获取订单预扣金额
     * 
     * @param userId 用户ID
     * @param orderId 订单ID
     * @return 预扣金额（单位：分）
     */
    public long getOrderPreHold(Long userId, Long orderId) {
        String orderKey = String.format(PRE_HOLD_ORDER_KEY, userId, orderId);
        
        try {
            String value = stringRedisTemplate.opsForValue().get(orderKey);
            return value != null ? Long.parseLong(value) : 0;
        } catch (Exception e) {
            log.error("[PreHold] ❌ Get order hold error, userId={}, orderId={}", userId, orderId, e);
            return 0;
        }
    }
    
    /**
     * 获取用户所有预扣订单
     * 
     * @param userId 用户ID
     * @return 订单ID列表
     */
    public List<Long> getPreHoldOrderIds(Long userId) {
        String ordersSetKey = String.format(PRE_HOLD_ORDERS_SET, userId);
        
        try {
            var members = stringRedisTemplate.opsForSet().members(ordersSetKey);
            if (members == null) {
                return Collections.emptyList();
            }
            return members.stream()
                .map(Long::parseLong)
                .toList();
        } catch (Exception e) {
            log.error("[PreHold] ❌ Get order ids error, userId={}", userId, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 强制清理用户所有预扣（用于测试或紧急恢复）
     * 
     * @param userId 用户ID
     */
    public void clearAllPreHold(Long userId) {
        String totalKey = String.format(PRE_HOLD_TOTAL_KEY, userId);
        String ordersSetKey = String.format(PRE_HOLD_ORDERS_SET, userId);
        
        try {
            // 获取所有订单ID
            List<Long> orderIds = getPreHoldOrderIds(userId);
            
            // 删除所有订单预扣
            for (Long orderId : orderIds) {
                String orderKey = String.format(PRE_HOLD_ORDER_KEY, userId, orderId);
                stringRedisTemplate.delete(orderKey);
            }
            
            // 删除总预扣和订单集合
            stringRedisTemplate.delete(totalKey);
            stringRedisTemplate.delete(ordersSetKey);
            
            log.warn("[PreHold] ⚠️ Cleared all pre-hold for userId={}, orderCount={}", userId, orderIds.size());
            
        } catch (Exception e) {
            log.error("[PreHold] ❌ Clear all error, userId={}", userId, e);
        }
    }
    
    /**
     * 计算实际可用余额 = 总余额 - 预扣
     * 
     * @param totalBalance 总余额
     * @param userId 用户ID
     * @return 实际可用余额
     */
    public long calculateAvailableBalance(long totalBalance, Long userId) {
        long preHold = getTotalPreHold(userId);
        return Math.max(0, totalBalance - preHold);
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 预扣结果
     */
    public static class PreHoldResult {
        private final boolean success;
        private final long amount;
        private final String message;
        
        private PreHoldResult(boolean success, long amount, String message) {
            this.success = success;
            this.amount = amount;
            this.message = message;
        }
        
        public static PreHoldResult success(long amount) {
            return new PreHoldResult(true, amount, "Success");
        }
        
        public static PreHoldResult fail(String message) {
            return new PreHoldResult(false, 0, message);
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public long getAmount() {
            return amount;
        }
        
        public String getMessage() {
            return message;
        }
        
        @Override
        public String toString() {
            return "PreHoldResult{" +
                "success=" + success +
                ", amount=" + amount +
                ", message='" + message + '\'' +
                '}';
        }
    }
}
