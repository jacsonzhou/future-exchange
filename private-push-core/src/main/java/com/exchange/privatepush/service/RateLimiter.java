package com.exchange.privatepush.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 限流器（令牌桶算法）
 * 
 * 🔥 核心职责：
 * 1. 按用户限流（防止单个用户刷屏）
 * 2. 按IP限流（防止DDoS）
 * 3. 令牌桶算法
 * 
 * 限流策略：
 * - 用户级别：每个用户独立令牌桶
 * - IP级别：每个IP独立令牌桶
 * - 默认：100消息/秒
 * 
 * @author Exchange Team
 * @version 1.0.0
 */
@Slf4j
@Service
public class RateLimiter {

    @Autowired
    private com.exchange.privatepush.config.PrivatePushProperties properties;

    // 用户令牌桶: userId -> TokenBucket
    private final Map<Long, TokenBucket> userBuckets = new ConcurrentHashMap<>();

    // IP令牌桶: ip -> TokenBucket
    private final Map<String, TokenBucket> ipBuckets = new ConcurrentHashMap<>();

    // 定期清理过期bucket，避免内存泄漏
    private ScheduledExecutorService cleanupExecutor;

    @jakarta.annotation.PostConstruct
    public void init() {
        cleanupExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-cleanup");
            t.setDaemon(true);
            return t;
        });

        // 每5分钟清理一次过期bucket
        cleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpiredBuckets,
            5,
            5,
            java.util.concurrent.TimeUnit.MINUTES
        );

        log.info("[RateLimiter] Initialized with cleanup task");
    }

    @jakarta.annotation.PreDestroy
    public void destroy() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
        }
        log.info("[RateLimiter] Destroyed");
    }

    /**
     * 清理超过10分钟未使用的bucket
     */
    private void cleanupExpiredBuckets() {
        long now = System.currentTimeMillis();
        long expireThreshold = 10 * 60 * 1000; // 10分钟

        // 清理用户bucket
        int userCleaned = userBuckets.entrySet().removeIf(entry -> 
            now - entry.getValue().lastRefill.get() > expireThreshold
        ) ? 1 : 0;

        // 清理IP bucket
        int ipCleaned = ipBuckets.entrySet().removeIf(entry -> 
            now - entry.getValue().lastRefill.get() > expireThreshold
        ) ? 1 : 0;

        if (userCleaned > 0 || ipCleaned > 0) {
            log.info("[RateLimiter] Cleaned up expired buckets");
        }
    }
    
    /**
     * 检查是否允许发送消息（用户级别）
     * 
     * @param userId 用户ID
     * @return true=允许，false=限流
     */
    public boolean allowUser(Long userId) {
        int rate = properties.getRateLimit().getMessagesPerSecond();
        TokenBucket bucket = userBuckets.computeIfAbsent(
                userId, k -> new TokenBucket(rate, rate));
        
        return bucket.tryConsume();
    }
    
    /**
     * 检查是否允许发送消息（IP级别）
     * 
     * @param ip IP地址
     * @return true=允许，false=限流
     */
    public boolean allowIp(String ip) {
        int rate = properties.getRateLimit().getMessagesPerSecond();
        TokenBucket bucket = ipBuckets.computeIfAbsent(
                ip, k -> new TokenBucket(rate, rate));
        
        return bucket.tryConsume();
    }
    
    /**
     * 检查是否允许订阅（用户级别）
     * 
     * @param userId 用户ID
     * @return true=允许，false=限流
     */
    public boolean allowSubscribe(Long userId) {
        int rate = properties.getRateLimit().getSubscribePerSecond();
        TokenBucket bucket = userBuckets.computeIfAbsent(
                userId, k -> new TokenBucket(rate, rate));
        
        return bucket.tryConsume();
    }
    
    /**
     * 令牌桶实现（无锁优化版）
     *
     * 性能优化：
     * 1. 使用AtomicLong替代synchronized，提升并发性能
     * 2. 使用CAS操作保证原子性
     * 3. 减少锁竞争，提升吞吐量
     */
    private static class TokenBucket {
        private final long capacity;      // 桶容量
        private final long refillRate;    // 每秒补充速率（每毫秒补充速率 = refillRate / 1000.0）
        private final AtomicLong tokens;     // 当前令牌数（放大1000倍避免浮点运算）
        private final AtomicLong lastRefill; // 上次补充时间

        TokenBucket(long capacity, long refillRate) {
            this.capacity = capacity;
            this.refillRate = refillRate;
            this.tokens = new AtomicLong(capacity * 1000); // 放大1000倍
            this.lastRefill = new AtomicLong(System.currentTimeMillis());
        }

        /**
         * 尝试消费一个令牌（无锁CAS实现）
         *
         * @return true=成功，false=限流
         */
        boolean tryConsume() {
            long now = System.currentTimeMillis();

            // 补充令牌（CAS操作）
            while (true) {
                long lastRefillTime = lastRefill.get();
                long elapsed = now - lastRefillTime;

                if (elapsed <= 0) {
                    break; // 无需补充
                }

                // 计算需要补充的令牌数（放大1000倍）
                long tokensToAdd = elapsed * refillRate;

                if (tokensToAdd > 0) {
                    if (lastRefill.compareAndSet(lastRefillTime, now)) {
                        // 成功更新时间，补充令牌
                        long currentTokens = tokens.get();
                        long newTokens = Math.min(capacity * 1000, currentTokens + tokensToAdd);
                        tokens.set(newTokens);
                        break;
                    }
                    // CAS失败，重试
                } else {
                    break;
                }
            }

            // 消费令牌（CAS操作）
            while (true) {
                long currentTokens = tokens.get();

                if (currentTokens >= 1000) { // 至少有1个令牌（1000 = 1 * 1000）
                    if (tokens.compareAndSet(currentTokens, currentTokens - 1000)) {
                        return true; // 成功消费
                    }
                    // CAS失败，重试
                } else {
                    return false; // 限流
                }
            }
        }
    }
}




