package com.exchange.push.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RateLimiter 性能基准测试
 * 
 * 测试目标：
 * - Token Bucket 限流性能
 * - 高并发下的限流准确性
 * 
 * PRD要求：
 * - Session消息限流：1000 msg/s
 * - 频道限流：10000 msg/s
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(4)
public class RateLimiterBenchmark {

    // 简化的Token Bucket实现（用于基准测试）
    private TokenBucket tokenBucket;
    private AtomicLong successCount;
    private AtomicLong failCount;

    @Setup
    public void setup() {
        // 初始化Token Bucket：1000 tokens/s, max 1000 tokens
        tokenBucket = new TokenBucket(1000.0, 1000.0);
        successCount = new AtomicLong(0);
        failCount = new AtomicLong(0);
    }

    /**
     * 测试Token Bucket限流性能
     */
    @Benchmark
    public boolean benchmarkTokenBucket() {
        boolean allowed = tokenBucket.tryAcquire();
        if (allowed) {
            successCount.incrementAndGet();
        } else {
            failCount.incrementAndGet();
        }
        return allowed;
    }

    /**
     * 简化的Token Bucket实现
     */
    private static class TokenBucket {
        private final double tokensPerSecond;
        private final double maxTokens;
        private volatile double tokens;
        private volatile long lastRefillTime;

        TokenBucket(double tokensPerSecond, double maxTokens) {
            this.tokensPerSecond = tokensPerSecond;
            this.maxTokens = maxTokens;
            this.tokens = maxTokens;
            this.lastRefillTime = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            refill();
            
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            long lastRefill = lastRefillTime;
            double elapsedSeconds = (now - lastRefill) / 1_000_000_000.0;
            
            if (elapsedSeconds > 0) {
                double tokensToAdd = elapsedSeconds * tokensPerSecond;
                tokens = Math.min(maxTokens, tokens + tokensToAdd);
                lastRefillTime = now;
            }
        }
    }

    /**
     * 运行基准测试
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(RateLimiterBenchmark.class.getSimpleName())
                .build();
        
        new Runner(opt).run();
    }
}

