# 交易流程完整性检查报告

## 📋 流程概览

### 流程1：资金流程（下单→撮合→账本→快照）

```
api-gateway → oms-core → kafka → match-core → ledger-core → kafka → snapshot-account-service/snapshot-position-service
```

### 流程2：行情流程（下单→撮合→行情计算→推送）

```
api-gateway → oms-core → kafka → match-core → kafka → market-price-core → kafka → public-push-core → client
```

---

## ✅ 流程1：资金流程检查

### 1.1 API Gateway → OMS Core

**状态**: ✅ **已实现**

- **文件**: `api-gateway/src/main/java/com/exchange/gateway/controller/OrderRouteController.java`
- **接口**: `POST /api/v1/oms/order/submit`
- **功能**: 路由订单请求到 OMS Core

### 1.2 OMS Core → Kafka (order-events)

**状态**: ✅ **已实现**

- **文件**: `oms-core/src/main/java/com/exchange/oms/publisher/OrderEventPublisher.java`
- **Topic**: `order-events`
- **Key**: `symbol:orderId`
- **功能**: 发布订单事件到 Kafka

```java
@KafkaListener(topics = "order-events", groupId = "match-engine-group")
public void publishOrderEvent(OrderEventCommand command) {
    kafkaTemplate.send("order-events", key, value);
}
```

### 1.3 Kafka → Match Engine

**状态**: ✅ **已实现**

- **文件**: `match-engine-core/src/main/java/com/exchange/match/consumer/OrderEventConsumer.java`
- **Topic**: `order-events`
- **Group**: `match-engine-group`
- **功能**: 消费订单事件，提交到 Disruptor 撮合

```java
@KafkaListener(topics = "order-events", groupId = "match-engine-group", concurrency = "1")
public void consumeOrderEvent(@Payload byte[] message) {
    disruptorEngine.submitOrderCommand(command);
}
```

### 1.4 Match Engine → Kafka (trade-event)

**状态**: ✅ **已实现**

- **文件**: `match-engine-core/src/main/java/com/exchange/match/publisher/TradePublisher.java`
- **Topic**: `trade-event` (可配置)
- **功能**: 发布成交事件到 Kafka

```java
public void publishTrade(Trade trade) {
    kafkaTemplate.send("trade-event", tradeId, serializeTradeEvent(trade));
}
```

### 1.5 Kafka → Ledger Core

**状态**: ✅ **已实现**

- **文件**: `ledger-core/src/main/java/com/exchange/ledger/consumer/TradeEventConsumer.java`
- **Topic**: `trade-event`
- **Group**: `ledger-service`
- **功能**: 消费成交事件，写入双录分录

```java
@KafkaListener(topics = "trade-event", groupId = "ledger-service", concurrency = "4")
public void consumeTradeEvent(@Payload byte[] messageBytes) {
    ledgerService.applyTrade(trade);
}
```

### 1.6 Ledger Core → Kafka (trade-entry-{symbol})

**状态**: ✅ **已实现**

- **文件**: `ledger-core/src/main/java/com/exchange/ledger/publisher/LedgerEventPublisher.java`
- **Topic**: `trade-entry-{symbol}` (例如: `trade-entry-BTCUSDT`)
- **功能**: 发布账本分录事件

```java
public void publishTradeEntry(TradeEntryEvent event) {
    String topic = "trade-entry-" + event.getSymbol();
    kafkaTemplate.send(topic, symbol, message);
}
```

### 1.7 Kafka → Snapshot Account Service

**状态**: ✅ **已实现**

- **文件**: `snapshot-account-core/src/main/java/com/exchange/snapshot/consumer/TradeEntryEventConsumer.java`
- **Topic**: `trade-entry-{symbol}` (配置: `${snapshot.kafka.topics}`)
- **Group**: `snapshot-service`
- **功能**: 消费账本分录事件，更新账户快照

```java
@KafkaListener(topics = "${snapshot.kafka.topics}", groupId = "snapshot-service", concurrency = "1")
public void consumeTradeEntry(@Payload String message) {
    accountSnapshotService.onTradeEntryEvent(event);
}
```

### 1.8 Kafka → Position Snapshot Service

**状态**: ✅ **已实现**

- **文件**: `position-snapshot-core/src/main/java/com/exchange/position/consumer/TradeEntryEventConsumer.java`
- **Topic**: `trade-entry-.*` (Pattern匹配)
- **Group**: `position-service`
- **功能**: 消费账本分录事件，更新持仓快照

```java
@KafkaListener(topicPattern = "trade-entry-.*", groupId = "position-service", concurrency = "1")
public void consumeTradeEntryEvent(@Payload String message) {
    positionService.onTradeEntryEvent(event);
}
```

---

## ✅ 流程2：行情流程检查

### 2.1 API Gateway → OMS Core → Kafka → Match Engine

**状态**: ✅ **已实现** (同流程1的1.1-1.3)

### 2.2 Match Engine → Kafka (trade-event)

**状态**: ✅ **已实现** (同流程1的1.4)

### 2.3 Kafka → Market Price Core

**状态**: ✅ **已实现**

- **文件**: `market-price-core/src/main/java/com/exchange/market/consumer/MatchEventConsumer.java`
- **Topic**: `trade-event` (配置: `${kafka.topic.match-event:trade-event}`)
- **Group**: `market-price-service`
- **功能**: 消费成交事件，计算行情数据（K线、Ticker、深度等）

```java
@KafkaListener(topics = "${kafka.topic.match-event:trade-event}", 
               groupId = "${spring.application.name:market-price-service}")
public void onTradeEvent(List<ConsumerRecord<String, byte[]>> records) {
    engineService.onTrade(symbol, trade);
}
```

### 2.4 Market Price Core → Kafka (market.*)

**状态**: ✅ **已实现**

- **文件**: `market-price-core/src/main/java/com/exchange/market/publisher/MarketDataPublisher.java`
- **Topics**:
  - `market.trade.{symbol}` - 实时成交
  - `market.depth.{symbol}` - 深度更新
  - `market.kline.{symbol}.{interval}` - K线数据
  - `market.ticker.{symbol}` - 24h统计
- **功能**: 发布行情数据到 Kafka

```java
public void publishTrade(String symbol, Trade trade) {
    String topic = "market.trade." + symbol;
    kafkaTemplate.send(topic, symbol, json);
}

public void publishDepth(String symbol, DepthUpdate depthUpdate) {
    String topic = "market.depth." + symbol;
    kafkaTemplate.send(topic, symbol, json);
}
```

### 2.5 Kafka → Public Push Core

**状态**: ✅ **已实现**

- **文件**: `public-push-core/src/main/java/com/exchange/push/service/KafkaConsumerManager.java`
- **Topics**: 动态订阅（根据客户端订阅需求）
  - `market.trade.{symbol}`
  - `market.depth.{symbol}`
  - `market.kline.{symbol}.{interval}`
  - `market.ticker.{symbol}`
- **功能**: 动态创建消费者，消费行情数据

```java
public void ensureConsumerStarted(String channel) {
    String topic = channelToTopic(channel);  // channel -> topic 映射
    MessageListenerContainer container = kafkaListenerContainerFactory.createContainer(topic);
    container.getContainerProperties().setMessageListener(record -> {
        onMessage(channel, record);
    });
    container.start();
}
```

### 2.6 Public Push Core → Client (WebSocket)

**状态**: ✅ **已实现**

- **文件**: `public-push-core/src/main/java/com/exchange/push/handler/PublicWebSocketHandler.java`
- **功能**: 通过 WebSocket 推送行情数据到客户端

---

## 🔍 关键配置检查

### Kafka Topics 配置

需要确保以下 Topics 已创建：

```bash
# 订单流程
order-events                    # OMS → Match Engine
trade-event                     # Match Engine → Ledger/Market Price
trade-entry-{symbol}            # Ledger → Snapshot Services

# 行情流程
market.trade.{symbol}           # Market Price → Public Push
market.depth.{symbol}           # Market Price → Public Push
market.kline.{symbol}.{interval} # Market Price → Public Push
market.ticker.{symbol}          # Market Price → Public Push
```

### 服务配置检查

#### OMS Core
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
```

#### Match Engine Core
```yaml
match:
  kafka:
    topic:
      trade-event: trade-event
```

#### Ledger Core
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
```

#### Market Price Core
```yaml
kafka:
  topic:
    match-event: trade-event
```

#### Public Push Core
```yaml
public-push:
  kafka:
    topics:
      trade: market.trade.
      depth: market.depth.
      kline: market.kline.
      ticker: market.ticker.
```

---

## ⚠️ 潜在问题与修复建议

### 1. Topic 命名不一致

**问题**: 
- OMS 发布到 `order-events`（统一Topic）
- Match Engine 消费 `order-events`
- 但文档中提到 `order-event-{symbol}`（按Symbol分Topic）

**建议**: 
- 当前实现使用统一Topic `order-events`，这是可行的
- 如需按Symbol分Topic，需要修改 OMS 的 `OrderEventPublisher` 和 Match Engine 的 `OrderEventConsumer`

### 2. Market Price Core 深度数据来源

**问题**: 
- Market Price Core 消费 `trade-event` 可以计算成交和K线
- 但深度数据（OrderBook）需要从 Match Engine 获取

**当前实现**: 
- Market Price Core 还消费 `orderbook-delta-{symbol}` Topic
- 需要确认 Match Engine 是否发布深度更新事件

**建议**: 
- 检查 Match Engine 的 `DepthPublisher` 是否正常工作
- 或者 Market Price Core 直接从 Match Engine 的 OrderBook 获取快照

### 3. Public Push 动态消费者

**问题**: 
- Public Push 使用动态消费者，需要确保 Topic 存在
- 如果客户端订阅了不存在的Symbol，可能导致消费者启动失败

**建议**: 
- 添加 Topic 存在性检查
- 或者使用 Topic Pattern 自动创建消费者

---

## ✅ 总结

### 流程完整性

| 流程环节 | 状态 | 备注 |
|---------|------|------|
| API Gateway → OMS | ✅ | 已实现 |
| OMS → Kafka (order-events) | ✅ | 已实现 |
| Kafka → Match Engine | ✅ | 已实现 |
| Match Engine → Kafka (trade-event) | ✅ | 已实现 |
| Kafka → Ledger Core | ✅ | 已实现 |
| Ledger Core → Kafka (trade-entry) | ✅ | 已实现 |
| Kafka → Snapshot Account | ✅ | 已实现 |
| Kafka → Position Snapshot | ✅ | 已实现 |
| Kafka → Market Price Core | ✅ | 已实现 |
| Market Price Core → Kafka (market.*) | ✅ | 已实现 |
| Kafka → Public Push Core | ✅ | 已实现（动态消费者） |
| Public Push Core → Client | ✅ | 已实现（WebSocket） |

### 结论

**✅ 两个流程理论上都可以走通！**

但需要注意：
1. **Kafka Topics 必须已创建**（使用 `init_kafka.sh` 脚本）
2. **所有服务必须正常运行**
3. **配置必须正确**（Kafka地址、Topic名称等）

### 测试建议

1. **端到端测试**：
   ```bash
   # 1. 下单
   curl -X POST http://localhost:8082/api/v1/oms/order/submit ...
   
   # 2. 检查 Kafka Topics 是否有消息
   kafka-console-consumer --bootstrap-server localhost:9092 --topic trade-event
   
   # 3. 检查 Ledger 是否有分录
   SELECT * FROM t_ledger_entry WHERE ref_trade_id = 'xxx';
   
   # 4. 检查 Snapshot 是否更新
   SELECT * FROM account_snapshot WHERE user_id = xxx;
   
   # 5. 检查行情是否推送
   # 连接 WebSocket: ws://localhost:8096/ws
   ```

2. **监控指标**：
   - Kafka Consumer Lag
   - 服务日志（查看是否有错误）
   - 数据库数据（验证数据一致性）

---

*最后更新: 2026-02-22*

