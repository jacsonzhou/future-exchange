package com.exchange.push.benchmark;

import com.alibaba.fastjson2.JSONObject;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MessageDispatcher 性能基准测试
 * 
 * 测试目标：
 * - 消息广播性能
 * - 批量发送性能
 * - 序列化性能
 * 
 * PRD要求：
 * - 消息吞吐量 ≥ 100万msg/s
 * - 推送延迟 P99 < 50ms
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class MessageDispatcherBenchmark {

    // 模拟订阅者
    private Set<String> subscribers;
    
    // 测试消息
    private JSONObject testMessage;
    private String serializedMessage;

    @Setup
    public void setup() {
        // 初始化1000个订阅者
        subscribers = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 1000; i++) {
            subscribers.add("session_" + i);
        }
        
        // 构建测试消息
        testMessage = new JSONObject();
        testMessage.put("e", "trade");
        testMessage.put("E", System.currentTimeMillis());
        testMessage.put("s", "BTCUSDT");
        testMessage.put("t", 123456789L);
        testMessage.put("p", "45000.50");
        testMessage.put("q", "0.001");
        testMessage.put("T", System.currentTimeMillis());
        testMessage.put("m", true);
        
        // 预序列化
        serializedMessage = testMessage.toJSONString();
    }

    /**
     * 测试消息序列化性能
     */
    @Benchmark
    public String benchmarkSerialize() {
        return testMessage.toJSONString();
    }

    /**
     * 测试消息广播性能（模拟）
     */
    @Benchmark
    public int benchmarkBroadcast() {
        int count = 0;
        for (String sessionId : subscribers) {
            // 模拟发送消息
            count++;
        }
        return count;
    }

    /**
     * 测试批量消息构建性能
     */
    @Benchmark
    public String benchmarkBatchMessage() {
        JSONObject batchWrapper = new JSONObject();
        batchWrapper.put("batch", true);
        batchWrapper.put("messages", new String[]{
            serializedMessage,
            serializedMessage,
            serializedMessage
        });
        return batchWrapper.toJSONString();
    }

    /**
     * 运行基准测试
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(MessageDispatcherBenchmark.class.getSimpleName())
                .build();
        
        new Runner(opt).run();
    }
}

