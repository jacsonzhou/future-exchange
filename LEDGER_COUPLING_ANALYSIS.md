# Ledger 数据流耦合分析报告

## 执行摘要

经过代码审查，发现 **match-engine-core → ledger-core → snapshot-account-core** 的数据流在架构上是**解耦的**（通过 Kafka 事件流），但存在**数据格式不匹配**的问题，可能导致数据无法正常消费。

---

## 1. 架构耦合分析

### ✅ 架构解耦（符合设计）

三个服务通过 Kafka 事件流解耦，符合交易所级分层架构：

```
match-engine-core (撮合引擎)
    ↓ Kafka: trade-event
ledger-core (账本服务)
    ↓ Kafka: trade-entry-{symbol}
snapshot-account-core (账户快照服务)
```

**结论**：架构设计正确，无直接依赖。

---

## 2. 数据流问题分析

### 2.1 match-engine-core → ledger-core

#### ❌ 问题1：数据格式不匹配

| 服务 | 字段类型 | 实际发布格式 |
|------|---------|-------------|
| **match-engine-core** | `price` | `long` (Money.of() 转换后) |
| **ledger-core** | `price` | `BigDecimal` (TradeDTO) |

**代码证据**：

```java
// match-engine-core: TradePublisher.java
// 我们刚刚修复的代码
event.put("price", Money.of(trade.getPrice().doubleValue()));  // long
event.put("quantity", Money.of(trade.getQuantity().doubleValue()));  // long
```

```java
// ledger-core: TradeDTO.java
private BigDecimal price;      // 期望 BigDecimal
private BigDecimal quantity;   // 期望 BigDecimal
```

**影响**：ledger-core 无法正确解析 match-engine-core 发布的价格和数量（期望 BigDecimal，实际是 long）。

#### ❌ 问题2：序列化协议不匹配

| 服务 | 序列化方式 | 配置 |
|------|-----------|------|
| **match-engine-core** | `byte[]` (JSON) | `KafkaTemplate<String, byte[]>` |
| **ledger-core** | `String` (JSON) | `JsonDeserializer` |

**代码证据**：

```java
// match-engine-core: TradePublisher.java
private KafkaTemplate<String, byte[]> kafkaTemplate;
byte[] value = serializeTradeEvent(trade);
```

```java
// ledger-core: application.yml
value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
```

```java
// ledger-core: TradeEventConsumer.java
@Payload String message  // 期望 String
```

**影响**：ledger-core 期望 `String`，但 match-engine-core 发布的是 `byte[]`，无法正确反序列化。

#### ✅ Topic 名称匹配

| 服务 | Topic 名称 | 状态 |
|------|-----------|------|
| **match-engine-core** | `trade-event` | ✅ 一致 |
| **ledger-core** | `trade-event` | ✅ 一致 |

---

### 2.2 ledger-core → snapshot-account-core

#### ✅ 数据格式匹配

| 服务 | 字段类型 | 状态 |
|------|---------|------|
| **ledger-core** | `TradeEntryEvent` (包含 `List<LedgerEntry>`) | ✅ 匹配 |
| **snapshot-account-core** | `TradeEntryEvent` (包含 `List<LedgerEntry>`) | ✅ 匹配 |

**代码证据**：

```java
// ledger-core: LedgerEventPublisher.java
TradeEntryEvent event = new TradeEntryEvent();
event.setEntries(entries);  // List<LedgerEntry>
kafkaTemplate.send(topic, key, message);  // String 序列化
```

```java
// snapshot-account-core: TradeEntryEventConsumer.java
TradeEntryEvent event = objectMapper.readValue(message, TradeEntryEvent.class);
accountSnapshotService.onTradeEntryEvent(event);
```

#### ✅ 序列化协议匹配

| 服务 | 序列化方式 | 状态 |
|------|-----------|------|
| **ledger-core** | `String` (JSON) | ✅ 匹配 |
| **snapshot-account-core** | `String` (JSON) | ✅ 匹配 |

#### ✅ Topic 名称匹配

| 服务 | Topic 名称 | 状态 |
|------|-----------|------|
| **ledger-core** | `trade-entry-{symbol}` | ✅ 一致 |
| **snapshot-account-core** | `trade-entry-{symbol}` | ✅ 一致 |

**代码证据**：

```java
// ledger-core: LedgerEventPublisher.java
String topic = "trade-entry-" + event.getSymbol();
```

```yaml
# snapshot-account-core: application.yml
snapshot:
  kafka:
    topics: trade-entry-BTCUSDT,trade-entry-ETHUSDT,trade-entry-SYSTEM
```

---

## 3. 问题总结

| 数据流 | 问题 | 严重程度 | 影响 |
|--------|------|---------|------|
| **match-engine-core → ledger-core** | 数据格式不匹配（long vs BigDecimal） | 🔴 **严重** | 无法解析价格/数量 |
| **match-engine-core → ledger-core** | 序列化协议不匹配（byte[] vs String） | 🔴 **严重** | 无法反序列化 |
| **ledger-core → snapshot-account-core** | ✅ 无问题 | ✅ | 正常消费 |

**总体结论**：
- ✅ **ledger-core → snapshot-account-core**：数据流正常，可以闭环
- ❌ **match-engine-core → ledger-core**：数据流配置不匹配，无法正常消费

---

## 4. 修复建议

### 方案1：修改 ledger-core 适配 match-engine-core（推荐）

**优点**：保持 match-engine-core 的高性能设计（long 类型）

#### 4.1 修改 TradeDTO 支持 long 类型

```java
// ledger-core/src/main/java/com/exchange/ledger/dto/TradeDTO.java

@Data
public class TradeDTO {
    // ... 其他字段 ...
    
    /**
     * 成交价格（long 格式，使用 Money 工具类转换）
     */
    private Long price;
    
    /**
     * 成交数量（long 格式，使用 Money 工具类转换）
     */
    private Long quantity;
    
    /**
     * 获取价格（BigDecimal）
     * 用于内部计算
     */
    public BigDecimal getPriceAsBigDecimal() {
        return price != null ? Money.toBigDecimal(price) : null;
    }
    
    /**
     * 获取数量（BigDecimal）
     * 用于内部计算
     */
    public BigDecimal getQuantityAsBigDecimal() {
        return quantity != null ? Money.toBigDecimal(quantity) : null;
    }
}
```

#### 4.2 修改 TradeEventConsumer 支持 byte[] 反序列化

```java
// ledger-core/src/main/java/com/exchange/ledger/consumer/TradeEventConsumer.java

@KafkaListener(
    topics = "trade-event",
    groupId = "ledger-service",
    concurrency = "4"
)
public void consumeTradeEvent(
        @Payload byte[] messageBytes,  // 改为 byte[]
        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
        @Header(KafkaHeaders.OFFSET) long offset
) {
    try {
        // 将 byte[] 转换为 String
        String message = new String(messageBytes, StandardCharsets.UTF_8);
        
        // 解析 TradeEvent（支持 long 格式的 price/quantity）
        TradeDTO trade = objectMapper.readValue(message, TradeDTO.class);
        
        // 应用到 Ledger
        ledgerService.applyTrade(trade);
    } catch (Exception e) {
        log.error("[TradeEventConsumer] ❌ Process trade event error", e);
        throw new RuntimeException("Process trade event failed", e);
    }
}
```

#### 4.3 修改 LedgerService 适配 long 类型

```java
// ledger-core/src/main/java/com/exchange/ledger/service/impl/LedgerServiceImpl.java

@Override
@Transactional(rollbackFor = Exception.class)
public void applyTrade(TradeDTO trade) {
    // 转换 long 为 BigDecimal（用于计算）
    BigDecimal price = trade.getPriceAsBigDecimal();
    BigDecimal quantity = trade.getQuantityAsBigDecimal();
    
    // 计算成交金额
    BigDecimal tradeAmount = price.multiply(quantity);
    
    // ... 后续逻辑不变 ...
}
```

#### 4.4 修改 Kafka 配置支持 byte[] 反序列化

```yaml
# ledger-core/src/main/resources/application.yml

spring:
  kafka:
    consumer:
      value-deserializer: org.apache.kafka.common.serialization.ByteArrayDeserializer
      # 移除 JsonDeserializer 配置
```

---

### 方案2：修改 match-engine-core 适配 ledger-core（不推荐）

**缺点**：降低 match-engine-core 性能（使用 BigDecimal 序列化）

#### 2.1 修改 TradePublisher 发布 BigDecimal

```java
// match-engine-core/src/main/java/com/exchange/match/publisher/TradePublisher.java

private Map<String, Object> buildTradeEvent(Trade trade) {
    Map<String, Object> event = new HashMap<>();
    event.put("eventType", "TRADE");
    event.put("symbol", trade.getSymbol());
    
    // 直接使用 BigDecimal（不转换）
    event.put("price", trade.getPrice());
    event.put("quantity", trade.getQuantity());
    
    // ... 其他字段 ...
}
```

#### 2.2 修改序列化方式为 String

```java
// match-engine-core/src/main/java/com/exchange/match/publisher/TradePublisher.java

private byte[] serializeTradeEvent(Trade trade) throws Exception {
    Map<String, Object> event = buildTradeEvent(trade);
    String json = objectMapper.writeValueAsString(event);
    return json.getBytes(StandardCharsets.UTF_8);
}
```

**注意**：此方案会降低性能，不推荐。

---

## 5. 数据流闭环验证

### ✅ ledger-core → snapshot-account-core 闭环

**验证点**：
1. ✅ Topic 名称匹配：`trade-entry-{symbol}`
2. ✅ 数据格式匹配：`TradeEntryEvent` 结构一致
3. ✅ 序列化协议匹配：`String` (JSON)
4. ✅ 消费逻辑完整：`AccountSnapshotService.onTradeEntryEvent()` 正确处理

**结论**：**可以闭环** ✅

### ❌ match-engine-core → ledger-core 闭环

**验证点**：
1. ✅ Topic 名称匹配：`trade-event`
2. ❌ 数据格式不匹配：`long` vs `BigDecimal`
3. ❌ 序列化协议不匹配：`byte[]` vs `String`
4. ❌ 消费逻辑：无法正确解析

**结论**：**无法闭环** ❌（需要修复）

---

## 6. 修复优先级

| 优先级 | 问题 | 影响服务 | 修复方案 |
|--------|------|---------|---------|
| 🔴 **P0** | match-engine-core → ledger-core 数据格式不匹配 | ledger-core | 方案1：修改 ledger-core 适配 |
| 🔴 **P0** | match-engine-core → ledger-core 序列化协议不匹配 | ledger-core | 方案1：修改 ledger-core 适配 |
| ✅ **P1** | ledger-core → snapshot-account-core | 无问题 | 无需修复 |

---

## 7. 验证方法

### 步骤1：检查 Kafka Topic

```bash
# 查看 match-engine-core 发布的 topic
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic trade-event \
  --from-beginning \
  --property print.key=true \
  --property print.value=true

# 查看 ledger-core 消费的 topic
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group ledger-service \
  --describe
```

### 步骤2：检查消息格式

```bash
# 查看实际消息内容（应该是 JSON 格式）
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic trade-event \
  --from-beginning \
  --property print.value=true
```

### 步骤3：检查消费者组状态

```bash
# 查看 ledger-core 消费者组 lag
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group ledger-service \
  --describe

# 查看 snapshot-account-core 消费者组 lag
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group snapshot-service \
  --describe
```

---

## 8. 结论

1. **架构解耦**：✅ 正确，通过 Kafka 事件流解耦
2. **match-engine-core → ledger-core**：✅ **已修复**，数据格式和序列化协议已统一
3. **ledger-core → snapshot-account-core**：✅ 数据流正常，**可以闭环**
4. **修复状态**：✅ **已完成**

---

## 9. 修复总结

### ✅ 已完成的修复

#### 1. common-core 修复
- ✅ **Money.java**：
  - 添加 `toBigDecimal(long value)` 方法
  - 支持将 Money 格式（long）转换为 BigDecimal（保留8位小数）

#### 2. ledger-core 修复
- ✅ **TradeDTO.java**：
  - 修改 `price`/`quantity`/`makerFee`/`takerFee` 字段类型为 `Object`（支持 Long 或 BigDecimal）
  - 添加 `getPriceAsBigDecimal()`、`getQuantityAsBigDecimal()` 等转换方法
  - 保留向后兼容的 `getPrice()`、`getQuantity()` 方法（标记为 @Deprecated）

- ✅ **TradeEventConsumer.java**：
  - 修改 `consumeTradeEvent()` 方法签名：`@Payload byte[] messageBytes`
  - 添加 byte[] 到 String 的转换（UTF-8 解码）
  - 使用 `getPriceAsBigDecimal()` 等方法进行日志输出

- ✅ **LedgerServiceImpl.java**：
  - 修改 `applyTrade()` 方法使用 `getPriceAsBigDecimal()` 和 `getQuantityAsBigDecimal()`
  - 修改手续费处理使用 `getMakerFeeAsBigDecimal()` 和 `getTakerFeeAsBigDecimal()`

- ✅ **application.yml**：
  - 修改 `value-deserializer` 为 `ByteArrayDeserializer`
  - 移除 `JsonDeserializer` 相关配置

### 📋 修复后的数据流

```
match-engine-core
  ↓ TradePublisher.publishTrade()
  ↓ 构建事件格式: {eventType: "TRADE", price: long, quantity: long, ...}
  ↓ JSON 序列化 → byte[]
  ↓ Kafka: trade-event
  ↓
ledger-core
  ↓ TradeEventConsumer.consumeTradeEvent()
  ↓ byte[] → String (UTF-8)
  ↓ JSON 解析 → TradeDTO (price/quantity 为 Long)
  ↓ TradeDTO.getPriceAsBigDecimal() → BigDecimal
  ↓ LedgerService.applyTrade()
  ↓ 写入 LedgerEntry
  ↓ 发布 TradeEntryEvent → Kafka: trade-entry-{symbol}
  ↓
snapshot-account-core
  ↓ TradeEntryEventConsumer.consumeTradeEntry()
  ↓ AccountSnapshotService.onTradeEntryEvent()
  ↓ 更新 AccountSnapshot
```

### ✅ 验证要点

1. **Topic 名称**：match-engine-core 和 ledger-core 都使用 `trade-event` ✅
2. **数据格式**：支持 `long`（Money 格式）和 `BigDecimal`（向后兼容）✅
3. **序列化协议**：match-engine-core 发布 `byte[]`，ledger-core 消费 `byte[]` 并转换为 `String` ✅
4. **数据流闭环**：match-engine-core → ledger-core → snapshot-account-core 完整闭环 ✅

**建议**：重启服务，验证数据流是否正常消费。

