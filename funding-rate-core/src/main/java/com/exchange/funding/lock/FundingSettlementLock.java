package com.exchange.funding.lock;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 资金费率结算分布式锁
 */
@Slf4j
@Component
public class FundingSettlementLock {

    @Autowired(required = false)
    private RedissonClient redissonClient;

    @Value("${funding-rate.lock.enabled:true}")
    private Boolean lockEnabled;

    @Value("${funding-rate.lock.timeout:60000}")
    private Long lockTimeout;

    private static final String LOCK_PREFIX = "funding:settlement:lock:";

    /**
     * 尝试获取结算锁
     *
     * @param symbol 交易对
     * @param fundingTime 结算时间
     * @return 是否成功获取锁
     */
    public boolean tryLock(String symbol, Long fundingTime) {
        if (!lockEnabled || redissonClient == null) {
            log.warn("Distributed lock is disabled or Redisson client not available");
            return true; // 降级：不使用锁
        }

        String lockKey = LOCK_PREFIX + symbol + ":" + fundingTime;
        try {
            RLock lock = redissonClient.getLock(lockKey);
            boolean locked = lock.tryLock(0, lockTimeout, TimeUnit.MILLISECONDS);
            if (locked) {
                log.info("Acquired settlement lock for symbol={}, fundingTime={}", symbol, fundingTime);
                return true;
            } else {
                log.warn("Failed to acquire settlement lock for symbol={}, fundingTime={}", symbol, fundingTime);
                return false;
            }
        } catch (Exception e) {
            log.error("Error acquiring settlement lock for symbol={}, fundingTime={}", symbol, fundingTime, e);
            return true; // 降级：锁异常时继续执行
        }
    }

    /**
     * 释放结算锁
     *
     * @param symbol 交易对
     * @param fundingTime 结算时间
     */
    public void unlock(String symbol, Long fundingTime) {
        if (!lockEnabled || redissonClient == null) {
            return;
        }

        String lockKey = LOCK_PREFIX + symbol + ":" + fundingTime;
        try {
            RLock lock = redissonClient.getLock(lockKey);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("Released settlement lock for symbol={}, fundingTime={}", symbol, fundingTime);
            }
        } catch (Exception e) {
            log.error("Error releasing settlement lock for symbol={}, fundingTime={}", symbol, fundingTime, e);
        }
    }

    /**
     * 执行带锁的结算任务
     *
     * @param symbol 交易对
     * @param fundingTime 结算时间
     * @param task 结算任务
     */
    public void executeWithLock(String symbol, Long fundingTime, Runnable task) {
        if (!tryLock(symbol, fundingTime)) {
            log.warn("Settlement already in progress for symbol={}, fundingTime={}, skip", symbol, fundingTime);
            return;
        }

        try {
            task.run();
        } finally {
            unlock(symbol, fundingTime);
        }
    }
}
