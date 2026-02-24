# 深度数据流转分析：三个模块的数据流

## 📊 架构概览

```
Match Engine Core
    ↓ (Kafka: market.depth.raw.{symbol})
Market Price Core
    ↓ (Kafka: market.depth.{symbol})
Public Push Core
    ↓ (WebSocket)
客户端
```

---

## 🔍 详细分析

### 1. Match Engine Core → Market Price Core

#### 数据流向
- **发布方**: `match-engine-core/src/main/java/com/exchange/match/publisher/DepthPublisher.java`
- **Topic**: `market.depth.raw.{symbol}` (例如: `market.depth.raw.BTCUSDT`)
- **消费方**: `market-price-core/src/main/java/com/exchange/market/consumer/MatchEventConsumer.java`

#### 数据格式

**Match Engine 发布的数据**:
```json
{
  "e": "depthUpdate",           // 事件类型
  "E": 123456789,               // 事件时间
  "s": "BTCUSDT",               // 交易对
  "U": 1,                       // 第一个更新ID
  "u": 1,                       // 最后一个更新ID
  "isSnapshot": true,           // ⚠️ 标记为快照（全量数据）
  "b": [["40000", "1.0"], ...], // 买盘（20档）
  "a": [["40001", "1.5"], ...]  // 卖盘（20档）
}
```

#### 关键代码

**Match Engine 发布** (`DepthPublisher.java:75-135`):
```java
public void publishDepth(String symbol, OrderBook orderBook, long lastSeq) {
    // 获取深度数据（20档）- 全量数据
    Map<String, List<List<String>>> depthData = orderBook.getDepthData(20);
    
    // 构建消息
    message.put("isSnapshot", true);  // ⚠️ 标记为快照
    message.put("b", bids);           // 全量买盘
    message.put("a", asks);           // 全量卖盘
    
    // 发布到 raw topic
    String rawTopic = "market.depth.raw." + symbol;  // ⚠️ 使用 raw topic
    kafkaTemplate.send(rawTopic, key, value);
}
```

**Market Price Core 消费** (`MatchEventConsumer.java:81-99`):
```java
@KafkaListener(
    topicPattern = "market\\.depth\\.raw\\..*",  // ⚠️ 消费 raw topic
    groupId = "${spring.application.name:market-price-service}"
)
public void onDepthEvent(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
    for (ConsumerRecord<String, String> record : records) {
        processDepthEvent(record.value());  // 处理深度事件
    }
}
```

#### 数据特点

- ✅ **全量数据**: Match Engine 每次发布都是完整的 20 档深度数据
- ✅ **快照标记**: `isSnapshot: true` 表示这是全量快照
- ✅ **独立 Topic**: 使用 `market.depth.raw.*` 避免与最终输出冲突

---

### 2. Market Price Core → Public Push Core

#### 数据流向
- **发布方**: `market-price-core/src/main/java/com/exchange/market/publisher/MarketDataPublisher.java`
- **Topic**: `market.depth.{symbol}` (例如: `market.depth.BTCUSDT`)
- **消费方**: `public-push-core/src/main/java/com/exchange/push/service/KafkaConsumerManager.java`

#### 数据格式

**Market Price Core 发布的数据**:
```json
{
  "e": "depthUpdate",
  "E": 123456789,
  "s": "BTCUSDT",
  "U": 1,
  "u": 1,
  "pu": 0,                      // 上一个更新ID
  "b": [[40000, 1.0], ...],     // 买盘（long[] 格式）
  "a": [[40001, 1.5], ...]      // 卖盘（long[] 格式）
}
```

#### 关键代码

**Market Price Core 处理** (`MarketDataEngineService.java:103-136`):
```java
public void onDepthDelta(String symbol, List<long[]> bids, List<long[]> asks, 
                         long sequence, long timestamp) {
    // 1. 应用增量到内部 OrderBook
    orderBook.applyDelta(bids, asks, sequence, timestamp);
    
    // 2. 获取完整快照（20档）
    OrderBook.DepthSnapshot snapshot = orderBook.getSnapshot(20);
    
    // 3. 发布完整快照
    MarketDataPublisher.DepthUpdate update = new MarketDataPublisher.DepthUpdate();
    update.setBids(snapshot.getBids());  // 全量买盘
    update.setAsks(snapshot.getAsks());  // 全量卖盘
    update.setSnapshot(false);           // ⚠️ 标记为非快照（但实际是全量）
    
    publisher.publishDepth(symbol, update);
}
```

**Market Price Core 发布** (`MarketDataPublisher.java:292-322`):
```java
public void publishDepth(String symbol, DepthUpdate depthUpdate) {
    // 构建标准消息
    JSONObject message = buildDepthMessage(depthUpdate);
    
    // 发布到Kafka
    String topic = TOPIC_DEPTH + symbol;  // "market.depth." + symbol
    kafkaTemplate.send(topic, symbol, json);
    
    // 更新Redis快照（供public-push-core获取）
    redisTemplate.opsForValue().set(SNAPSHOT_DEPTH + symbol, json);
}
```

**Public Push Core 消费** (`KafkaConsumerManager.java:116-141`):
```java
private void onMessage(String channel, ConsumerRecord<String, String> record) {
    // 解析消息
    JSONObject message = JSON.parseObject(record.value());
    
    // 分发消息到 WebSocket
    messageDispatcher.broadcast(channel, message);
}
```

#### 数据特点

- ✅ **全量数据**: Market Price Core 每次发布都是完整的 20 档深度数据
- ⚠️ **格式转换**: 从 `List<List<String>>` 转换为 `List<long[]>`（价格和数量转为 long）
- ✅ **Redis 快照**: 同时写入 Redis，供首次订阅时获取快照

---

## 🔄 完整数据流

### 流程图

```
┌─────────────────┐
│ Match Engine    │
│   OrderBook     │
└────────┬────────┘
         │
         │ 1. 订单进入 OrderBook
         │ 2. 获取完整深度数据（20档）
         │ 3. 构建消息（isSnapshot: true）
         │
         ▼
    Kafka Topic:
  market.depth.raw.BTCUSDT
         │
         │ {"e":"depthUpdate", "isSnapshot":true, "b":[...], "a":[...]}
         │
         ▼
┌─────────────────┐
│ Market Price    │
│     Core        │
└────────┬────────┘
         │
         │ 1. 消费 raw topic
         │ 2. 解析深度数据
         │ 3. 应用增量到内部 OrderBook
         │ 4. 获取完整快照（20档）
         │ 5. 构建标准消息
         │
         ▼
    Kafka Topic:
    market.depth.BTCUSDT
         │
         │ {"e":"depthUpdate", "b":[[40000,1.0],...], "a":[[40001,1.5],...]}
         │
         ▼
┌─────────────────┐
│  Public Push    │
│     Core        │
└────────┬────────┘
         │
         │ 1. 消费 market.depth.BTCUSDT
         │ 2. 验证消息格式
         │ 3. 广播到 WebSocket 订阅者
         │
         ▼
    WebSocket
         │
         │ {"stream":"depth.BTCUSDT", "data":{...}}
         │
         ▼
┌─────────────────┐
│    客户端       │
│  trading-test   │
└─────────────────┘
```

---

## 📋 关键问题解答

### Q1: 是全量推送还是增量推送？

**答案**: **全量推送**

- **Match Engine → Market Price Core**: 每次发布都是完整的 20 档深度数据（`isSnapshot: true`）
- **Market Price Core → Public Push Core**: 每次发布都是完整的 20 档深度数据（虽然 `snapshot: false`，但数据是全量）

**原因**:
1. Match Engine 的 OrderBook 是实时状态，每次获取都是完整快照
2. Market Price Core 维护自己的 OrderBook，每次发布也是完整快照
3. 全量推送简化了客户端处理逻辑，不需要维护增量状态

### Q2: 为什么有两个 Topic？

**答案**: **避免消费竞争**

- `market.depth.raw.{symbol}`: Match Engine → Market Price Core（原始数据）
- `market.depth.{symbol}`: Market Price Core → Public Push Core（处理后数据）

**好处**:
1. Market Price Core 和 Public Push Core 不会竞争同一个 Topic
2. Market Price Core 可以处理数据后再发布（格式转换、数据清洗）
3. 如果 Market Price Core 处理失败，不影响 Public Push Core 的消费

### Q3: Market Price Core 的作用是什么？

**答案**: **数据加工和标准化**

1. **格式转换**: 从 `List<List<String>>` 转换为 `List<long[]>`（性能优化）
2. **数据验证**: 验证序列号连续性，处理数据不一致
3. **快照管理**: 维护 Redis 快照，供首次订阅时获取
4. **数据标准化**: 统一数据格式，符合 Binance API 规范

### Q4: 数据格式为什么不同？

**Match Engine 发布**:
```json
{
  "b": [["40000", "1.0"], ...],  // List<List<String>>
  "a": [["40001", "1.5"], ...]
}
```

**Market Price Core 发布**:
```json
{
  "b": [[40000, 1.0], ...],       // List<long[]>
  "a": [[40001, 1.5], ...]
}
```

**原因**:
- Match Engine 使用字符串格式（便于调试和兼容性）
- Market Price Core 转换为数字格式（性能优化，减少序列化大小）

---

## ⚠️ 潜在问题

### 问题1: 数据冗余

**现象**: 每次都是全量推送，即使只有少量变化

**影响**:
- Kafka 消息体积较大
- 网络传输开销
- 客户端需要处理全量数据

**优化建议**:
- 可以考虑增量推送（只发送变化的部分）
- 但需要客户端维护状态，复杂度增加

### 问题2: Topic 命名混乱

**现象**: 
- Match Engine 发布到 `market.depth.raw.{symbol}`
- Market Price Core 发布到 `market.depth.{symbol}`
- 但代码中有些地方还在用 `market.depth.*` 匹配

**影响**: 可能导致消费不到数据

**建议**: 统一 Topic 命名规范

### 问题3: 快照标记不一致

**现象**:
- Match Engine: `isSnapshot: true`（但实际每次都是全量）
- Market Price Core: `snapshot: false`（但实际也是全量）

**影响**: 客户端可能误解数据性质

**建议**: 统一快照标记逻辑

---

## 🔧 优化建议

### 1. 增量推送（可选）

如果网络带宽是瓶颈，可以考虑增量推送：

```java
// Match Engine 只发送变化的部分
{
  "e": "depthUpdate",
  "U": 100,
  "u": 105,
  "b": [[40000, "0"], ...],  // 数量为0表示删除
  "a": [[40001, "1.5"], ...]  // 新增或更新
}
```

### 2. 统一 Topic 命名

```yaml
# 建议的命名规范
match-engine.depth.raw.{symbol}    # Match Engine 原始数据
market-price.depth.{symbol}        # Market Price Core 处理后数据
```

### 3. 明确快照策略

```java
// 首次发布或序列号不连续时
update.setSnapshot(true);

// 正常增量更新时
update.setSnapshot(false);
```

---

## 📊 数据量估算

### 单次深度数据大小

- **20档深度**: 20 bids + 20 asks = 40 条记录
- **每条记录**: price (8 bytes) + qty (8 bytes) = 16 bytes
- **总大小**: 40 * 16 = 640 bytes（数据部分）
- **加上 JSON 格式**: 约 1-2 KB

### 推送频率

- **Match Engine**: 订单进入 OrderBook 时立即发布
- **Market Price Core**: 每次收到 raw 数据后立即发布
- **Public Push Core**: 每次收到数据后立即推送到 WebSocket

---

## ✅ 总结

1. **数据流**: Match Engine → Market Price Core → Public Push Core → 客户端
2. **推送方式**: **全量推送**（每次都是完整的 20 档深度数据）
3. **Topic 分离**: 使用不同的 Topic 避免消费竞争
4. **Market Price Core 作用**: 数据加工、格式转换、快照管理
5. **优化空间**: 可以考虑增量推送减少网络开销

---

*最后更新: 2026-02-22*

