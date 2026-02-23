# 盘口深度数据推送修复总结

## 问题描述

订单创建成功后，盘口深度显示"等待数据..."，没有收到 WebSocket 推送的深度数据。

## 根本原因

1. **Match Engine 深度发布策略问题**
   - 原逻辑：每 10 个事件才发布一次深度数据
   - 问题：如果只有 1 个订单，不会触发发布

2. **Market Price Core Topic 不匹配**
   - Match Engine 发布到：`market.depth.{symbol}`
   - Market Price Core 消费：`orderbook-delta-.*`
   - 不匹配导致数据无法消费

3. **Market Price Core 数据格式解析问题**
   - Match Engine 格式：`{"e": "depthUpdate", "s": "BTCUSDT", "U": 1, "u": 2, "b": [...], "a": [...]}`
   - Market Price Core 期望：`{"eventType": "DEPTH_DELTA", "symbol": "BTCUSDT", ...}`
   - 格式不匹配导致解析失败

## 修复内容

### 1. Match Engine - 深度发布策略优化

**文件**: `match-engine-core/src/main/java/com/exchange/match/processor/MatchingProcessor.java`

**修复**:
- 订单进入 OrderBook 后立即发布深度数据（不等待 10 个事件）
- 同时保留每 N 个事件的频率控制

```java
// 策略1：订单进入OrderBook后立即发送（确保盘口有数据）
if (!order.isFullyFilled()) {
    shouldPublish = true;
}
// 策略2：达到发布间隔
else if (sequence - lastDepthPublishSequence.get() >= depthPublishInterval) {
    shouldPublish = true;
}
```

### 2. Market Price Core - Topic 匹配修复

**文件**: `market-price-core/src/main/java/com/exchange/market/consumer/MatchEventConsumer.java`

**修复**:
- 从 `orderbook-delta-.*` 改为 `market\\.depth\\..*`
- 支持 byte[] 消息格式（Match Engine 发布的是 byte[]）

```java
@KafkaListener(
    topicPattern = "market\\.depth\\..*",  // 修复：匹配 Match Engine 的 topic
    groupId = "${spring.application.name:market-price-service}",
    containerFactory = "depthEventKafkaListenerContainerFactory"
)
public void onDepthEvent(List<ConsumerRecord<String, byte[]>> records, Acknowledgment ack) {
    // 转换为 String
    String json = new String(record.value(), StandardCharsets.UTF_8);
    processDepthEvent(json);
}
```

### 3. Market Price Core - 数据格式解析修复

**文件**: `market-price-core/src/main/java/com/exchange/market/consumer/MatchEventConsumer.java`

**修复**:
- 支持 Match Engine 格式：`{"e": "depthUpdate", "s": "BTCUSDT", "U": 1, "u": 2, "b": [...], "a": [...]}`
- 兼容旧格式：`{"eventType": "DEPTH_DELTA", "symbol": "BTCUSDT", ...}`

```java
// 解析 symbol（Match Engine 使用 "s"，旧格式使用 "symbol"）
String symbol = event.getString("s");
if (symbol == null) {
    symbol = event.getString("symbol");
}

// 解析序列号（Match Engine 使用 "U" 和 "u"）
long firstUpdateId = event.getLongValue("U");
long lastUpdateId = event.getLongValue("u");

// 解析买盘和卖盘（Match Engine 使用 "b" 和 "a"）
List<long[]> bids = parseDepthLevels(event.getJSONArray("b"));
List<long[]> asks = parseDepthLevels(event.getJSONArray("a"));
```

## 完整数据流程

```
1. 用户下单（OMS）
   ↓
2. OMS 冻结资金 → Ledger
   ↓
3. OMS 发布订单事件 → Kafka: order-events
   ↓
4. Match Engine 消费订单事件
   ↓
5. Match Engine 订单进入 OrderBook
   ↓
6. Match Engine 立即发布深度数据 → Kafka: market.depth.BTCUSDT
   ↓
7. Market Price Core 消费深度数据
   ↓
8. Market Price Core 处理并发布 → Kafka: market.depth.BTCUSDT（处理后）
   ↓
9. Public Push Core 消费深度数据
   ↓
10. Public Push Core 推送到 WebSocket → 客户端
```

## 验证方法

1. **检查 Match Engine 日志**:
   ```bash
   tail -f match-engine-core/logs/match-engine.log | grep "Depth published"
   ```

2. **检查 Market Price Core 日志**:
   ```bash
   tail -f market-price-core/logs/market-price.log | grep "Process depth"
   ```

3. **检查 Public Push Core 日志**:
   ```bash
   tail -f public-push-core/logs/public-push.log | grep "depth"
   ```

4. **检查 Kafka Topic**:
   ```bash
   kafka-console-consumer --bootstrap-server localhost:9092 --topic market.depth.BTCUSDT
   ```

5. **前端验证**:
   - 打开 `trading-test.html`
   - 查看 WebSocket 日志，应该看到"收到深度数据"
   - 盘口深度应该显示实时数据

## 注意事项

1. **Kafka Topics 必须已创建**
   - 运行 `./init_kafka.sh` 创建 `market.depth.*` topics

2. **服务启动顺序**
   - Match Engine (8083) → Market Price Core (8095) → Public Push Core (8096)

3. **深度数据格式**
   - Match Engine 发布：`{"e": "depthUpdate", "s": "BTCUSDT", "U": 1, "u": 2, "b": [...], "a": [...]}`
   - Market Price Core 处理后发布：相同格式
   - Public Push Core 推送到 WebSocket：`{"stream": "depth.BTCUSDT", "data": {...}}`

---

*最后更新: 2026-02-22*

