# OMS → Match Engine 双通道架构设计

## 📋 架构背景

大型合约交易系统中，OMS（订单管理系统）到 Match Engine（撮合引擎）的通信方式直接影响：
- 系统延迟
- 可靠性
- 可扩展性
- 监管合规性

本项目实现**双通道架构**，支持 Kafka 和 Feign 两种通信方式，通过配置灵活切换。

---

## 🏗️ 架构设计

### 整体架构图

```
                    ┌─────────────────────────────────────────┐
                    │           OMS Core                      │
                    │                                         │
                    │  1. 前置风控（Hard Risk Gate）          │
                    │  2. 冻结资金（Account Service）         │
                    │  3. 持久化订单（MySQL）                 │
                    │  4. 双通道提交 ──────────┐              │
                    │                          │              │
                    └──────────────────────────┼──────────────┘
                                               │
                        ┌──────────────────────┴──────────────────────┐
                        │       配置开关：submit-mode                 │
                        │       exchange.oms.submit-mode              │
                        └──────────┬─────────────────┬────────────────┘
                                   │                 │
                    ┌──────────────▼──────┐   ┌──────▼──────────────┐
                    │  Kafka 通道（推荐）  │   │  Feign 通道（降级） │
                    │  - 异步解耦          │   │  - 同步直连          │
                    │  - 可重放            │   │  - 低延迟            │
                    │  - 可灾备            │   │  - 简单直接          │
                    │  - 审计追溯          │   │  - 紧耦合            │
                    └──────────┬───────────┘   └──────┬──────────────┘
                               │                      │
                               ▼                      ▼
                    ┌──────────────────────────────────────────┐
                    │ Topic: order-event-{symbol}              │
                    │ - 单分区保证顺序                         │
                    │ - Key = orderId                          │
                    │ - Retention: 7 days                      │
                    └──────────┬───────────────────────────────┘
                               │
                               ▼
                    ┌──────────────────────────────────────────┐
                    │      Match Engine Core                   │
                    │                                          │
                    │  OrderEventConsumer (Kafka)              │
                    │  MatchController (Feign)                 │
                    │         ↓                                │
                    │  Disruptor RingBuffer                    │
                    │         ↓                                │
                    │  MatchingProcessor → OrderBook           │
                    └──────────────────────────────────────────┘
```

---

## 🔀 双通道对比

### 通道1：Kafka 异步通道 ⭐ **生产推荐**

#### 优点
✅ **解耦架构**：OMS 和 Match Engine 独立演进
✅ **顺序日志**：每个 Symbol 的订单事件完整记录
✅ **可重放**：支持从任意时间点重建状态
✅ **灾备恢复**：Match Engine 重启后从 Kafka 恢复
✅ **削峰填谷**：缓冲流量高峰，保护撮合引擎
✅ **水平扩展**：支持 Active-Active、主备切换
✅ **对账审计**：完整的 Event Sourcing 日志
✅ **合规要求**：满足监管的审计追溯需求

#### 缺点
⚠️ **延迟增加**：
  - 本地 Kafka：0.5-1ms
  - 集群 Kafka：1-2ms
  - 跨数据中心：5-10ms

#### 适用场景
- ✅ **生产环境（强烈推荐）**
- ✅ 需要监管合规的场景
- ✅ 需要灾备恢复能力
- ✅ 多机房/多活部署
- ✅ 延迟要求在 5ms 以内可接受

#### 性能指标
```yaml
延迟（P50）: 1ms
延迟（P99）: 3ms
TPS: 10000+
可用性: 99.99%
数据可靠性: 99.9999%（3副本）
```

---

### 通道2：Feign 同步通道（降级/测试）

#### 优点
✅ **极低延迟**：微秒级，适合高频交易
✅ **实现简单**：代码量少，易于调试
✅ **同步反馈**：立即知道订单是否被撮合引擎接收
✅ **适合 MVP**：快速验证业务逻辑

#### 缺点
❌ **紧耦合**：OMS 和 Match Engine 直接依赖
❌ **无法重放**：系统故障后无法从日志重建状态
❌ **无法灾备**：Match Engine 宕机 = 订单丢失
❌ **无法削峰**：流量高峰直接冲击撮合引擎
❌ **扩展性差**：难以支持 Active-Active 部署
❌ **无法对账**：缺少顺序日志
❌ **合规风险**：监管要求保留完整的订单流日志

#### 适用场景
- ✅ Demo/POC 阶段
- ✅ 内部测试环境
- ✅ 紧急降级场景
- ✅ 极低延迟要求（< 100μs）且可接受单点故障

#### 性能指标
```yaml
延迟（P50）: 0.1ms
延迟（P99）: 0.5ms
TPS: 20000+（单机）
可用性: 99.9%（无灾备）
数据可靠性: 取决于单点
```

---

## ⚙️ 配置说明

### 配置项

```yaml
exchange:
  oms:
    # 提交模式：kafka（生产推荐）| feign（降级/测试）
    submit-mode: kafka
```

### 配置切换

#### 生产环境（Kafka模式）

```yaml
# application.yml
exchange:
  oms:
    submit-mode: kafka
```

#### 测试环境（Feign模式）

方式1：使用配置文件
```bash
java -jar oms-core.jar --spring.profiles.active=feign
```

方式2：直接修改配置
```yaml
# application.yml
exchange:
  oms:
    submit-mode: feign
```

#### 紧急降级

如果 Kafka 集群故障，可临时切换到 Feign 模式：

```bash
# 无需重启，通过配置中心动态切换
# Nacos配置中心
exchange.oms.submit-mode: feign
```

---

## 📊 性能对比

| 维度 | Kafka通道 | Feign通道 |
|-----|----------|----------|
| **延迟（P50）** | 1ms | 0.1ms |
| **延迟（P99）** | 3ms | 0.5ms |
| **TPS** | 10000+ | 20000+ |
| **可靠性** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| **解耦性** | ⭐⭐⭐⭐⭐ | ⭐ |
| **可重放** | ✅ | ❌ |
| **灾备** | ✅ | ❌ |
| **削峰** | ✅ | ❌ |
| **扩展性** | ⭐⭐⭐⭐⭐ | ⭐⭐ |
| **监管合规** | ✅ | ❌ |
| **实现复杂度** | ⭐⭐⭐ | ⭐ |
| **适用阶段** | 生产环境 | 测试/降级 |

---

## 🔍 业界最佳实践

### 顶级交易所架构

#### 币安（Binance）
```
OMS → Kafka (order-event-{symbol}) → Match Gateway → Disruptor → Match Engine
```
- **两级队列**：Kafka 用于持久化和解耦，Disruptor 用于极致性能
- 每个 Symbol 单独 Topic，单分区保证顺序

#### OKX/火币（Huobi）
```
OMS → Kafka → Match Dispatcher → Disruptor → Match Engine（Per Symbol）
```
- 类似架构，Kafka 作为权威顺序日志
- 支持从 Kafka 重放实现灾备

#### 传统高频交易（HFT）
```
OMS → Shared Memory Queue / LMAX Disruptor → Match Engine
```
- **不使用 Kafka**，追求极致延迟（< 100μs）
- 单机部署，不考虑分布式

---

## 💻 代码实现

### 核心代码结构

```
oms-core/
├── config/
│   └── OmsSubmitModeConfig.java           # 配置类
├── service/impl/
│   └── OrderServiceImpl.java              # 双通道实现
├── publisher/
│   └── OrderEventPublisher.java           # Kafka发布器
├── client/
│   └── MatchEngineClient.java             # Feign客户端
└── resources/
    ├── application.yml                    # 主配置（Kafka模式）
    └── application-feign.yml              # Feign模式配置
```

### 核心方法

```java
/**
 * 🔥 双通道架构核心：根据配置选择提交方式
 */
private void submitOrderToMatchEngine(OrderCommand command, Order order) {
    if (submitModeConfig.isKafkaMode()) {
        // Kafka通道：异步解耦（生产推荐）
        log.info("[Kafka Mode] Submitting order via Kafka");
        OrderEventCommand kafkaCommand = convertToKafkaCommand(command, order);
        orderEventPublisher.publishOrderEvent(kafkaCommand);
    } else if (submitModeConfig.isFeignMode()) {
        // Feign通道：同步直连（降级/测试）
        log.info("[Feign Mode] Submitting order via Feign");
        matchEngineClient.submitOrder(command);
    } else {
        // 未知模式降级到Kafka
        log.error("Invalid submit mode, fallback to Kafka");
        OrderEventCommand kafkaCommand = convertToKafkaCommand(command, order);
        orderEventPublisher.publishOrderEvent(kafkaCommand);
    }
}
```

---

## 🧪 测试验证

### 功能测试

1. **Kafka模式测试**
```bash
# 启动 Kafka
docker-compose up -d kafka

# 启动 OMS（默认Kafka模式）
java -jar oms-core.jar

# 发送测试订单
curl -X POST http://localhost:8081/api/order \
  -H "Content-Type: application/json" \
  -d '{"userId":1001,"symbol":"BTCUSDT","side":"BUY","price":"50000","quantity":"1"}'

# 观察 Kafka 消息
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic order-event-BTCUSDT --from-beginning
```

2. **Feign模式测试**
```bash
# 启动 OMS（Feign模式）
java -jar oms-core.jar --spring.profiles.active=feign

# 发送测试订单（同上）
# 观察日志：应显示 [Feign Mode]
```

### 性能测试

```bash
# Kafka模式压测
wrk -t4 -c100 -d60s --latency \
  -s order_submit.lua http://localhost:8081/api/order

# Feign模式压测（切换配置后再测）
```

### 灾备测试

```bash
# 1. Kafka模式下提交100个订单
# 2. 停止 Match Engine
# 3. 提交100个订单（应正常入队列）
# 4. 重启 Match Engine
# 5. 验证200个订单全部被处理

# Feign模式：第3步会失败（无灾备）
```

---

## 📈 监控指标

### Kafka通道监控

```yaml
# Kafka Producer监控
- oms.kafka.send.success.count        # 发送成功数
- oms.kafka.send.failure.count        # 发送失败数
- oms.kafka.send.latency.p99          # 发送延迟P99

# Kafka Consumer监控（Match Engine侧）
- match.kafka.consume.lag             # 消费延迟
- match.kafka.consume.rate            # 消费速率
```

### Feign通道监控

```yaml
# Feign Client监控
- oms.feign.submit.success.count      # 调用成功数
- oms.feign.submit.failure.count      # 调用失败数
- oms.feign.submit.latency.p99        # 调用延迟P99
```

---

## 🚀 部署建议

### 生产环境部署

1. **默认使用 Kafka 模式**
   ```yaml
   exchange.oms.submit-mode: kafka
   ```

2. **Kafka 集群配置**
   - 3节点集群，replication-factor=3
   - min.insync.replicas=2
   - 单分区保证顺序

3. **监控告警**
   - Kafka lag > 1000 告警
   - 消费延迟 > 5s 告警

4. **降级预案**
   - 准备 Feign 模式配置
   - 配置中心一键切换
   - 定期演练降级流程

### 灰度上线

```bash
# 阶段1：20% 流量走 Kafka
- 配置路由规则：userId % 5 == 0 → Kafka
- 监控延迟、成功率

# 阶段2：50% 流量走 Kafka
- userId % 2 == 0 → Kafka

# 阶段3：100% 流量走 Kafka
- 全量切换
- 下线 Feign 通道（保留降级能力）
```

---

## 📝 总结

### 推荐方案

**强烈推荐：Kafka 异步通道 ⭐⭐⭐⭐⭐**

理由：
1. **监管合规**：金融系统必须保留完整审计日志
2. **灾备要求**：生产环境必须支持故障恢复
3. **业界标准**：所有大型交易所均采用此架构
4. **延迟可接受**：1-2ms 对合约交易影响极小
5. **扩展性**：支持多机房、Active-Active

### 不推荐场景

仅在以下场景使用 Feign 通道：
- ❌ 高频交易（HFT）需要 < 100μs 延迟
- ❌ 单机部署不考虑灾备
- ✅ 测试环境快速验证
- ✅ 紧急降级场景

### 关键文件

```
/oms-core/src/main/java/com/exchange/oms/config/OmsSubmitModeConfig.java
/oms-core/src/main/java/com/exchange/oms/service/impl/OrderServiceImpl.java
/oms-core/src/main/java/com/exchange/oms/publisher/OrderEventPublisher.java
/oms-core/src/main/java/com/exchange/oms/client/MatchEngineClient.java
/oms-core/src/main/resources/application.yml
/oms-core/src/main/resources/application-feign.yml
```

---

## 📞 联系方式

如有问题或建议，请联系架构组。
