# 深度数据推送链路日志追踪指南

## 📋 完整链路日志检查清单

### 链路概览

```
下单 → OMS → Kafka(order-events) → Match Engine 
→ 订单进入OrderBook → 发布深度 → Kafka(market.depth.BTCUSDT)
→ Market Price Core → Kafka(market.depth.BTCUSDT)
→ Public Push Core → WebSocket → 客户端
```

---

## 🔍 各环节日志检查

### 1. OMS Core - 订单事件发布

**日志文件**: `oms-core/logs/oms-core.log`

**关键日志**:
```bash
# 检查订单事件发布
tail -f oms-core/logs/oms-core.log | grep -E "Order event sent|publishOrderEvent|Kafka send.*order"
```

**预期日志**:
```
[OMS] Order event sent to match engine, orderId=xxx
[OrderEventPublisher] ✅ Kafka send success, topic=order-events, partition=0, offset=xxx, orderId=xxx
```

**排查要点**:
- ✅ 如果看到 "Order event sent"，说明 OMS 已发布
- ❌ 如果没有，检查 OMS 配置和 Kafka 连接

---

### 2. Match Engine - 消费订单

**日志文件**: `match-engine-core/logs/match-engine.log`

**关键日志**:
```bash
# 检查订单消费
tail -f match-engine-core/logs/match-engine.log | grep -E "OrderEventConsumer|Receive order|Parse order"
```

**预期日志**:
```
[OrderEventConsumer] ⬇️ Receive order event, topic=order-events, partition=0, offset=xxx, key=xxx
[OrderEventConsumer] Parse order command, orderId=xxx, type=ORDER_SUBMIT, symbol=BTCUSDT
[OrderEventConsumer] ✅ Order command submitted to Disruptor, orderId=xxx
```

**排查要点**:
- ✅ 如果看到 "Receive order event"，说明 Match Engine 已消费
- ❌ 如果没有，检查 Kafka consumer group 和 topic 配置

---

### 3. Match Engine - 订单处理

**日志文件**: `match-engine-core/logs/match-engine.log`

**关键日志**:
```bash
# 检查订单处理
tail -f match-engine-core/logs/match-engine.log | grep -E "MatchingProcessor|handleSubmit|addOrder|OrderBook"
```

**预期日志**:
```
[MatchingProcessor] Process event, type=ORDER_SUBMIT, seq=xxx, orderId=xxx
[OrderBook] Add order, orderId=xxx, symbol=BTCUSDT, side=0, price=xxx, qty=xxx
[MatchingProcessor] Order added to OrderBook, publish depth immediately, orderId=xxx
```

**排查要点**:
- ✅ 如果看到 "Order added to OrderBook"，说明订单已进入盘口
- ❌ 如果没有，可能是订单立即完全成交（没有进入 OrderBook）

---

### 4. Match Engine - 发布深度数据 ⭐ **关键环节**

**日志文件**: `match-engine-core/logs/match-engine.log`

**关键日志**:
```bash
# 检查深度发布（详细日志）
tail -f match-engine-core/logs/match-engine.log | grep -E "DepthPublisher|publishDepth|Depth published"
```

**预期日志**（完整流程）:
```
[DepthPublisher] 🔥 Start publish depth, symbol=BTCUSDT, lastSeq=xxx
[DepthPublisher] Depth data retrieved, symbol=BTCUSDT, bids=X, asks=Y
[DepthPublisher] Message serialized, symbol=BTCUSDT, size=xxx bytes
[DepthPublisher] Sending to Kafka, topic=market.depth.BTCUSDT, key=BTCUSDT
[DepthPublisher] ✅ Depth published successfully, symbol=BTCUSDT, bids=X, asks=Y, partition=0, offset=xxx
```

**错误日志**:
```
[DepthPublisher] ⚠️ Empty depth data, symbol=BTCUSDT
[DepthPublisher] ❌ Depth publish failed, symbol=BTCUSDT
[DepthPublisher] ❌ Publish depth error, symbol=BTCUSDT
```

**排查要点**:
- ✅ 如果看到 "Depth published successfully"，说明已发布到 Kafka
- ⚠️ 如果看到 "Empty depth data"，说明 OrderBook 为空
- ❌ 如果看到错误日志，检查 Kafka 连接和 topic 配置

---

### 5. Market Price Core - 消费深度数据

**日志文件**: `market-price-core/logs/market-price.log`

**关键日志**:
```bash
# 检查深度消费
tail -f market-price-core/logs/market-price.log | grep -E "Process depth|depth event|onDepthEvent|depthUpdate"
```

**预期日志**:
```
[Consumer] Process depth event, symbol=BTCUSDT, firstU=xxx, lastU=xxx, bids=X, asks=Y
[EngineService] Publish depth update, symbol=BTCUSDT, bids=X, asks=Y
```

**错误日志**:
```
[Consumer] Failed to parse depth event: xxx
[Consumer] No depth data in event: xxx
```

**排查要点**:
- ✅ 如果看到 "Process depth event"，说明已消费
- ❌ 如果没有，检查 topic 名称是否匹配（market.depth.*）
- ❌ 如果看到解析错误，检查数据格式

---

### 6. Market Price Core - 发布处理后的深度

**日志文件**: `market-price-core/logs/market-price.log`

**关键日志**:
```bash
# 检查发布
tail -f market-price-core/logs/market-price.log | grep -E "Publish depth|publishDepth"
```

**预期日志**:
```
[Publisher] Publishing depth for BTCUSDT
```

**排查要点**:
- ✅ 如果看到发布日志，说明已处理并发布
- ❌ 如果没有，检查 Market Data Publisher 配置

---

### 7. Public Push Core - 消费深度数据

**日志文件**: `public-push-core/logs/public-push.log`

**关键日志**:
```bash
# 检查消费
tail -f public-push-core/logs/public-push.log | grep -E "onMessage.*depth|KafkaConsumer.*depth"
```

**预期日志**:
```
[KafkaConsumer] Processing message for channel: depth.BTCUSDT
[KafkaConsumer] Message received, channel=depth.BTCUSDT, size=xxx bytes
```

**排查要点**:
- ✅ 如果看到 "Processing message"，说明已消费
- ❌ 如果没有，检查订阅状态和 Kafka consumer

---

### 8. Public Push Core - 推送深度数据

**日志文件**: `public-push-core/logs/public-push.log`

**关键日志**:
```bash
# 检查推送
tail -f public-push-core/logs/public-push.log | grep -E "broadcast.*depth|sendMessage.*depth|Broadcasting.*depth"
```

**预期日志**:
```
[Dispatcher] Broadcasting to X subscribers for channel: depth.BTCUSDT
[Dispatcher] Sent message to session: xxx, channel: depth.BTCUSDT
```

**警告日志**:
```
[Dispatcher] No subscribers for channel: depth.BTCUSDT
[Dispatcher] Sequence gap detected for channel: depth.BTCUSDT, triggering snapshot rebuild
[Dispatcher] Message dropped due to rate limit
```

**排查要点**:
- ✅ 如果看到 "Broadcasting to X subscribers"，说明正在推送
- ⚠️ 如果看到 "No subscribers"，说明没有 WebSocket 连接
- ⚠️ 如果看到 "Sequence gap"，说明序列号不连续，会触发快照重建

---

### 9. WebSocket 连接和订阅

**日志文件**: `public-push-core/logs/public-push.log`

**关键日志**:
```bash
# 检查连接和订阅
tail -f public-push-core/logs/public-push.log | grep -E "subscribed|Subscription|WebSocket.*connected"
```

**预期日志**:
```
[Subscription] sess_xxx subscribed to depth.BTCUSDT (type: DEPTH), total subs: X
[KafkaConsumer] Started consumer for channel: depth.BTCUSDT -> topic: market.depth.BTCUSDT
```

**排查要点**:
- ✅ 如果看到 "subscribed to depth.BTCUSDT"，说明订阅成功
- ❌ 如果没有，检查 WebSocket 连接和订阅消息

---

## 🚀 一键检查脚本

### 完整链路检查

```bash
#!/bin/bash
echo "=== 深度数据推送链路检查 ==="
echo ""

echo "1. OMS 订单发布:"
tail -20 oms-core/logs/oms-core.log | grep -E "Order event sent" | tail -1

echo ""
echo "2. Match Engine 消费订单:"
tail -20 match-engine-core/logs/match-engine.log | grep -E "Receive order event" | tail -1

echo ""
echo "3. Match Engine 订单处理:"
tail -20 match-engine-core/logs/match-engine.log | grep -E "Order added to OrderBook" | tail -1

echo ""
echo "4. Match Engine 发布深度:"
tail -20 match-engine-core/logs/match-engine.log | grep -E "Depth published successfully" | tail -1

echo ""
echo "5. Market Price Core 消费深度:"
tail -20 market-price-core/logs/market-price.log | grep -E "Process depth event" | tail -1

echo ""
echo "6. Public Push Core 推送:"
tail -20 public-push-core/logs/public-push.log | grep -E "Broadcasting.*depth" | tail -1

echo ""
echo "7. WebSocket 订阅:"
tail -20 public-push-core/logs/public-push.log | grep -E "subscribed to depth" | tail -1
```

---

## 📊 实时监控命令

### 同时监控所有环节

```bash
# 终端1: OMS 和 Match Engine
tail -f oms-core/logs/oms-core.log match-engine-core/logs/match-engine.log | grep -E "Order event|Receive order|Depth published"

# 终端2: Market Price Core
tail -f market-price-core/logs/market-price.log | grep -E "Process depth|Publish depth"

# 终端3: Public Push Core
tail -f public-push-core/logs/public-push.log | grep -E "broadcast.*depth|subscribed.*depth"

# 终端4: Kafka Topic（可选）
kafka-console-consumer --bootstrap-server localhost:9092 --topic market.depth.BTCUSDT --from-beginning
```

---

## 🔧 常见问题排查

### 问题1: Match Engine 没有发布深度数据

**检查**:
```bash
tail -100 match-engine-core/logs/match-engine.log | grep -E "DepthPublisher|publishDepth"
```

**可能原因**:
1. `depthPublisher` 为 null（检查注入）
2. 订单立即完全成交（没有进入 OrderBook）
3. `orderBook.getDepthData()` 返回空数据
4. Kafka 发送失败

**解决**:
- 检查 `DepthPublisher` 是否注入
- 创建一个价格远离市价的订单（确保不会立即成交）
- 检查 Kafka 连接和 topic 配置

### 问题2: Market Price Core 没有消费深度数据

**检查**:
```bash
tail -100 market-price-core/logs/market-price.log | grep -E "Process depth"
```

**可能原因**:
1. Topic 名称不匹配（应该是 `market.depth.*`）
2. Consumer group 配置错误
3. Kafka 连接问题

**解决**:
- 检查 `MatchEventConsumer` 的 `@KafkaListener` topic 配置
- 检查 consumer group 配置
- 检查 Kafka 连接

### 问题3: Public Push Core 没有推送

**检查**:
```bash
tail -100 public-push-core/logs/public-push.log | grep -E "broadcast.*depth|No subscribers"
```

**可能原因**:
1. 没有订阅者（WebSocket 未连接）
2. 序列号不连续（触发快照重建）
3. 限流导致消息被丢弃

**解决**:
- 检查 WebSocket 连接状态
- 检查订阅消息
- 检查限流配置

---

## ✅ 修复状态总结

### 已完成的修复

1. ✅ **Match Engine 深度发布日志增强**
   - 添加了详细的步骤日志（开始、获取数据、序列化、发送、成功）
   - 日志级别从 `debug` 改为 `info`
   - 添加了空数据检查和错误处理

2. ✅ **Market Price Core 深度消费日志**
   - 已有 "Process depth event" 日志
   - 已有错误日志

3. ✅ **Public Push Core 推送日志**
   - 已有 "Broadcasting" 日志
   - 已有 "No subscribers" 警告
   - 已有序列号检查日志

### 日志覆盖情况

| 环节 | 日志文件 | 关键日志 | 状态 |
|------|---------|---------|------|
| OMS 发布 | oms-core/logs/oms-core.log | Order event sent | ✅ |
| Match Engine 消费 | match-engine-core/logs/match-engine.log | Receive order event | ✅ |
| Match Engine 处理 | match-engine-core/logs/match-engine.log | Order added to OrderBook | ✅ |
| Match Engine 发布深度 | match-engine-core/logs/match-engine.log | Depth published successfully | ✅ **已增强** |
| Market Price Core 消费 | market-price-core/logs/market-price.log | Process depth event | ✅ |
| Market Price Core 发布 | market-price-core/logs/market-price.log | Publish depth update | ✅ |
| Public Push Core 消费 | public-push-core/logs/public-push.log | Processing message | ✅ |
| Public Push Core 推送 | public-push-core/logs/public-push.log | Broadcasting to X subscribers | ✅ |
| WebSocket 订阅 | public-push-core/logs/public-push.log | subscribed to depth | ✅ |

---

## 📝 使用建议

1. **创建订单后立即检查日志**，按照链路顺序逐一检查
2. **使用 grep 过滤关键日志**，快速定位问题
3. **同时监控多个日志文件**，了解完整流程
4. **关注错误和警告日志**，优先解决

---

*最后更新: 2026-02-22*

