# match-engine-core 与 market-price-core 耦合分析报告

## 执行摘要

经过代码审查，发现 **match-engine-core** 与 **market-price-core** 在架构上是**解耦的**（通过 Kafka 事件流），但存在**数据流配置不匹配**的问题，导致数据无法正常消费。

---

## 1. 架构耦合分析

### ✅ 架构解耦（符合设计）

两个服务通过 Kafka 事件流解耦，符合交易所级分层架构：

```
match-engine-core (撮合引擎)
    ↓ Kafka: trade-event
market-price-core (行情计算引擎)
    ↓ Kafka: market-events
public-push-core (行情推送系统)
```

**结论**：架构设计正确，无直接依赖。

---

## 2. 数据流问题分析

### ❌ 问题1：Topic 名称不匹配

| 服务 | Topic 名称 | 配置位置 |
|------|-----------|---------|
| **match-engine-core** | `trade-event` | `TradePublisher.java:78` (硬编码) |
| **market-price-core** | `match-event-topic` | `application.yml:104` (配置) |

**影响**：market-price-core 无法消费到 match-engine-core 发布的消息。

**代码证据**：

```78:78:match-engine-core/src/main/java/com/exchange/match/publisher/TradePublisher.java
            String topic = "trade-event";
```

```104:104:market-price-core/src/main/resources/application.yml
    match-event: match-event-topic
```

```49:49:market-price-core/src/main/java/com/exchange/market/consumer/MatchEventConsumer.java
        topics = "${kafka.topic.match-event:match-event-topic}",
```

---

### ❌ 问题2：数据格式不匹配

#### match-engine-core 发布格式

`TradePublisher` 发布的是 `Trade` 对象的 JSON 序列化（或 Protobuf）：

```java
// Trade 对象结构
{
  "tradeId": "xxx",
  "matchSequence": 123,
  "symbol": "BTCUSDT",
  "makerOrderId": 456,
  "takerOrderId": 789,
  "price": "50000.00",  // BigDecimal
  "quantity": "0.01",    // BigDecimal
  "isMakerBuy": true,
  "tradeTime": 1704067200000
}
```

#### market-price-core 期望格式

`MatchEventConsumer` 期望的格式包含 `eventType` 字段：

```90:106:market-price-core/src/main/java/com/exchange/market/consumer/MatchEventConsumer.java
    private void processTradeEvent(String json) {
        try {
            JSONObject event = JSON.parseObject(json);
            String eventType = event.getString("eventType");
            
            if (!"TRADE".equals(eventType)) {
                return;
            }
            
            String symbol = event.getString("symbol");
            long sequence = event.getLongValue("sequence");
            long tradeId = event.getLongValue("tradeId");
            long price = event.getLongValue("price");
            long quantity = event.getLongValue("quantity");
            boolean isBuyerMaker = event.getBooleanValue("isBuyerMaker");
            long timestamp = event.getLongValue("timestamp");
```

**问题**：
1. `Trade` 对象没有 `eventType` 字段
2. `Trade.price` 是 `BigDecimal`（字符串），但消费者期望 `long`
3. `Trade.quantity` 是 `BigDecimal`（字符串），但消费者期望 `long`
4. `Trade` 没有 `timestamp` 字段（有 `tradeTime`）
5. `Trade` 没有 `isBuyerMaker` 字段（有 `isMakerBuy`）

---

### ❌ 问题3：序列化协议不匹配

| 服务 | 序列化方式 | 配置 |
|------|-----------|------|
| **match-engine-core** | `byte[]` (JSON 或 Protobuf) | `KafkaTemplate<String, byte[]>` |
| **market-price-core** | `String` (JSON) | `ConsumerRecord<String, String>` |

**代码证据**：

```49:49:match-engine-core/src/main/java/com/exchange/match/publisher/TradePublisher.java
    private KafkaTemplate<String, byte[]> kafkaTemplate;
```

```53:53:market-price-core/src/main/java/com/exchange/market/consumer/MatchEventConsumer.java
    public void onTradeEvent(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
```

---

## 3. 实际调用链验证

### ✅ 发布链路正常

```
DisruptorEngine
  → MatchingProcessor.onEvent()
    → MatchingProcessor.handleSubmit()
      → OrderBook.addOrder()
        → TradePublisher.publishTrade()  ✅ 正确调用
```

**代码证据**：

```67:67:match-engine-core/src/main/java/com/exchange/match/disruptor/DisruptorEngine.java
        disruptor.handleEventsWith(matchingProcessor);
```

```109:109:match-engine-core/src/main/java/com/exchange/match/processor/MatchingProcessor.java
            tradePublisher.publishTrade(trade);
```

---

## 4. 问题总结

| 问题 | 严重程度 | 影响 |
|------|---------|------|
| Topic 名称不匹配 | 🔴 **严重** | 数据无法消费 |
| 数据格式不匹配 | 🔴 **严重** | 解析失败 |
| 序列化协议不匹配 | 🔴 **严重** | 反序列化失败 |

**总体结论**：虽然架构解耦，但**数据流配置不匹配，导致数据无法正常消费**。

---

## 5. 修复建议

### 方案1：统一 Topic 名称（推荐）

**修改 match-engine-core**：

```yaml
# application.yml
match:
  kafka:
    topic:
      trade-event: trade-event  # 或改为 match-event-topic
```

**修改 TradePublisher**：

```java
@Value("${match.kafka.topic.trade-event:trade-event}")
private String tradeEventTopic;

public void publishTrade(Trade trade) {
    String topic = tradeEventTopic;  // 使用配置
    // ...
}
```

**修改 market-price-core**：

```yaml
# application.yml
kafka:
  topic:
    match-event: trade-event  # 与 match-engine-core 一致
```

---

### 方案2：统一数据格式（推荐）

**选项A：修改 TradePublisher，包装为事件格式**

```java
public void publishTrade(Trade trade) {
    // 构建事件格式
    Map<String, Object> event = new HashMap<>();
    event.put("eventType", "TRADE");
    event.put("symbol", trade.getSymbol());
    event.put("sequence", trade.getMatchSequence());
    event.put("tradeId", trade.getTradeId());
    event.put("price", trade.getPrice().longValue());  // 转换为 long
    event.put("quantity", trade.getQuantity().longValue());
    event.put("isBuyerMaker", !trade.getIsMakerBuy());
    event.put("timestamp", trade.getTradeTime());
    
    String json = objectMapper.writeValueAsString(event);
    byte[] value = json.getBytes(StandardCharsets.UTF_8);
    // ...
}
```

**选项B：修改 MatchEventConsumer，适配 Trade 格式**

```java
private void processTradeEvent(String json) {
    try {
        // 直接解析 Trade 对象
        Trade trade = JSON.parseObject(json, Trade.class);
        
        // 转换为内部格式
        engineService.onTrade(
            trade.getSymbol(),
            trade.getPrice().longValue(),
            trade.getQuantity().longValue(),
            trade.getTradeTime(),
            !trade.getIsMakerBuy()
        );
    } catch (Exception e) {
        log.error("[Consumer] Failed to parse trade event: {}", json, e);
    }
}
```

---

### 方案3：统一序列化协议

**修改 market-price-core 消费者配置**：

```java
// 使用 ByteArrayDeserializer
@KafkaListener(
    topics = "${kafka.topic.match-event:trade-event}",
    containerFactory = "matchEventKafkaListenerContainerFactory"  // 需要配置 ByteArrayDeserializer
)
public void onTradeEvent(List<ConsumerRecord<String, byte[]>> records, Acknowledgment ack) {
    for (ConsumerRecord<String, byte[]> record : records) {
        String json = new String(record.value(), StandardCharsets.UTF_8);
        processTradeEvent(json);
    }
    ack.acknowledge();
}
```

---

## 6. 验证方法

### 步骤1：检查 Kafka Topic

```bash
# 查看 match-engine-core 发布的 topic
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic trade-event \
  --from-beginning

# 查看 market-price-core 订阅的 topic
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic match-event-topic \
  --from-beginning
```

### 步骤2：检查消息格式

```bash
# 查看实际消息内容
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic trade-event \
  --from-beginning \
  --property print.key=true \
  --property print.value=true
```

### 步骤3：检查消费者组状态

```bash
# 查看 market-price-core 消费者组 lag
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group market-price-service \
  --describe
```

---

## 7. 结论

1. **架构解耦**：✅ 正确，通过 Kafka 事件流解耦
2. **数据流配置**：✅ **已修复**，Topic 名称、数据格式、序列化协议已统一
3. **修复状态**：✅ **已完成**

---

## 8. 修复总结

### ✅ 已完成的修复

#### 1. match-engine-core 修复
- ✅ **TradePublisher.java**：
  - 支持配置化 topic 名称（`${match.kafka.topic.trade-event:trade-event}`）
  - 统一数据格式：包装为事件格式，包含 `eventType: "TRADE"` 等字段
  - 价格/数量转换：使用 `Money.of()` 将 `BigDecimal` 转换为 `long`
  - 时间戳映射：`tradeTime` → `timestamp`
  - `isBuyerMaker` 推导：从 `isMakerBuy` 计算

- ✅ **application.yml**：
  - 添加 `match.kafka.topic.trade-event` 配置项

#### 2. market-price-core 修复
- ✅ **application.yml**：
  - 更新 `kafka.topic.match-event` 为 `trade-event`（与 match-engine-core 一致）

- ✅ **KafkaConfig.java**：
  - 修改 `matchEventConsumerFactory()` 使用 `ByteArrayDeserializer`
  - 修改 `matchEventKafkaListenerContainerFactory()` 泛型为 `<String, byte[]>`

- ✅ **MatchEventConsumer.java**：
  - 修改 `onTradeEvent()` 方法签名：`ConsumerRecord<String, byte[]>`
  - 添加 byte[] 到 String 的转换（UTF-8 解码）

### 📋 修复后的数据流

```
match-engine-core
  ↓ TradePublisher.publishTrade()
  ↓ 构建事件格式: {eventType: "TRADE", symbol, price (long), quantity (long), ...}
  ↓ JSON 序列化 → byte[]
  ↓ Kafka: trade-event
  ↓
market-price-core
  ↓ MatchEventConsumer.onTradeEvent()
  ↓ byte[] → String (UTF-8)
  ↓ JSON 解析
  ↓ MarketDataEngineService.onTrade()
```

### ✅ 验证要点

1. **Topic 名称**：两个服务都使用 `trade-event`
2. **数据格式**：包含 `eventType: "TRADE"`，价格/数量为 `long`
3. **序列化协议**：match-engine-core 发布 `byte[]`，market-price-core 消费 `byte[]` 并转换为 `String`

**建议**：重启两个服务，验证数据流是否正常消费。

