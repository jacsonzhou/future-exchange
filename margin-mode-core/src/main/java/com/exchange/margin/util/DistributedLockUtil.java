package com.exchange.margin.util;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁工具类
 *
 * 基于Redisson实现的分布式锁，用于关键业务操作
 */
@Slf4j
@Component
public class DistributedLockUtil {

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 锁前缀
     */
    private static final String LOCK_PREFIX = "margin:lock:";

    /**
     * 默认等待时间（秒）
     */
    private static final long DEFAULT_WAIT_TIME = 5;

    /**
     * 默认持有时间（秒）
     */
    private static final long DEFAULT_LEASE_TIME = 10;

    /**
     * 执行带锁的操作
     *
     * @param lockKey 锁的key（不含前缀）
     * @param supplier 需要执行的操作
     * @param <T> 返回值类型
     * @return 操作结果
     */
    public <T> T executeWithLock(String lockKey, Supplier<T> supplier) {
        return executeWithLock(lockKey, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, supplier);
    }

    /**
     * 执行带锁的操作（自定义等待和持有时间）
     *
     * @param lockKey 锁的key（不含前缀）
     * @param waitTime 等待时间（秒）
     * @param leaseTime 持有时间（秒）
     * @param supplier 需要执行的操作
     * @param <T> 返回值类型
     * @return 操作结果
     */
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime,
                                  Supplier<T> supplier) {
        String fullLockKey = LOCK_PREFIX + lockKey;
        RLock lock = redissonClient.getLock(fullLockKey);

        try {
            // 尝试获取锁
            boolean acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);

            if (!acquired) {
                log.warn("[DistributedLock] Failed to acquire lock, lockKey={}", lockKey);
                throw new RuntimeException("操作繁忙，请稍后重试");
            }

            log.debug("[DistributedLock] Lock acquired, lockKey={}", lockKey);

            // 执行业务逻辑
            return supplier.get();

        } catch (InterruptedException e) {
            log.error("[DistributedLock] Lock interrupted, lockKey={}", lockKey, e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("操作被中断");
        } finally {
            // 释放锁（只释放自己持有的锁）
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("[DistributedLock] Lock released, lockKey={}", lockKey);
            }
        }
    }

    /**
     * 执行带锁的操作（无返回值）
     *
     * @param lockKey 锁的key（不含前缀）
     * @param runnable 需要执行的操作
     */
    public void executeWithLock(String lockKey, Runnable runnable) {
        executeWithLock(lockKey, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 生成用户级锁key
     *
     * @param userId 用户ID
     * @return 锁key
     */
    public static String getUserLockKey(Long userId) {
        return "user:" + userId;
    }

    /**
     * 生成仓位级锁key
     *
     * @param positionId 仓位ID
     * @return 锁key
     */
    public static String getPositionLockKey(Long positionId) {
        return "position:" + positionId;
    }

    /**
     * 生成用户Symbol级锁key
     *
     * @param userId 用户ID
     * @param symbol 交易对
     * @return 锁key
     */
    public static String getUserSymbolLockKey(Long userId, String symbol) {
        return "user:" + userId + ":symbol:" + symbol;
    }
}
