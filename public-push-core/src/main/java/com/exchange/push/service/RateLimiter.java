package com.exchange.push.service;

import com.exchange.push.config.PublicPushProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多层限流器
 * 
 * 限流层级：
 * 1. IP限流 - 防止单IP过多连接
 * 2. Session限流 - 单连接消息频率
 * 3. 频道限流 - 热点频道保护
 */
@Slf4j
@Service
public class RateLimiter {

    @Autowired
    private PublicPushProperties properties;

    // IP连接限流器
    private Cache<String, TokenBucket> ipConnectionLimiters;
    
    // Session消息限流器
    private Cache<String, TokenBucket> sessionMessageLimiters;
    
    // 频道消息限流器
    private Cache<String, TokenBucket> channelMessageLimiters;

    @PostConstruct
    public void init() {
        // IP限流：每分钟最多N次连接
        ipConnectionLimiters = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();
        
        // Session消息限流
        sessionMessageLimiters = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();
        
        // 频道消息限流
        channelMessageLimiters = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();
    }

    /**
     * 检查IP是否允许新建连接
     */
    public boolean allowIpConnection(String ip) {
        if (!properties.getRateLimit().isEnabled() || 
            !properties.getRateLimit().getIpConnection().isEnabled()) {
            return true;
        }
        
        int maxConnections = properties.getRateLimit().getIpConnection().getMaxConnections();
        double ratePerMinute = properties.getRateLimit().getIpConnection().getRatePerMinute();
        
        TokenBucket bucket = ipConnectionLimiters.get(ip, 
            k -> new TokenBucket(ratePerMinute, maxConnections));
        
        return bucket.tryAcquire();
    }

    /**
     * 检查Session是否允许发送消息
     */
    public boolean allowSessionMessage(String sessionId) {
        if (!properties.getRateLimit().isEnabled() || 
            !properties.getRateLimit().getSessionMessage().isEnabled()) {
            return true;
        }
        
        int maxPerSecond = properties.getRateLimit().getSessionMessage().getMaxMessagesPerSecond();
        int maxPerMinute = properties.getRateLimit().getSessionMessage().getMaxMessagesPerMinute();
        
        TokenBucket bucket = sessionMessageLimiters.get(sessionId, 
            k -> new TokenBucket(maxPerSecond, maxPerMinute));
        
        return bucket.tryAcquire();
    }

    /**
     * 检查频道是否允许发送消息
     */
    public boolean allowChannelMessage(String channel) {
        if (!properties.getRateLimit().isEnabled() || 
            !properties.getRateLimit().getChannel().isEnabled()) {
            return true;
        }
        
        int maxPerSecond = properties.getRateLimit().getChannel().getMaxMessagesPerSecond();
        
        TokenBucket bucket = channelMessageLimiters.get(channel, 
            k -> new TokenBucket(maxPerSecond, maxPerSecond * 60));
        
        return bucket.tryAcquire();
    }

    /**
     * Token Bucket 限流算法实现（无锁 CAS）
     */
    private static class TokenBucket {
        private static final long SCALE = 1_000_000L; // 6位小数精度
        
        private final double tokensPerSecond;
        private final double maxTokens;
        
        private final AtomicLong tokensScaled;
        private final AtomicLong lastRefillTime;

        TokenBucket(double tokensPerSecond, double maxTokens) {
            this.tokensPerSecond = tokensPerSecond;
            this.maxTokens = maxTokens;
            this.tokensScaled = new AtomicLong((long) (maxTokens * SCALE));
            this.lastRefillTime = new AtomicLong(System.nanoTime());
        }

        boolean tryAcquire() {
            refill();
            
            while (true) {
                long current = tokensScaled.get();
                if (current < SCALE) {
                    return false;
                }
                if (tokensScaled.compareAndSet(current, current - SCALE)) {
                    return true;
                }
            }
        }

        private void refill() {
            long now = System.nanoTime();
            long lastRefill = lastRefillTime.get();
            long elapsedNanos = now - lastRefill;
            
            if (elapsedNanos <= 0) {
                return;
            }
            
            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
            long tokensToAdd = (long) (elapsedSeconds * tokensPerSecond * SCALE);
            
            if (tokensToAdd <= 0) {
                return;
            }
            
            long maxTokensScaled = (long) (maxTokens * SCALE);
            
            while (true) {
                long current = tokensScaled.get();
                long newTokens = Math.min(maxTokensScaled, current + tokensToAdd);
                
                if (tokensScaled.compareAndSet(current, newTokens)) {
                    lastRefillTime.compareAndSet(lastRefill, now);
                    return;
                }
                
                // CAS 失败，重新读取时间并计算
                lastRefill = lastRefillTime.get();
                elapsedNanos = now - lastRefill;
                if (elapsedNanos <= 0) {
                    return;
                }
                elapsedSeconds = elapsedNanos / 1_000_000_000.0;
                tokensToAdd = (long) (elapsedSeconds * tokensPerSecond * SCALE);
            }
        }
    }
}
