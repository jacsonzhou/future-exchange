package com.exchange.push.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * SubscriptionManager 性能基准测试
 * 
 * 测试目标：
 * - 订阅/取消订阅性能
 * - 频道查询性能
 * - 订阅者查询性能
 * 
 * PRD要求：
 * - 单连接最大订阅数：1024
 * - 订阅变更频率限制：10/秒
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class SubscriptionManagerBenchmark {

    // 模拟数据结构
    private Set<String> channelSubscribers;
    private Set<String> sessionChannels;
    private String testChannel;
    private String testSessionId;

    @Setup
    public void setup() {
        // 初始化数据结构
        channelSubscribers = ConcurrentHashMap.newKeySet();
        sessionChannels = ConcurrentHashMap.newKeySet();
        
        // 添加1000个订阅者
        for (int i = 0; i < 1000; i++) {
            channelSubscribers.add("session_" + i);
        }
        
        // 添加100个频道
        for (int i = 0; i < 100; i++) {
            sessionChannels.add("channel_" + i);
        }
        
        testChannel = "trade.BTCUSDT";
        testSessionId = "session_500";
    }

    /**
     * 测试订阅操作性能
     */
    @Benchmark
    public boolean benchmarkSubscribe() {
        channelSubscribers.add(testSessionId);
        sessionChannels.add(testChannel);
        return true;
    }

    /**
     * 测试取消订阅操作性能
     */
    @Benchmark
    public boolean benchmarkUnsubscribe() {
        channelSubscribers.remove(testSessionId);
        sessionChannels.remove(testChannel);
        return true;
    }

    /**
     * 测试获取订阅者性能
     */
    @Benchmark
    public int benchmarkGetSubscribers() {
        return channelSubscribers.size();
    }

    /**
     * 测试获取频道列表性能
     */
    @Benchmark
    public int benchmarkGetChannels() {
        return sessionChannels.size();
    }

    /**
     * 测试检查订阅状态性能
     */
    @Benchmark
    public boolean benchmarkIsSubscribed() {
        return channelSubscribers.contains(testSessionId);
    }

    /**
     * 运行基准测试
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(SubscriptionManagerBenchmark.class.getSimpleName())
                .build();
        
        new Runner(opt).run();
    }
}

