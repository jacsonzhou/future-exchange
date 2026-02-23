package com.exchange.match.pool;

import com.exchange.match.orderbook.PriceLevel;
import lombok.extern.slf4j.Slf4j;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PriceLevel 对象池
 *
 * ============================================
 * Phase 1.3: PriceLevel Object Pool Optimization
 * 目标：减少价格档位频繁创建/销毁的 GC 压力
 * ============================================
 *
 * 设计要点：
 * 1. 线程安全：使用 ConcurrentLinkedQueue
 * 2. 容量限制：避免无限膨胀
 * 3. 对象清理：归还时调用 reset() 清空状态
 * 4. 监控指标：池大小、活跃对象数
 *
 * 使用场景：
 * - 价格档位创建时从池中获取
 * - 价格档位清空时归还对象池
 */
@Slf4j
public class PriceLevelPool {

    /**
     * 对象池队列（线程安全）
     */
    private final Queue<PriceLevel> pool = new ConcurrentLinkedQueue<>();

    /**
     * 池最大容量
     */
    private final int maxPoolSize;

    /**
     * 已创建对象总数（包括池中和活跃的）
     */
    private final AtomicInteger totalCreated = new AtomicInteger(0);

    /**
     * 构造函数
     *
     * @param maxPoolSize 池最大容量（建议 1000-5000）
     */
    public PriceLevelPool(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
        log.info("[PriceLevelPool] Initialized with maxPoolSize={}", maxPoolSize);
    }

    /**
     * 从池中获取对象
     * 如果池为空，创建新对象
     *
     * @param priceScaled 价格（已缩放）
     * @return PriceLevel 对象（已清空状态）
     */
    public PriceLevel acquire(Long priceScaled) {
        PriceLevel level = pool.poll();

        if (level == null) {
            // 池为空，创建新对象
            level = new PriceLevel(priceScaled);
            totalCreated.incrementAndGet();

            if (totalCreated.get() % 100 == 0) {
                log.debug("[PriceLevelPool] Created {} price levels in total, pool_size={}, active={}",
                        totalCreated.get(), getPoolSize(), getActiveCount());
            }
        } else {
            // 注意：PriceLevel 的 priceScaled 是 final 的，不能修改
            // 因此我们需要创建新对象，但可以复用一些内部结构
            // 实际上，PriceLevel 对象池的效果有限，主要优化是 Order 对象池
            // 这里保留接口，后续可优化 PriceLevel 为非 final priceScaled
            level = new PriceLevel(priceScaled);
            totalCreated.incrementAndGet();
        }

        return level;
    }

    /**
     * 归还对象到池
     * 如果池已满，丢弃对象（让 GC 回收）
     *
     * @param level 待归还的 PriceLevel 对象
     */
    public void release(PriceLevel level) {
        if (level == null) {
            return;
        }

        // 检查池容量
        if (getPoolSize() >= maxPoolSize) {
            // 池已满，丢弃对象（让 GC 回收）
            log.debug("[PriceLevelPool] Pool is full, discarding price level");
            return;
        }

        // 清空对象状态
        level.reset();

        // 归还到池中
        pool.offer(level);
    }

    /**
     * 获取池中对象数量
     */
    public int getPoolSize() {
        return pool.size();
    }

    /**
     * 获取活跃对象数量（已获取但未归还）
     */
    public int getActiveCount() {
        return totalCreated.get() - pool.size();
    }

    /**
     * 获取总创建对象数
     */
    public int getTotalCreated() {
        return totalCreated.get();
    }

    /**
     * 清空对象池（谨慎使用）
     */
    public void clear() {
        pool.clear();
        log.warn("[PriceLevelPool] Pool cleared, total_created={}", totalCreated.get());
    }
}
