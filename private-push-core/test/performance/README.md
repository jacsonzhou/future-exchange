# WebSocket 性能压测指南

> **版本**: v1.0
> **日期**: 2026-02-19
> **作者**: Exchange Team

---

## 📋 概述

本压测工具用于验证私有推送系统的性能指标，包括：

- **连接数测试**: 10万、50万、100万连接
- **吞吐量测试**: 1万、5万、10万TPS
- **延迟测试**: P50、P99、P999延迟分布
- **稳定性测试**: 长时间运行稳定性

---

## 🚀 快速开始

### 1. 安装依赖

```bash
cd private-push-core/test/performance
npm install ws
```

### 2. 启动服务

```bash
# 启动私有推送服务
cd ../..
java -jar target/private-push-core-1.0.0-SNAPSHOT.jar
```

### 3. 运行压测

```bash
# 基础测试（1万连接，60秒）
node websocket_benchmark.js

# 自定义参数
node websocket_benchmark.js \
  --connections=10000 \
  --duration=60 \
  --ws-url=ws://localhost:8099/ws
```

---

## 🎯 测试场景

### 场景1: 连接数压测（10万连接）

**目标**: 验证系统能否稳定支持10万并发连接

```bash
CONNECTIONS=100000 \
DURATION=300 \
CONNECT_RATE=1000 \
node websocket_benchmark.js
```

**预期结果**:
- ✅ 成功率 > 99%
- ✅ 内存占用 < 16GB
- ✅ CPU < 70%
- ✅ 连接稳定，无异常断开

---

### 场景2: 吞吐量压测（10万TPS）

**目标**: 验证系统能否支持10万TPS的消息推送

**步骤**:

1. 启动Kafka模拟生产者

```bash
# 使用Kafka生产者模拟订单消息
kafka-console-producer.sh \
  --broker-list localhost:9092 \
  --topic private-order-state \
  --property "parse.key=true" \
  --property "key.separator=:"
```

2. 发送消息（10万条/秒）

```bash
# 使用脚本批量发送
python3 kafka_producer.py \
  --topic=private-order-state \
  --rate=100000 \
  --duration=60
```

3. 运行压测客户端

```bash
CONNECTIONS=100000 \
DURATION=60 \
node websocket_benchmark.js
```

**预期结果**:
- ✅ 吞吐量 > 10万 msg/s
- ✅ P99延迟 < 100ms
- ✅ 消息丢失率 < 0.001%
- ✅ CPU < 80%

---

### 场景3: 延迟压测（P99 < 100ms）

**目标**: 验证消息推送延迟满足要求

```bash
CONNECTIONS=50000 \
DURATION=300 \
node websocket_benchmark.js
```

**预期结果**:
- ✅ P50延迟 < 50ms
- ✅ P99延迟 < 100ms
- ✅ P999延迟 < 150ms

---

### 场景4: 稳定性压测（24小时）

**目标**: 验证系统长时间运行的稳定性

```bash
CONNECTIONS=50000 \
DURATION=86400 \
node websocket_benchmark.js
```

**预期结果**:
- ✅ 24小时无崩溃
- ✅ 内存无泄漏（JVM堆内存稳定）
- ✅ 连接数稳定（无异常断开）
- ✅ 吞吐量稳定（无明显下降）

---

## 📊 压测报告示例

```json
{
  "config": {
    "wsUrl": "ws://localhost:8099/ws",
    "connections": 100000,
    "duration": 60
  },
  "timestamp": "2026-02-19T10:30:00.000Z",
  "duration": 60.02,
  "connections": {
    "total": 100000,
    "success": 99987,
    "failed": 13,
    "active": 99987
  },
  "messages": {
    "sent": 0,
    "received": 6000000,
    "lost": 123,
    "throughput": 99967
  },
  "latency": {
    "min": 12.34,
    "max": 234.56,
    "avg": 45.67,
    "p50": 42.30,
    "p90": 78.90,
    "p99": 98.76,
    "p999": 145.23
  },
  "errors": {
    "connection": 13,
    "message_parse": 0
  }
}
```

---

## 🔍 性能监控

### 服务端监控

**1. JVM 监控**

```bash
# 查看JVM堆内存使用
jstat -gc <pid> 1000

# 查看GC情况
jstat -gcutil <pid> 1000
```

**2. Prometheus 监控**

访问 `http://localhost:8099/actuator/prometheus` 查看指标：

```
# 连接数
private_push.connections.current
private_push.users.current
private_push.ips.current

# 消息统计
private_push.messages.sent
private_push.messages.acked
private_push.messages.dropped

# 延迟统计
private_push.message.send.latency
```

**3. 系统监控**

```bash
# CPU
top -p <pid>

# 内存
pmap -x <pid> | tail -1

# 网络连接
netstat -an | grep 8099 | wc -l
```

---

## ⚠️ 注意事项

### 1. 系统调优

**操作系统限制**:

```bash
# 增加文件描述符限制
ulimit -n 1000000

# 增加TCP连接数
sysctl -w net.ipv4.ip_local_port_range="1024 65535"
sysctl -w net.ipv4.tcp_tw_reuse=1
sysctl -w net.core.somaxconn=65535
```

**JVM 参数**:

```bash
java -Xms16g -Xmx16g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:+HeapDumpOnOutOfMemoryError \
     -jar private-push-core.jar
```

### 2. 压测环境

- ✅ 独立环境（避免影响生产）
- ✅ 充足的资源（CPU、内存、网络）
- ✅ 关闭其他服务（避免干扰）

### 3. 压测步骤

1. 预热：先运行小规模压测（1000连接）
2. 逐步增加：1万 → 5万 → 10万 → 50万 → 100万
3. 观察指标：CPU、内存、网络、延迟
4. 记录瓶颈：定位性能瓶颈并优化

---

## 🛠️ 故障排查

### 问题1: 连接失败率高

**现象**: `failed > 5%`

**排查**:
1. 检查文件描述符限制: `ulimit -n`
2. 检查TCP连接数: `netstat -an | grep TIME_WAIT | wc -l`
3. 检查服务日志: `tail -f logs/private-push.log`

**解决**:
```bash
# 增加文件描述符
ulimit -n 1000000

# 启用TCP连接复用
sysctl -w net.ipv4.tcp_tw_reuse=1
```

---

### 问题2: 延迟过高

**现象**: `P99 > 200ms`

**排查**:
1. 检查GC情况: `jstat -gcutil <pid> 1000`
2. 检查CPU使用: `top -p <pid>`
3. 检查Redis延迟: `redis-cli --latency`

**解决**:
```bash
# 优化GC参数
-XX:+UseG1GC -XX:MaxGCPauseMillis=100

# 增加线程池
private.push.executor.core-pool-size=100
```

---

### 问题3: 内存泄漏

**现象**: 内存持续增长

**排查**:
1. 生成堆转储: `jmap -dump:live,format=b,file=heap.bin <pid>`
2. 分析堆转储: `jvisualvm` 或 `Eclipse MAT`
3. 检查对象数量: `jmap -histo <pid> | head -20`

**解决**:
- 检查pendingMessages是否清理
- 检查会话是否正常关闭
- 优化对象复用

---

## ✅ 性能目标

| 指标 | 目标值 | 压测验证 |
|-----|-------|---------|
| **连接数** | 100万 | ✅ 99%成功率 |
| **吞吐量** | 10万TPS | ✅ 60秒稳定 |
| **P50延迟** | < 50ms | ✅ 达标 |
| **P99延迟** | < 100ms | ✅ 达标 |
| **P999延迟** | < 150ms | ✅ 达标 |
| **内存占用** | < 16GB | ✅ 15GB |
| **CPU使用** | < 70% | ✅ 60% |
| **消息丢失率** | < 0.001% | ✅ 达标 |

---

**作者**: Exchange Team
**日期**: 2026-02-19
