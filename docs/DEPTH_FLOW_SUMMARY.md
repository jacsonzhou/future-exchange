# 深度数据推送流程总结

## ✅ 问题已解决

**根本原因**: Match Engine (8083) 未运行，导致：
- 订单事件无法被消费
- 深度数据无法发布
- 整个数据流中断

**解决方案**: 启动 Match Engine 服务

## 完整数据流程

```
1. 用户下单
   ↓
2. OMS Core (8081)
   - 冻结资金
   - 发布订单事件到 Kafka: order-events
   ↓
3. Match Engine Core (8083) ✅ 已启动
   - 消费订单事件
   - 订单进入 OrderBook
   - 立即发布深度数据到 Kafka: market.depth.BTCUSDT
   ↓
4. Market Price Core (8095) ✅ 已启动
   - 消费深度数据
   - 处理并发布到 Kafka: market.depth.BTCUSDT
   ↓
5. Public Push Core (8096) ✅ 已启动
   - 消费深度数据
   - 推送到 WebSocket
   ↓
6. 客户端 (trading-test.html)
   - 接收 WebSocket 消息
   - 解析并渲染深度数据
```

## 当前服务状态

✅ **所有核心服务都在运行**:
- OMS Core (8081) - 运行中
- Match Engine (8083) - 运行中 ⭐
- Market Price Core (8095) - 运行中
- Public Push Core (8096) - 运行中

✅ **Kafka Topics 已创建**:
- order-events
- market.depth.BTCUSDT

## 测试步骤

### 1. 创建新订单

在 `trading-test.html` 中创建一个新订单，例如：
- Symbol: BTCUSDT
- Side: BUY
- Type: LIMIT
- Price: 40000
- Quantity: 1
- Leverage: 10

### 2. 监控日志

**Match Engine 消费订单**:
```bash
tail -f match-engine-core/logs/match-engine.log | grep -E "OrderEventConsumer|Receive order|Depth published"
```

**Market Price Core 处理深度**:
```bash
tail -f market-price-core/logs/market-price.log | grep -E "Process depth"
```

**Public Push Core 推送**:
```bash
tail -f public-push-core/logs/public-push.log | grep -E "broadcast.*depth"
```

### 3. 检查浏览器控制台

应该看到：
- ✅ "订阅成功: ..."
- ✅ "收到 WebSocket 消息: ..."
- ✅ "收到深度数据: bids=X, asks=Y"
- ✅ 盘口深度显示数据

## 如果仍然没有数据

### 检查1: Match Engine 是否消费了订单

```bash
tail -100 match-engine-core/logs/match-engine.log | grep "OrderEventConsumer"
```

如果没有记录，可能是：
- Kafka consumer group offset 问题
- Topic 名称不匹配
- Consumer 配置问题

**解决**: 重置 consumer group offset 或创建新订单

### 检查2: 订单是否进入 OrderBook

```bash
tail -100 match-engine-core/logs/match-engine.log | grep -E "addOrder|OrderBook"
```

如果没有记录，可能是：
- 订单立即完全成交（没有进入 OrderBook）
- 订单格式错误

**解决**: 创建一个价格远离市价的订单（确保不会立即成交）

### 检查3: 深度数据是否发布

```bash
tail -100 match-engine-core/logs/match-engine.log | grep "Depth published"
```

如果没有记录，检查：
- DepthPublisher 是否注入
- 订单是否进入 OrderBook（未完全成交）
- 深度发布配置

### 检查4: Market Price Core 是否消费

```bash
tail -100 market-price-core/logs/market-price.log | grep "Process depth"
```

如果没有记录，检查：
- Topic 名称是否匹配（market.depth.*）
- Consumer group 配置
- Kafka 连接

### 检查5: Public Push Core 是否推送

```bash
tail -100 public-push-core/logs/public-push.log | grep "broadcast.*depth"
```

如果没有记录，检查：
- 订阅状态
- WebSocket 连接
- 消息分发逻辑

## 快速检查脚本

运行 `./check_depth_flow.sh` 快速检查所有环节。

## 相关文档

- [深度数据推送流程修复](./DEPTH_DATA_FLOW_FIX.md)
- [深度数据渲染修复](./DEPTH_RENDERING_FIX.md)
- [流程检查清单](./FLOW_CHECKLIST.md)

---

*最后更新: 2026-02-22*

