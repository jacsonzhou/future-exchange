# 深度数据推送问题排查指南

## 🔍 快速诊断

### 问题：没有盘口数据推送

**症状**:
- 订单提交成功
- 余额已扣除
- WebSocket 已连接并订阅
- 但没有收到深度数据

---

## 📋 检查清单

### 1. 检查服务运行状态 ⭐ **最重要**

```bash
lsof -i :8081 -i :8083 -i :8095 -i :8096 | grep LISTEN
```

**必须运行的服务**:
- ✅ OMS Core (8081)
- ✅ **Match Engine (8083)** ⭐ **关键！**
- ✅ Market Price Core (8095)
- ✅ Public Push Core (8096)

**如果 Match Engine 未运行**:
```bash
cd match-engine-core
mvn spring-boot:run -DskipTests
```

---

### 2. 检查订单事件发布

```bash
tail -f oms-core/logs/oms-core.log | grep -E "Order event sent|Kafka send.*order"
```

**预期日志**:
```
[OMS] Order event sent to match engine, orderId=xxx
[OrderEventPublisher] ✅ Kafka send success, topic=order-events, offset=xxx
```

**如果没有日志**: 检查 OMS 配置和 Kafka 连接

---

### 3. 检查 Match Engine 消费订单

```bash
tail -f match-engine-core/logs/match-engine.log | grep -E "OrderEventConsumer|Receive order event"
```

**预期日志**:
```
[OrderEventConsumer] ⬇️ Receive order event, topic=order-events, offset=xxx
[OrderEventConsumer] Parse order command, orderId=xxx
```

**如果没有日志**:
- ❌ Match Engine 未运行（最常见）
- ❌ Kafka consumer group offset 问题
- ❌ Topic 名称不匹配

---

### 4. 检查订单进入 OrderBook

```bash
tail -f match-engine-core/logs/match-engine.log | grep -E "Order added to OrderBook|publish depth immediately"
```

**预期日志**:
```
[MatchingProcessor] Order added to OrderBook, publish depth immediately, orderId=xxx
```

**如果没有日志**:
- ⚠️ 订单可能立即完全成交（没有进入 OrderBook）
- 解决：创建一个价格远离市价的订单

---

### 5. 检查深度数据发布 ⭐ **关键环节**

```bash
tail -f match-engine-core/logs/match-engine.log | grep -E "DepthPublisher|Depth published"
```

**预期日志**（完整流程）:
```
[DepthPublisher] 🔥 Start publish depth, symbol=BTCUSDT, lastSeq=xxx
[DepthPublisher] Depth data retrieved, symbol=BTCUSDT, bids=X, asks=Y
[DepthPublisher] Message serialized, symbol=BTCUSDT, size=xxx bytes
[DepthPublisher] Sending to Kafka, topic=market.depth.BTCUSDT
[DepthPublisher] ✅ Depth published successfully, symbol=BTCUSDT, partition=0, offset=xxx
```

**如果没有日志**:
- ❌ Match Engine 未运行
- ❌ 订单没有进入 OrderBook
- ❌ DepthPublisher 未注入
- ❌ Kafka 发送失败

---

### 6. 检查 Market Price Core 消费

```bash
tail -f market-price-core/logs/market-price.log | grep -E "Process depth event"
```

**预期日志**:
```
[Consumer] Process depth event, symbol=BTCUSDT, firstU=xxx, lastU=xxx, bids=X, asks=Y
```

**如果没有日志**:
- ❌ Match Engine 没有发布数据
- ❌ Topic 名称不匹配（应该是 `market.depth.*`）
- ❌ Consumer group 配置错误

---

### 7. 检查 Public Push Core 推送

```bash
tail -f public-push-core/logs/public-push.log | grep -E "broadcast.*depth|Broadcasting.*depth"
```

**预期日志**:
```
[Dispatcher] Broadcasting to X subscribers for channel: depth.BTCUSDT
[Dispatcher] Sent message to session: xxx, channel: depth.BTCUSDT
```

**如果没有日志**:
- ⚠️ 没有订阅者（WebSocket 未连接）
- ⚠️ 序列号不连续（触发快照重建）
- ⚠️ 限流导致消息被丢弃

---

### 8. 检查 WebSocket 订阅

```bash
tail -f public-push-core/logs/public-push.log | grep -E "subscribed to depth"
```

**预期日志**:
```
[Subscription] sess_xxx subscribed to depth.BTCUSDT (type: DEPTH), total subs: X
```

**如果没有日志**: 检查浏览器控制台的 WebSocket 连接和订阅消息

---

## 🚨 常见问题

### 问题1: Match Engine 未运行

**症状**: 订单提交成功，但没有深度数据

**检查**:
```bash
lsof -i :8083 | grep LISTEN
```

**解决**:
```bash
cd match-engine-core
mvn spring-boot:run -DskipTests
```

**注意**: Match Engine 启动后，会消费 Kafka 中积压的订单事件，之前的订单也会被处理。

---

### 问题2: 订单立即完全成交

**症状**: Match Engine 运行，订单被消费，但没有发布深度数据

**原因**: 订单价格与对手盘匹配，立即完全成交，没有进入 OrderBook

**解决**: 创建一个价格远离市价的订单（例如：买单价格远低于当前市价，卖单价格远高于当前市价）

---

### 问题3: WebSocket 未订阅

**症状**: 后端有数据推送，但前端没有收到

**检查**: 浏览器控制台是否看到 "订阅成功: ..."

**解决**: 检查 `trading-test.html` 中的订阅逻辑

---

### 问题4: 序列号不连续

**症状**: Public Push Core 日志显示 "Sequence gap detected"

**原因**: 深度数据的序列号不连续，可能是：
- Match Engine 重启后序列号重置
- 消息丢失
- 消费顺序问题

**解决**: Public Push Core 会自动触发快照重建，等待快照数据

---

## 🔧 一键诊断脚本

```bash
#!/bin/bash
echo "=== 深度数据推送诊断 ==="
echo ""

# 1. 服务状态
echo "1. 服务运行状态:"
lsof -i :8081 -i :8083 -i :8095 -i :8096 2>/dev/null | grep LISTEN || echo "❌ 有服务未运行"

# 2. 最近订单事件
echo ""
echo "2. 最近订单事件:"
tail -20 oms-core/logs/oms-core.log 2>/dev/null | grep "Order event sent" | tail -1 || echo "⚠️  未找到"

# 3. Match Engine 消费
echo ""
echo "3. Match Engine 消费:"
tail -20 match-engine-core/logs/match-engine.log 2>/dev/null | grep "Receive order event" | tail -1 || echo "⚠️  未找到（可能 Match Engine 未运行）"

# 4. 深度发布
echo ""
echo "4. 深度数据发布:"
tail -20 match-engine-core/logs/match-engine.log 2>/dev/null | grep "Depth published successfully" | tail -1 || echo "⚠️  未找到"

# 5. WebSocket 订阅
echo ""
echo "5. WebSocket 订阅:"
tail -20 public-push-core/logs/public-push.log 2>/dev/null | grep "subscribed to depth" | tail -1 || echo "⚠️  未找到"
```

---

## 📊 完整链路检查

### 实时监控所有环节

```bash
# 终端1: OMS 和 Match Engine
tail -f oms-core/logs/oms-core.log match-engine-core/logs/match-engine.log | grep -E "Order event|Receive order|Depth published"

# 终端2: Market Price Core
tail -f market-price-core/logs/market-price.log | grep -E "Process depth"

# 终端3: Public Push Core
tail -f public-push-core/logs/public-push.log | grep -E "broadcast.*depth|subscribed.*depth"
```

---

## ✅ 修复验证

### 创建订单后应该看到：

1. **OMS 日志**: `[OMS] Order event sent to match engine`
2. **Match Engine 日志**: `[OrderEventConsumer] ⬇️ Receive order event`
3. **Match Engine 日志**: `[MatchingProcessor] Order added to OrderBook`
4. **Match Engine 日志**: `[DepthPublisher] ✅ Depth published successfully`
5. **Market Price Core 日志**: `[Consumer] Process depth event`
6. **Public Push Core 日志**: `[Dispatcher] Broadcasting to X subscribers`
7. **浏览器控制台**: "收到深度数据: bids=X, asks=Y"
8. **页面显示**: 盘口深度数据

---

## 📝 注意事项

1. **Match Engine 必须运行** - 这是整个链路的关键
2. **订单必须进入 OrderBook** - 如果立即成交，不会发布深度数据
3. **Kafka Topics 必须存在** - 运行 `./init_kafka.sh`
4. **WebSocket 必须连接并订阅** - 检查浏览器控制台
5. **日志文件可能包含二进制数据** - 使用 `strings` 命令查看

---

*最后更新: 2026-02-22*

