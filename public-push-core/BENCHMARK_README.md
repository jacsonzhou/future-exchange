# Public Push Core JMH 基准测试

## 概述

本目录包含 `public-push-core` 模块的 JMH 性能基准测试，用于验证 PRD 中定义的性能目标。

## 测试目标

根据 `PUBLIC_PUSH_SYSTEM_PRD.md`，性能目标如下：

| 指标 | 目标值 | 测试方法 |
|------|--------|---------|
| 单节点WebSocket并发 | 10万连接 | ConnectionManagerBenchmark |
| 端到端推送延迟 | P99 < 50ms | MessageDispatcherBenchmark |
| 消息吞吐量 | 100万msg/s | MessageDispatcherBenchmark |
| Session消息限流 | 1000 msg/s | RateLimiterBenchmark |
| 频道限流 | 10000 msg/s | RateLimiterBenchmark |

## 基准测试列表

### 1. MessageDispatcherBenchmark

测试消息分发器性能：
- 消息序列化性能
- 消息广播性能
- 批量消息构建性能

**运行方式**：
```bash
mvn clean test-compile
java -cp target/test-classes:target/classes com.exchange.push.benchmark.MessageDispatcherBenchmark
```

### 2. RateLimiterBenchmark

测试限流器性能：
- Token Bucket 限流性能
- 高并发下的限流准确性

**运行方式**：
```bash
mvn clean test-compile
java -cp target/test-classes:target/classes com.exchange.push.benchmark.RateLimiterBenchmark
```

### 3. SubscriptionManagerBenchmark

测试订阅管理器性能：
- 订阅/取消订阅性能
- 频道查询性能
- 订阅者查询性能

**运行方式**：
```bash
mvn clean test-compile
java -cp target/test-classes:target/classes com.exchange.push.benchmark.SubscriptionManagerBenchmark
```

### 4. ConnectionManagerBenchmark

测试连接管理器性能：
- 连接注册/移除性能
- IP限流检查性能
- 连接查询性能

**运行方式**：
```bash
mvn clean test-compile
java -cp target/test-classes:target/classes com.exchange.push.benchmark.ConnectionManagerBenchmark
```

## 运行所有基准测试

```bash
# 编译测试代码
mvn clean test-compile

# 运行所有基准测试
java -cp target/test-classes:target/classes org.openjdk.jmh.Main ".*Benchmark"
```

## 预期结果

### MessageDispatcherBenchmark

- `benchmarkSerialize`: ≥ 1,000,000 ops/s
- `benchmarkBroadcast`: ≥ 100,000 ops/s (1000订阅者)
- `benchmarkBatchMessage`: ≥ 500,000 ops/s

### RateLimiterBenchmark

- `benchmarkTokenBucket`: ≥ 10,000,000 ops/s
- 平均时间: < 1μs

### SubscriptionManagerBenchmark

- `benchmarkSubscribe`: ≥ 1,000,000 ops/s
- `benchmarkUnsubscribe`: ≥ 1,000,000 ops/s
- `benchmarkGetSubscribers`: ≥ 10,000,000 ops/s
- `benchmarkGetChannels`: ≥ 10,000,000 ops/s
- `benchmarkIsSubscribed`: ≥ 10,000,000 ops/s

### ConnectionManagerBenchmark

- `benchmarkRegisterConnection`: ≥ 1,000,000 ops/s
- `benchmarkRemoveConnection`: ≥ 1,000,000 ops/s
- `benchmarkGetConnection`: ≥ 10,000,000 ops/s
- `benchmarkCheckIpLimit`: ≥ 10,000,000 ops/s

## 注意事项

1. **环境要求**：
   - JDK 17+
   - Maven 3.8+
   - 足够的堆内存（建议 -Xmx4g）

2. **运行建议**：
   - 在专用机器上运行，避免其他进程干扰
   - 关闭CPU频率调节（CPU governor）
   - 多次运行取平均值

3. **结果分析**：
   - 关注 Throughput（吞吐量）和 AverageTime（平均时间）
   - 对比PRD要求，评估是否达标
   - 如果未达标，分析瓶颈并优化

## 持续集成

建议在 CI/CD 流程中集成基准测试：

```yaml
# .github/workflows/benchmark.yml
name: Performance Benchmark

on:
  schedule:
    - cron: '0 2 * * *'  # 每天凌晨2点运行
  workflow_dispatch:

jobs:
  benchmark:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
      - name: Run benchmarks
        run: |
          mvn clean test-compile
          java -cp target/test-classes:target/classes org.openjdk.jmh.Main ".*Benchmark" > benchmark-results.txt
      - name: Upload results
        uses: actions/upload-artifact@v3
        with:
          name: benchmark-results
          path: benchmark-results.txt
```

---

*最后更新：2026-02-18*

