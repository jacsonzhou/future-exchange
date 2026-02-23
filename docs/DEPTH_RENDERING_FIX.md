# 盘口深度数据渲染修复

## 问题描述

订阅消息已发送，但盘口深度数据没有渲染显示。

## 修复内容

### 1. 前端数据解析优化

**文件**: `trading-test.html`

**修复**:
- 增强深度数据解析，支持多种数据格式
- 添加调试日志，便于排查问题
- 修复价格和数量解析逻辑

```javascript
// 支持多种数据格式
let bids = depthData.b || depthData.bids || [];
let asks = depthData.a || depthData.asks || [];

// 如果 bids/asks 是字符串数组，需要转换
if (bids.length > 0 && typeof bids[0] === 'string') {
    bids = bids.map(item => {
        if (Array.isArray(item)) {
            return [parseFloat(item[0]), parseFloat(item[1])];
        }
        return item;
    });
}
```

### 2. 深度数据显示逻辑优化

**修复**:
- 支持数组格式 `[price, qty]` 和对象格式
- 添加数据验证，防止 NaN 错误
- 增强错误处理

```javascript
asks.forEach((item) => {
    // 支持数组格式 [price, qty] 或对象格式
    const price = Array.isArray(item) ? item[0] : (item.price || item[0]);
    const qty = Array.isArray(item) ? item[1] : (item.qty || item[1]);
    
    // 转换为数字
    const priceNum = typeof price === 'string' ? parseFloat(price) : price;
    const qtyNum = typeof qty === 'string' ? parseFloat(qty) : qty;
    
    if (isNaN(priceNum) || isNaN(qtyNum)) {
        log('warn', `无效的深度数据: price=${price}, qty=${qty}`);
        return;
    }
    // ... 渲染逻辑
});
```

### 3. WebSocket 消息处理增强

**修复**:
- 添加消息解析错误处理
- 添加调试日志，记录收到的所有消息
- 优化频道识别逻辑

```javascript
state.publicWs.onmessage = (e) => {
    try {
        const msg = JSON.parse(e.data);
        log('debug', '收到 WebSocket 消息: ' + JSON.stringify(msg).substring(0, 200));
        handlePublicMessage(msg);
    } catch (err) {
        log('error', '解析 WebSocket 消息失败: ' + err.message);
    }
};
```

### 4. Public Push Core 调试日志

**文件**: `public-push-core/src/main/java/com/exchange/push/service/MessageDispatcher.java`

**修复**:
- 添加广播日志，记录订阅者数量
- 添加消息发送日志
- 添加会话状态检查日志

```java
if (subscribers.isEmpty()) {
    log.debug("[Dispatcher] No subscribers for channel: {}", channel);
    return;
}

log.debug("[Dispatcher] Broadcasting to {} subscribers for channel: {}", subscribers.size(), channel);
```

## 数据格式说明

### Match Engine 发布格式
```json
{
  "e": "depthUpdate",
  "E": 1704067200000,
  "s": "BTCUSDT",
  "U": 1,
  "u": 2,
  "b": [[40000, 1.0], [39999, 2.0]],
  "a": [[40001, 1.5], [40002, 2.5]]
}
```

### Market Price Core 处理后格式
```json
{
  "e": "depthUpdate",
  "E": 1704067200000,
  "s": "BTCUSDT",
  "U": 1,
  "u": 2,
  "b": [[40000, 1.0], [39999, 2.0]],
  "a": [[40001, 1.5], [40002, 2.5]]
}
```

### Public Push Core 推送到 WebSocket 格式
```json
{
  "stream": "depth.BTCUSDT",
  "data": {
    "e": "depthUpdate",
    "E": 1704067200000,
    "s": "BTCUSDT",
    "U": 1,
    "u": 2,
    "b": [[40000, 1.0], [39999, 2.0]],
    "a": [[40001, 1.5], [40002, 2.5]]
  }
}
```

### 快照数据格式
```json
{
  "stream": "depth.BTCUSDT",
  "data": {
    "e": "depthUpdate",
    "E": 1704067200000,
    "s": "BTCUSDT",
    "U": 1,
    "u": 1,
    "b": [[40000, 1.0], [39999, 2.0]],
    "a": [[40001, 1.5], [40002, 2.5]]
  },
  "snapshot": true
}
```

## 排查步骤

### 1. 检查 WebSocket 连接
- 打开浏览器控制台
- 查看是否有 "WebSocket 连接成功" 日志
- 查看是否有订阅确认消息

### 2. 检查订阅确认
- 应该收到：`{"result": "subscribed", "id": 1771714467383, "data": {"subscribed": ["trade.BTCUSDT", "depth.BTCUSDT", "kline.BTCUSDT.1m"]}}`

### 3. 检查深度数据推送
- 查看浏览器控制台的 "收到 WebSocket 消息" 日志
- 查看是否有 "收到深度数据" 日志
- 查看是否有 "买单示例" 和 "卖单示例" 日志

### 4. 检查后端日志
```bash
# Public Push Core 日志
tail -f public-push-core/logs/public-push.log | grep -E "broadcast|sendMessage|depth"

# Market Price Core 日志
tail -f market-price-core/logs/market-price.log | grep -E "Process depth|Publish depth"

# Match Engine 日志
tail -f match-engine-core/logs/match-engine.log | grep -E "Depth published"
```

### 5. 检查 Kafka Topic 数据
```bash
# 检查 Match Engine 发布的深度数据
kafka-console-consumer --bootstrap-server localhost:9092 --topic market.depth.BTCUSDT --from-beginning

# 检查 Market Price Core 发布的深度数据
kafka-console-consumer --bootstrap-server localhost:9092 --topic market.depth.BTCUSDT --from-beginning
```

### 6. 检查 Redis 快照
```bash
# 检查深度快照
redis-cli GET "market:snapshot:depth:BTCUSDT"
```

## 常见问题

### Q1: 订阅成功但没有收到数据
**可能原因**:
1. Match Engine 没有发布深度数据（订单没有进入 OrderBook）
2. Market Price Core 没有消费深度数据
3. Public Push Core 没有消费深度数据
4. Kafka Topic 不存在或没有数据

**解决方法**:
1. 创建一个订单，确保订单进入 OrderBook
2. 检查 Match Engine 日志，确认是否发布深度数据
3. 检查 Market Price Core 日志，确认是否消费深度数据
4. 检查 Public Push Core 日志，确认是否消费并推送数据

### Q2: 收到数据但无法渲染
**可能原因**:
1. 数据格式不正确
2. 价格/数量解析失败
3. 数据为空或格式异常

**解决方法**:
1. 查看浏览器控制台的调试日志
2. 检查 "买单示例" 和 "卖单示例" 日志
3. 检查是否有 "无效的深度数据" 警告

### Q3: 快照数据没有发送
**可能原因**:
1. Redis 中没有快照数据
2. `sendSnapshot` 方法失败
3. 快照数据格式不正确

**解决方法**:
1. 检查 Redis 中是否有快照数据
2. 查看 Public Push Core 日志，确认是否调用 `sendSnapshot`
3. 检查快照数据格式是否符合预期

---

*最后更新: 2026-02-22*

