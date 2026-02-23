# 深度数据推送流程检查清单

## 完整流程

```
1. 用户下单
   ↓
2. OMS Core (8081) - 冻结资金 → 发布订单事件
   ↓
3. Kafka: order-events topic
   ↓
4. Match Engine Core (8083) - 消费订单 → 进入 OrderBook → 发布深度数据
   ↓
5. Kafka: market.depth.BTCUSDT topic
   ↓
6. Market Price Core (8095) - 消费深度数据 → 处理 → 发布
   ↓
7. Kafka: market.depth.BTCUSDT topic (处理后)
   ↓
8. Public Push Core (8096) - 消费深度数据 → 推送到 WebSocket
   ↓
9. 客户端 (trading-test.html) - 接收并渲染
```

## 检查步骤

### 1. 检查服务运行状态

```bash
# 检查所有核心服务
lsof -i :8081 -i :8083 -i :8095 -i :8096 | grep LISTEN

# 应该看到：
# - OMS Core (8081)
# - Match Engine (8083) ⚠️ 关键！
# - Market Price Core (8095)
# - Public Push Core (8096)
```

### 2. 检查 Kafka Topics

```bash
# 检查 topics 是否存在
kafka-topics --bootstrap-server localhost:9092 --list | grep -E "order-events|market.depth"

# 应该看到：
# - order-events
# - market.depth.BTCUSDT
```

### 3. 检查订单事件发布

```bash
# OMS Core 日志
tail -f oms-core/logs/oms-core.log | grep -E "Order event sent|publishOrderEvent"

# 应该看到：
# [OMS] Order event sent to match engine, orderId=xxx
# [OrderEventPublisher] ✅ Kafka send success
```

### 4. 检查 Match Engine 消费订单

```bash
# Match Engine 日志
tail -f match-engine-core/logs/match-engine.log | grep -E "OrderEventConsumer|Receive order|Parse order"

# 应该看到：
# [OrderEventConsumer] ⬇️ Receive order event
# [OrderEventConsumer] Parse order command, orderId=xxx
```

### 5. 检查深度数据发布

```bash
# Match Engine 日志
tail -f match-engine-core/logs/match-engine.log | grep -E "Depth published|publishDepth"

# 应该看到：
# [DepthPublisher] ✅ Depth published, symbol=BTCUSDT
```

### 6. 检查 Market Price Core 消费深度

```bash
# Market Price Core 日志
tail -f market-price-core/logs/market-price.log | grep -E "Process depth|depth event|onDepthDelta"

# 应该看到：
# [Consumer] Process depth event, symbol=BTCUSDT
```

### 7. 检查 Public Push Core 消费并推送

```bash
# Public Push Core 日志
tail -f public-push-core/logs/public-push.log | grep -E "onMessage.*depth|broadcast.*depth|sendMessage"

# 应该看到：
# [KafkaConsumer] Processing message for channel: depth.BTCUSDT
# [Dispatcher] Broadcasting to X subscribers for channel: depth.BTCUSDT
```

### 8. 检查 WebSocket 连接

```bash
# 浏览器控制台应该看到：
# - "WebSocket 连接成功"
# - "订阅成功: ..."
# - "收到 WebSocket 消息: ..."
# - "收到深度数据: bids=X, asks=Y"
```

## 常见问题排查

### 问题1: Match Engine 未运行

**症状**: 订单创建成功，但没有深度数据

**检查**:
```bash
lsof -i :8083 | grep LISTEN
```

**解决**:
```bash
cd match-engine-core
mvn spring-boot:run -DskipTests
```

### 问题2: Kafka Topic 不存在

**症状**: Match Engine 启动失败或无法消费

**检查**:
```bash
kafka-topics --bootstrap-server localhost:9092 --list | grep order-events
```

**解决**:
```bash
./init_kafka.sh
```

### 问题3: 订单事件未发布

**症状**: OMS 日志中没有 "Order event sent"

**检查**:
```bash
tail -f oms-core/logs/oms-core.log | grep "Order event sent"
```

**解决**: 检查 OMS 配置和 Kafka 连接

### 问题4: Match Engine 未消费订单

**症状**: Match Engine 运行但日志中没有 "Receive order event"

**检查**:
```bash
tail -f match-engine-core/logs/match-engine.log | grep "OrderEventConsumer"
```

**解决**: 
- 检查 Kafka consumer group
- 检查 topic 名称是否匹配
- 检查 consumer 配置

### 问题5: 深度数据未发布

**症状**: Match Engine 消费了订单但没有发布深度数据

**检查**:
```bash
tail -f match-engine-core/logs/match-engine.log | grep "Depth published"
```

**解决**:
- 检查订单是否进入 OrderBook（未完全成交）
- 检查 DepthPublisher 是否注入
- 检查深度发布配置

### 问题6: Market Price Core 未消费深度

**症状**: Match Engine 发布了深度但 Market Price Core 没有消费

**检查**:
```bash
tail -f market-price-core/logs/market-price.log | grep "Process depth"
```

**解决**:
- 检查 topic 名称是否匹配（market.depth.*）
- 检查 consumer group 配置
- 检查 Kafka 连接

### 问题7: Public Push Core 未推送

**症状**: Market Price Core 处理了深度但 WebSocket 没有收到

**检查**:
```bash
tail -f public-push-core/logs/public-push.log | grep "broadcast.*depth"
```

**解决**:
- 检查订阅状态
- 检查 WebSocket 连接
- 检查消息分发逻辑

### 问题8: 前端未渲染

**症状**: WebSocket 收到数据但页面没有显示

**检查**: 浏览器控制台日志

**解决**:
- 检查数据格式是否正确
- 检查解析逻辑
- 检查渲染函数

## 快速测试命令

```bash
# 1. 检查所有服务
lsof -i :8081 -i :8083 -i :8095 -i :8096 | grep LISTEN

# 2. 检查 Kafka topics
kafka-topics --bootstrap-server localhost:9092 --list | grep -E "order-events|market.depth"

# 3. 监控订单事件
kafka-console-consumer --bootstrap-server localhost:9092 --topic order-events --from-beginning

# 4. 监控深度数据
kafka-console-consumer --bootstrap-server localhost:9092 --topic market.depth.BTCUSDT --from-beginning

# 5. 测试下单
curl -X POST http://localhost:8082/api/order/create \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 35,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "type": "LIMIT",
    "price": "4000000000000",
    "quantity": "100000000",
    "leverage": 10
  }'
```

---

*最后更新: 2026-02-22*

