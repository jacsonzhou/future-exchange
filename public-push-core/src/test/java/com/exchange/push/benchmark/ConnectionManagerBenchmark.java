package com.exchange.push.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ConnectionManager 性能基准测试
 * 
 * 测试目标：
 * - 连接注册/移除性能
 * - IP限流检查性能
 * - 连接查询性能
 * 
 * PRD要求：
 * - 单节点支持10万并发连接
 * - 连接建立时间 < 100ms
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class ConnectionManagerBenchmark {

    // 模拟数据结构
    private Map<String, String> sessions;
    private Map<String, AtomicInteger> ipConnectionCounts;
    private String testSessionId;
    private String testIp;

    @Setup
    public void setup() {
        sessions = new ConcurrentHashMap<>();
        ipConnectionCounts = new ConcurrentHashMap<>();
        
        // 初始化10000个连接
        for (int i = 0; i < 10000; i++) {
            String sessionId = "session_" + i;
            String ip = "192.168.1." + (i % 255);
            sessions.put(sessionId, ip);
            ipConnectionCounts.computeIfAbsent(ip, k -> new AtomicInteger(0)).incrementAndGet();
        }
        
        testSessionId = "session_5000";
        testIp = "192.168.1.100";
    }

    /**
     * 测试连接注册性能
     */
    @Benchmark
    public boolean benchmarkRegisterConnection() {
        sessions.put(testSessionId, testIp);
        ipConnectionCounts.computeIfAbsent(testIp, k -> new AtomicInteger(0)).incrementAndGet();
        return true;
    }

    /**
     * 测试连接移除性能
     */
    @Benchmark
    public boolean benchmarkRemoveConnection() {
        String ip = sessions.remove(testSessionId);
        if (ip != null) {
            AtomicInteger count = ipConnectionCounts.get(ip);
            if (count != null) {
                count.decrementAndGet();
            }
        }
        return true;
    }

    /**
     * 测试连接查询性能
     */
    @Benchmark
    public boolean benchmarkGetConnection() {
        return sessions.containsKey(testSessionId);
    }

    /**
     * 测试IP限流检查性能
     */
    @Benchmark
    public boolean benchmarkCheckIpLimit() {
        AtomicInteger count = ipConnectionCounts.get(testIp);
        return count == null || count.get() < 50;
    }

    /**
     * 运行基准测试
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(ConnectionManagerBenchmark.class.getSimpleName())
                .build();
        
        new Runner(opt).run();
    }
}




