# 🔥 交易所级双通道Kafka架构

## 核心设计原则

> 在交易所级架构里：
> - **OMS → Match Engine** 是单向顺序日志
> - **Match Engine → OMS** 是独立的回传通道（不是同一条 Kafka）

## 架构总览

```
OMS Core
  ↓
  ⓵ 单向顺序日志（OrderEvent Log）
  ↓
Kafka: order-event-{symbol} (单分区)
  ↓
Match Engine (per symbol)
  ↓
  ⓶ 独立回传通道（Match Result Channel）
  ↓
Kafka: order-state-event  → OMS
Kafka: trade-event        → Ledger / Clearing / Position
```

## 核心思想

**撮合输入** 与 **撮合输出** 必须是两条独立、可重放的事件流。

## Topic设计

### 1. order-event-{symbol}（OMS → Match Engine）

**作用**：单向顺序日志，OMS投递订单事件到撮合引擎

**特性**：
- ✅ 每个Symbol一个Topic：`order-event-BTCUSDT`、`order-event-ETHUSDT`
- ✅ **单分区**（保证顺序）
- ✅ Key = orderId
- ✅ 可重放、可灾备

**消息类型**：
```java
{
  "eventType": "ORDER_SUBMIT",     // ORDER_SUBMIT / ORDER_CANCEL
  "orderId": 10001,
  "userId": 1001,
  "symbol": "BTCUSDT",
  "side": "BUY",
  "orderType": "LIMIT",
  "price": "43000",
  "quantity": "0.1",
  "eventTime": 1640000000000
}
```

**生产者**：OMS Core
**消费者**：Match Engine (per symbol)

---

### 2. order-state-{symbol}（Match Engine → OMS）

**作用**：订单状态回传，撮合引擎通知OMS订单状态变化

**特性**：
- ✅ 每个Symbol一个Topic：`order-state-BTCUSDT`
- ✅ 单分区（按orderId分区）
- ✅ Key = orderId
- ✅ **独立通道**，不是 order-event-{symbol}

**消息类型**：
```java
{
  "orderId": 10001,
  "symbol": "BTCUSDT",
  "status": "PARTIALLY_FILLED",  // NEW / PARTIALLY_FILLED / FILLED / CANCELED / REJECTED
  "filledQuantityDelta": "0.05",
  "eventTime": 1640000000100
}
```

**生产者**：Match Engine
**消费者**：OMS Core

---

### 3. trade-event（Match Engine → 全系统）

**作用**：成交事件，全系统的资金唯一来源

**特性**：
- ✅ **全局Topic**（所有Symbol的成交都发到这个Topic）
- ✅ 多分区（按tradeId分区）
- ✅ Key = tradeId
- 🔥 **钱的唯一来源**
- 🔥 Ledger唯一事实
- 🔥 Clearing唯一事实
- 🔥 Position更新唯一依据

**消息类型**：
```java
{
  "tradeId": "BTCUSDT-20240101-000001",
  "symbol": "BTCUSDT",
  "makerOrderId": 20001,
  "takerOrderId": 10001,
  "price": "43000",
  "quantity": "0.05",
  "makerSide": "SELL",
  "takerSide": "BUY",
  "tradeTime": 1640000000100,
  "sequence": 12345
}
```

**生产者**：Match Engine
**消费者**：Ledger Service、Position Service、Clearing Service、WebSocket Service

---

## 完整交互流程

### 1. 用户下单（BUY BTCUSDT, price=43000, qty=0.1）

```
① 用户 → API Gateway → OMS
② OMS：参数校验、幂等检查
③ OMS：创建订单（orderId=10001, status=NEW）
④ OMS：调用Hard Risk → PASS
⑤ OMS：冻结资金 → SUCCESS
⑥ OMS：更新状态 → FROZEN
⑦ OMS：发送到Kafka
    Topic: order-event-BTCUSDT
    Key: 10001
    Value: {"eventType":"ORDER_SUBMIT",...}
```

### 2. Match Engine撮合

```
⑧ Match Engine：从Kafka消费订单事件
    Consumer: match-engine-BTCUSDT
    Topic: order-event-BTCUSDT
    
⑨ Match Engine：提交到Disruptor（单线程）
⑩ Match Engine：撮合处理
    - 找到卖单20001（price=43000, qty=0.1）
    - 完全成交！
    
⑪ Match Engine：生成TradeEvent
    tradeId: BTCUSDT-xxx-1
    maker: 20001, taker: 10001
    price: 43000, qty: 0.1
```

### 3. Match Engine发布事件（双通道）

```
⑫ Match Engine → Kafka: trade-event
    Topic: trade-event
    Key: BTCUSDT-xxx-1
    Value: {"tradeId":"...","makerOrderId":20001,...}
    
⑬ Match Engine → Kafka: order-state-BTCUSDT
    Topic: order-state-BTCUSDT
    Key: 10001
    Value: {"orderId":10001,"status":"FILLED","filledQuantityDelta":"0.1"}
    
⑭ Match Engine → Kafka: order-state-BTCUSDT
    Topic: order-state-BTCUSDT
    Key: 20001
    Value: {"orderId":20001,"status":"FILLED","filledQuantityDelta":"0.1"}
```

### 4. OMS接收状态更新

```
⑮ OMS：从Kafka消费订单状态
    Consumer: oms-order-state
    Topic: order-state-BTCUSDT
    
⑯ OMS：更新订单状态
    orderId=10001: FROZEN → FILLED
    filledQuantity: 0 → 0.1
    
⑰ OMS：记录状态变更日志
```

### 5. Ledger清算

```
⑱ Ledger Service：从Kafka消费成交事件
    Consumer: ledger-service
    Topic: trade-event
    
⑲ Ledger：执行资金清算
    - 买方扣除：43000 * 0.1 = 4300 USDT
    - 卖方扣除：0.1 BTC
    - 买方增加：0.1 BTC
    - 卖方增加：4300 USDT
```

---

## 代码实现

### OMS侧

**OrderEventPublisher.java**（OMS → Kafka）
```java
@Component
public class OrderEventPublisher {
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    
    public void publishOrderEvent(OrderEventCommand command) {
        String topic = "order-event-" + command.getSymbol();
        String key = command.getOrderId().toString();
        String value = objectMapper.writeValueAsString(command);
        
        kafkaTemplate.send(topic, key, value);
    }
}
```

**OrderStateConsumer.java**（Kafka → OMS）
```java
@Component
public class OrderStateConsumer {
    @KafkaListener(
        topics = {"order-state-BTCUSDT", "order-state-ETHUSDT"},
        groupId = "oms-order-state",
        concurrency = "3"
    )
    @Transactional
    public void consumeOrderState(@Payload String message) {
        // 解析消息
        JsonNode event = objectMapper.readTree(message);
        Long orderId = event.get("orderId").asLong();
        String status = event.get("status").asText();
        
        // 更新订单状态
        OmsOrder order = orderMapper.selectById(orderId);
        order.setStatus(mapStatus(status));
        orderMapper.updateById(order);
    }
}
```

### Match Engine侧

**OrderEventConsumer.java**（Kafka → Match Engine）
```java
@Component
public class OrderEventConsumer {
    @KafkaListener(
        topics = "order-event-#{@orderEventConsumer.symbol}",
        groupId = "match-engine-#{@orderEventConsumer.symbol}",
        concurrency = "1"  // 🔥 单线程消费，保证顺序
    )
    public void consumeOrderEvent(@Payload String message) {
        OrderCommand command = objectMapper.readValue(message, OrderCommand.class);
        
        // 提交到Disruptor（单线程撮合）
        disruptorEngine.submitOrderCommand(command);
    }
}
```

**TradePublisher.java**（Match Engine → Kafka）
```java
@Component
public class TradePublisher {
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    
    // 发布成交事件
    public void publishTrade(Trade trade) {
        String topic = "trade-event";
        String key = trade.getTradeId();
        String value = objectMapper.writeValueAsString(trade);
        
        kafkaTemplate.send(topic, key, value);
    }
    
    // 发布订单状态事件
    public void publishOrderState(OrderStateEvent event) {
        String topic = "order-state-" + event.getSymbol();
        String key = event.getOrderId().toString();
        String value = objectMapper.writeValueAsString(event);
        
        kafkaTemplate.send(topic, key, value);
    }
}
```

---

## Kafka配置优化

### application.yml（OMS）

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    
    # Producer配置（OMS → Match Engine）
    producer:
      acks: 1                    # 不需要all（性能优先）
      linger-ms: 0              # 不等待batch
      compression-type: none     # 不压缩（减少延迟）
      
    # Consumer配置（Match Engine → OMS）
    consumer:
      group-id: oms-order-state
      enable-auto-commit: true
      auto-offset-reset: earliest
      fetch-min-bytes: 1        # 不等待batch
      fetch-max-wait-ms: 0      # 立即返回
```

### application.yml（Match Engine）

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    
    # Consumer配置（OMS → Match Engine）
    consumer:
      group-id: match-engine-${match.symbol}
      enable-auto-commit: true
      auto-offset-reset: earliest  # 灾备恢复必须
      fetch-min-bytes: 1
      fetch-max-wait-ms: 0
      
    # Producer配置（Match Engine → 全系统）
    producer:
      acks: 1
      linger-ms: 0
      compression-type: none
```

---

## 性能指标

### Kafka延迟

| 场景 | 延迟 |
|------|------|
| 单机Kafka | ~0.5ms |
| 3节点集群 | ~1-2ms |
| 跨数据中心 | ~5-10ms |

### 端到端延迟

```
用户下单 → OMS处理 → Kafka → Match Engine → 撮合完成
         ↑~1ms    ↑~0.5ms   ↑~2ms      ↑~0.1ms
         
总延迟：~3.6ms（完全可接受）
```

---

## 灾备与重放

### 重放订单（灾备恢复）

```bash
# Match Engine从头开始重放订单事件
# auto-offset-reset=earliest
# 从partition 0, offset 0开始消费

Match Engine启动 → 从Kafka重放所有OrderEvent
                → 重建OrderBook内存状态
                → 恢复到最新状态
```

### 对账（订单审计）

```bash
# 审计OMS订单与Match Engine成交记录
# 通过重放order-event和trade-event

审计系统 → 重放order-event-BTCUSDT
        → 重放trade-event
        → 对比OMS数据库
        → 生成对账报告
```

---

## 为什么必须是双通道？

### ❌ 错误做法：单通道双向通信

```
OMS ↔ Kafka ↔ Match Engine
```

**问题**：
- 撮合输入和输出混在一起
- 无法独立重放
- 无法独立对账
- 违反单向数据流原则

### ✅ 正确做法：双通道单向数据流

```
OMS → Kafka (order-event) → Match Engine
                             ↓
OMS ← Kafka (order-state) ← Match Engine
                             ↓
Ledger ← Kafka (trade-event) ← Match Engine
```

**优势**：
- ✅ 撮合输入（order-event）可独立重放
- ✅ 撮合输出（trade-event/order-state）可独立审计
- ✅ 单向数据流，职责清晰
- ✅ Match Engine只读order-event，只写trade-event/order-state
- ✅ 真正的Event Sourcing架构

---

## 总结

| 特性 | 设计 |
|------|------|
| **OMS → Match** | Kafka单向顺序日志 |
| **Match → OMS** | Kafka独立回传通道 |
| **Match → 全系统** | Kafka成交事件广播 |
| **顺序保证** | 单分区 + 单线程消费 |
| **可重放** | offset从0开始重放 |
| **可灾备** | 重建OrderBook内存态 |
| **可审计** | 对账order-event vs trade-event |
| **延迟** | ~0.5-2ms（完全可接受）|

---

## 面试回答模板

> 我们采用了交易所级双通道Kafka架构：
> 
> **通道1（OMS → Match）**：单向顺序日志
> - Topic: `order-event-{symbol}`（单分区）
> - 保证订单顺序、可重放、可灾备
> 
> **通道2（Match → 全系统）**：独立回传通道
> - `order-state-{symbol}`：订单状态回传OMS
> - `trade-event`：成交事件广播给Ledger/Position/Clearing
> 
> 核心思想：撮合输入和输出必须是两条独立、可重放的事件流。
> 
> 延迟：Kafka ~0.5-2ms，完全满足交易所性能要求。

---

**这就是Binance/OKX/Bybit同级别的架构！** 🚀

