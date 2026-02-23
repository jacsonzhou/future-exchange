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
     * Token Bucket 限流算法实现
     */
    private static class TokenBucket {
        private final double tokensPerSecond;
        private final double maxTokens;
        
        private final AtomicDouble tokens;
        private final AtomicLong lastRefillTime;

        TokenBucket(double tokensPerSecond, double maxTokens) {
            this.tokensPerSecond = tokensPerSecond;
            this.maxTokens = maxTokens;
            this.tokens = new AtomicDouble(maxTokens);
            this.lastRefillTime = new AtomicLong(System.nanoTime());
        }

        synchronized boolean tryAcquire() {
            refill();
            
            double currentTokens = tokens.get();
            if (currentTokens >= 1.0) {
                tokens.addAndGet(-1.0);
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            long lastRefill = lastRefillTime.get();
            double elapsedSeconds = (now - lastRefill) / 1_000_000_000.0;
            
            if (elapsedSeconds > 0) {
                double tokensToAdd = elapsedSeconds * tokensPerSecond;
                double newTokens = Math.min(maxTokens, tokens.get() + tokensToAdd);
                tokens.set(newTokens);
                lastRefillTime.set(now);
            }
        }
    }

    /**
     * 原子Double（简化实现）
     */
    private static class AtomicDouble {
        private volatile double value;

        AtomicDouble(double initialValue) {
            this.value = initialValue;
        }

        double get() {
            return value;
        }

        void set(double newValue) {
            this.value = newValue;
        }

        double addAndGet(double delta) {
            synchronized (this) {
                value += delta;
                return value;
            }
        }
    }
}
