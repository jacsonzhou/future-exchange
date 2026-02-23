package com.exchange.match.metrics;

import com.exchange.match.pool.OrderPool;
import com.exchange.match.wal.MatchWAL;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 撮合引擎监控指标收集器
 *
 * ============================================
 * Phase 1-3 生产级监控
 * ============================================
 *
 * 监控指标：
 * 1. GC 监控：频率、暂停时间、年轻代/老年代
 * 2. 对象池监控：池大小、活跃对象数、使用率
 * 3. WAL 监控：队列深度、刷盘延迟
 * 4. 撮合延迟监控：P50/P99/P999
 * 5. 内存监控：堆内存使用率
 *
 * 告警规则：
 * - GC 频率 > 2次/秒 → Warning
 * - GC 暂停 > 50ms → Critical
 * - WAL 队列深度 > 5000 → Warning
 * - WAL 队列深度 > 8000 → Critical
 * - 对象池使用率 > 80% → Warning
 */
@Slf4j
@Component
public class MetricsCollector {

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Autowired(required = false)
    private MatchWAL matchWAL;

    /**
     * 撮合延迟记录器
     */
    private final AtomicLong totalMatchings = new AtomicLong(0);
    private final AtomicLong totalLatencyNanos = new AtomicLong(0);

    /**
     * GC 统计（上次采集值）
     */
    private long lastYoungGcCount = 0;
    private long lastYoungGcTime = 0;
    private long lastOldGcCount = 0;
    private long lastOldGcTime = 0;

    @PostConstruct
    public void init() {
        if (meterRegistry != null) {
            log.info("[MetricsCollector] Initialized with Micrometer support");
            registerGauges();
        } else {
            log.warn("[MetricsCollector] MeterRegistry not found, metrics disabled");
        }
    }

    /**
     * 注册固定指标（Gauge）
     */
    private void registerGauges() {
        // JVM 内存监控
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        
        meterRegistry.gauge("jvm.memory.heap.used", Tags.empty(), memoryMXBean, 
            bean -> bean.getHeapMemoryUsage().getUsed());
        meterRegistry.gauge("jvm.memory.heap.max", Tags.empty(), memoryMXBean,
            bean -> bean.getHeapMemoryUsage().getMax());
        meterRegistry.gauge("jvm.memory.heap.usage", Tags.empty(), memoryMXBean,
            bean -> {
                MemoryUsage usage = bean.getHeapMemoryUsage();
                return (double) usage.getUsed() / usage.getMax();
            });

        // WAL 监控
        if (matchWAL != null) {
            meterRegistry.gauge("match.wal.queue.depth", Tags.empty(), matchWAL,
                wal -> wal.getQueueDepth());
            meterRegistry.gauge("match.wal.written.records", Tags.empty(), matchWAL,
                wal -> wal.getWrittenRecords());
        }
    }

    /**
     * 定期采集 GC 指标
     * 每 5 秒采集一次
     */
    @Scheduled(fixedRate = 5000)
    public void collectGCMetrics() {
        if (meterRegistry == null) {
            return;
        }

        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        for (GarbageCollectorMXBean gcBean : gcBeans) {
            String name = gcBean.getName();
            long count = gcBean.getCollectionCount();
            long time = gcBean.getCollectionTime();

            // 记录累计值
            meterRegistry.gauge("jvm.gc.count", Tags.of("name", name), count);
            meterRegistry.gauge("jvm.gc.time", Tags.of("name", name), time);

            // 计算增量（5秒内的 GC 次数和时间）
            if (name.contains("Young") || name.contains("PS Scavenge") || name.contains("G1 Young")) {
                long deltaCount = count - lastYoungGcCount;
                long deltaTime = time - lastYoungGcTime;
                
                if (deltaCount > 0) {
                    // Young GC 频率告警检查
                    double gcPerSecond = deltaCount / 5.0;
                    if (gcPerSecond > 2.0) {
                        log.warn("[MetricsCollector] ⚠️ High Young GC frequency: {}/s", gcPerSecond);
                    }
                    
                    // Young GC 平均暂停时间
                    long avgPauseMs = deltaTime / deltaCount;
                    if (avgPauseMs > 10) {
                        log.warn("[MetricsCollector] ⚠️ High Young GC pause: {}ms", avgPauseMs);
                    }
                }

                lastYoungGcCount = count;
                lastYoungGcTime = time;

            } else if (name.contains("Old") || name.contains("PS MarkSweep") || name.contains("G1 Old")) {
                long deltaCount = count - lastOldGcCount;
                long deltaTime = time - lastOldGcTime;

                if (deltaCount > 0) {
                    // Old GC 告警检查
                    long avgPauseMs = deltaTime / deltaCount;
                    if (avgPauseMs > 50) {
                        log.error("[MetricsCollector] 🚨 Critical Old GC pause: {}ms", avgPauseMs);
                    }
                }

                lastOldGcCount = count;
                lastOldGcTime = time;
            }
        }
    }

    /**
     * 定期采集 WAL 指标
     * 每 1 秒采集一次
     */
    @Scheduled(fixedRate = 1000)
    public void collectWALMetrics() {
        if (matchWAL == null || !matchWAL.isAsyncEnabled()) {
            return;
        }

        int queueDepth = matchWAL.getQueueDepth();

        // WAL 队列深度告警
        if (queueDepth > 8000) {
            log.error("[MetricsCollector] 🚨 Critical WAL queue depth: {}", queueDepth);
        } else if (queueDepth > 5000) {
            log.warn("[MetricsCollector] ⚠️ High WAL queue depth: {}", queueDepth);
        }

        // 刷盘延迟监控
        long lastFlushTime = matchWAL.getLastFlushTime();
        long flushLatency = System.currentTimeMillis() - lastFlushTime;
        
        if (flushLatency > 1000) {
            log.warn("[MetricsCollector] ⚠️ WAL flush latency: {}ms", flushLatency);
        }
    }

    /**
     * 记录撮合延迟
     * 
     * @param latencyNanos 延迟（纳秒）
     */
    public void recordMatchingLatency(long latencyNanos) {
        if (meterRegistry != null) {
            meterRegistry.timer("match.latency").record(latencyNanos, TimeUnit.NANOSECONDS);
        }

        totalMatchings.incrementAndGet();
        totalLatencyNanos.addAndGet(latencyNanos);
    }

    /**
     * 记录订单池指标
     * 
     * @param poolSize   池大小
     * @param activeCount 活跃对象数
     */
    public void recordOrderPoolMetrics(int poolSize, int activeCount) {
        if (meterRegistry != null) {
            meterRegistry.gauge("match.order.pool.size", poolSize);
            meterRegistry.gauge("match.order.pool.active", activeCount);

            double usage = poolSize > 0 ? (double) activeCount / poolSize : 0;
            meterRegistry.gauge("match.order.pool.usage", usage);

            // 对象池使用率告警
            if (usage > 0.9) {
                log.error("[MetricsCollector] 🚨 Critical order pool usage: {}%", usage * 100);
            } else if (usage > 0.8) {
                log.warn("[MetricsCollector] ⚠️ High order pool usage: {}%", usage * 100);
            }
        }
    }

    /**
     * 获取平均撮合延迟（微秒）
     */
    public double getAverageLatencyMicros() {
        long matchings = totalMatchings.get();
        if (matchings == 0) {
            return 0;
        }
        return totalLatencyNanos.get() / (double) matchings / 1000.0;
    }

    /**
     * 获取总撮合次数
     */
    public long getTotalMatchings() {
        return totalMatchings.get();
    }

    /**
     * 定期打印统计摘要
     * 每 60 秒打印一次
     */
    @Scheduled(fixedRate = 60000)
    public void printSummary() {
        log.info("[MetricsCollector] ========== Performance Summary ==========");
        log.info("[MetricsCollector] Total matchings: {}", getTotalMatchings());
        log.info("[MetricsCollector] Avg latency: {:.2f} μs", getAverageLatencyMicros());

        if (matchWAL != null && matchWAL.isAsyncEnabled()) {
            log.info("[MetricsCollector] WAL queue depth: {}", matchWAL.getQueueDepth());
            log.info("[MetricsCollector] WAL written records: {}", matchWAL.getWrittenRecords());
        }

        // JVM 内存摘要
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        double heapUsagePercent = (double) heapUsage.getUsed() / heapUsage.getMax() * 100;
        log.info("[MetricsCollector] Heap usage: {:.1f}% ({}/{} MB)",
            heapUsagePercent,
            heapUsage.getUsed() / 1024 / 1024,
            heapUsage.getMax() / 1024 / 1024);

        log.info("[MetricsCollector] ==========================================");
    }
}
