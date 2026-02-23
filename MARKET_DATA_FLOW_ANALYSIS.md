# 行情数据流完整链路分析报告

## 📊 执行摘要

**结论: ✅ 从撮合到行情推送的完整链路已实现**

```
Match Engine (撮合)
    ↓ Kafka: trade-event
Market Price Core (8095) [行情计算层]
    ↓ Kafka: market.trade.* / market.depth.* / market.kline.* / market.ticker.*
Public Push Core (8096) [行情推送层]
    ↓ WebSocket
Clients (Web/App/Trader)
```

---

## 🔄 完整数据流链路

### 阶段 1: 撮合引擎生成成交事件

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                              Match Engine Core (端口 8083)                               │
│                                    撮合引擎核心                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘

  DisruptorEngine (RingBuffer)
           │
           ▼ 单线程处理
  MatchingProcessor.onEvent()
           │
           ├─ handleSubmit() → OrderBook.addOrder() → 撮合匹配
           │                      │
           │                      ▼ 生成 Trade
           │              ┌─────────────────┐
           │              │  List<Trade>    │
           │              └────────┬────────┘
           │                       │
           ▼                       ▼
    for each Trade:
           │
           ├─ TradePublisher.publishTrade(trade) ──────────────────────────┐
           │                                                              │
           │   Kafka Topic: trade-event                                   │
           │   Key: tradeId                                               │
           │   Value: {                                                   │
           │       "eventType": "TRADE",                                  │
           │       "symbol": "BTCUSDT",                                   │
           │       "tradeId": "...",                                      │
           │       "price": 5000000000000,      // long, 8位小数          │
           │       "quantity": 100000000,                               │
           │       "isBuyerMaker": true,                                │
           │       "timestamp": 1704067200000                           │
           │   }                                                          │
           │                                                              │
           ▼                                                              │
    Kafka (trade-event topic)  ◄──────────────────────────────────────────┘
```

**关键实现**: 
- ✅ `MatchingProcessor` 使用 `TradePublisher` 发送成交事件
- ✅ 支持 JSON/Protobuf 双协议序列化
- ✅ 统一事件格式，包含 eventType="TRADE"

---

### 阶段 2: 行情计算引擎消费并计算

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                           Market Price Core (端口 8095)                                  │
│                              行情数据计算层 (计算密集型)                                  │
└─────────────────────────────────────────────────────────────────────────────────────────┘

  Kafka Consumer
           │
           ▼
  MatchEventConsumer.onTradeEvent()
           │
           ├─ 解析 JSON: {eventType, symbol, price, quantity, isBuyerMaker, ...}
           │
           ▼
  MarketDataEngineService.onTrade(symbol, trade)
           │
           ├─ 驱动多个引擎:
           │
           │   ┌──────────────────────────────────────────────────────────────┐
           │   │  TradeEngine (成交引擎)                                      │
           │   │  • 记录最新成交价格                                           │
           │   │  • 更新 24h 统计 (high/low/volume/quoteVolume)                │
           │   │  • 发布 Ticker 更新                                           │
           │   └────────────────────────┬─────────────────────────────────────┘
           │                            │
           │   ┌────────────────────────┴─────────────────────────────────────┐
           │   │  KlineEngine (K线引擎)                                         │
           │   │  • 15个周期: 1m, 3m, 5m, 15m, 30m, 1h, 2h, 4h, 6h, 8h, 12h,   │
           │   │            1d, 3d, 1w, 1M                                     │
           │   │  • 实时更新 open/high/low/close/volume                        │
           │   │  • 周期结束时发布 closed kline                                │
           │   └────────────────────────┬─────────────────────────────────────┘
           │                            │
           │   ┌────────────────────────┴─────────────────────────────────────┐
           │   │  OrderBook (深度引擎)                                          │
           │   │  • 重建买卖盘深度                                               │
           │   │  • 计算最佳买卖价 (bestBid/bestAsk)                            │
           │   │  • 发布深度更新 (100档/500档)                                  │
           │   └────────────────────────┬─────────────────────────────────────┘
           │                            │
           ▼                            ▼
  MarketDataPublisher (异步发布)
           │
           ├─ publishTrade()      → Kafka: market.trade.{symbol}
           ├─ publishAggTrade()   → Kafka: market.aggtrade.{symbol} (100ms聚合)
           ├─ publishKline()      → Kafka: market.kline.{symbol}.{interval}
           ├─ publishTicker()     → Kafka: market.ticker.{symbol}
           ├─ publishDepth()      → Kafka: market.depth.{symbol}
           └─ publishMarkPrice()  → Kafka: market.markprice.{symbol}
           
           同时更新 Redis 快照:
           • market:snapshot:trade:{symbol}
           • market:snapshot:kline:{symbol}:{interval}
           • market:snapshot:ticker:{symbol}
           • market:snapshot:depth:{symbol}
```

**Kafka Topics (Market Price Core 输出)**:
```
market.trade.{symbol}         # 实时成交
market.aggtrade.{symbol}      # 聚合成交 (100ms窗口)
market.kline.{symbol}.{int}   # K线数据 (15个周期)
market.ticker.{symbol}        # 24h Ticker统计
market.ticker.all             # 全市场Ticker
market.depth.{symbol}         # 深度更新
market.markprice.{symbol}     # 标记价格
market.markprice.all          # 全市场标记价格
```

---

### 阶段 3: 公有推送系统消费并推送

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                           Public Push Core (端口 8096)                                   │
│                              行情推送层 (IO密集型)                                        │
└─────────────────────────────────────────────────────────────────────────────────────────┘

  KafkaConsumerManager (动态消费者)
           │
           ├─ 按需启动消费者 (有订阅时才消费)
           ├─ 每个频道独立消费者组
           └─ 空闲消费者自动清理
           
           订阅频道示例:
           • trade.BTCUSDT        → 消费 market.trade.BTCUSDT
           • depth.BTCUSDT@100ms  → 消费 market.depth.BTCUSDT
           • kline.1m.BTCUSDT     → 消费 market.kline.BTCUSDT.1m
           • ticker.BTCUSDT       → 消费 market.ticker.BTCUSDT
           • ticker@arr           → 消费 market.ticker.all
           
           ▼
  onMessage(channel, record)
           │
           ├─ 验证消息格式 (e, E, s 字段)
           │
           ▼
  MessageDispatcher.broadcast(channel, message)
           │
           ├─ 获取该频道的所有订阅连接
           ├─ 批量发送 (10-50ms聚合窗口)
           ├─ 序列化 JSON
           │
           ▼
  WebSocket 推送
           │
           └─ 每个订阅的客户端收到:
              {
                  "e": "trade",           // event type
                  "E": 1704067200123,     // event time
                  "s": "BTCUSDT",         // symbol
                  "t": 12345,             // trade id
                  "p": "50000.00",        // price
                  "q": "1.000",           // quantity
                  "T": 1704067200100,     // trade time
                  "m": true               // is buyer maker
              }
```

**WebSocket 协议**:
```javascript
// 客户端订阅
{
    "method": "SUBSCRIBE",
    "params": [
        "trade.BTCUSDT",
        "depth.BTCUSDT@100ms",
        "kline.1m.BTCUSDT"
    ],
    "id": 1
}

// 服务器响应
{
    "result": "subscribed",
    "id": 1,
    "data": {
        "subscribed": ["trade.BTCUSDT", "depth.BTCUSDT@100ms", "kline.1m.BTCUSDT"]
    }
}

// 数据推送 (实时成交)
{
    "e": "trade",
    "E": 1704067200123,
    "s": "BTCUSDT",
    "t": 12345,
    "p": "50000.00",
    "q": "1.000",
    "T": 1704067200100,
    "m": true
}

// 数据推送 (K线)
{
    "e": "kline",
    "E": 1704067200123,
    "s": "BTCUSDT",
    "k": {
        "t": 1704067200000,     // open time
        "T": 1704067259999,     // close time
        "s": "BTCUSDT",
        "i": "1m",              // interval
        "o": "49900.00",        // open
        "c": "50000.00",        // close
        "h": "50100.00",        // high
        "l": "49800.00",        // low
        "v": "100.000",         // volume
        "n": 150,               // number of trades
        "x": false,             // is closed
        "q": "4995000.00",      // quote volume
        "V": "60.000",          // taker buy volume
        "Q": "2997000.00"       // taker buy quote volume
    }
}
```

---

## ✅ 各组件实现状态

### Match Engine Core (8083)

| 组件 | 状态 | 说明 |
|-----|------|------|
| `DisruptorEngine` | ✅ | RingBuffer 配置正确 |
| `MatchingProcessor` | ✅ | 撮合核心，调用 TradePublisher |
| `TradePublisher` | ✅ | 发送 trade-event 到 Kafka |
| `OrderBook` | ✅ | 内存订单簿，高性能撮合 |

### Market Price Core (8095)

| 组件 | 状态 | 说明 |
|-----|------|------|
| `MatchEventConsumer` | ✅ | 消费 trade-event |
| `TradeEngine` | ✅ | 成交统计 |
| `KlineEngine` | ✅ | 15个周期K线 |
| `TickerEngine` | ✅ | 24h Ticker |
| `OrderBook` | ✅ | 深度计算 |
| `MarketDataPublisher` | ✅ | 发布到 Kafka + Redis |

### Public Push Core (8096)

| 组件 | 状态 | 说明 |
|-----|------|------|
| `PublicWebSocketHandler` | ✅ | WebSocket 连接管理 |
| `ConnectionManager` | ✅ | 连接池 (10万+并发) |
| `SubscriptionManager` | ✅ | 订阅管理 (1024/连接) |
| `KafkaConsumerManager` | ✅ | 动态消费者 |
| `MessageDispatcher` | ✅ | 消息分发 |
| `RateLimiter` | ✅ | 多层限流 |

---

## 🔗 Kafka Topic 链路

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                    Kafka Topic 链路                                      │
└─────────────────────────────────────────────────────────────────────────────────────────┘

  Match Engine Core
    │
    ▼ (生产者)
    ┌─────────────────────────────────────────────────────────────┐
    │ Topic: trade-event                                          │
    │ 分区: 全局 topic，可多分区                                    │
    │ 消费者: Market Price Core, Ledger Core, Position Snapshot   │
    └─────────────────────────────────────────────────────────────┘
    
  Market Price Core
    │
    ▼ (消费者 trade-event，生产者 market-events)
    ┌─────────────────────────────────────────────────────────────┐
    │ Topic: market.trade.{symbol}                                │
    │        market.aggtrade.{symbol}                             │
    │        market.kline.{symbol}.{interval}                     │
    │        market.ticker.{symbol}                               │
    │        market.ticker.all                                    │
    │        market.depth.{symbol}                                │
    │        market.markprice.{symbol}                            │
    │ 分区: 每个 symbol 独立 topic，单分区保证顺序                   │
    │ 消费者: Public Push Core                                     │
    └─────────────────────────────────────────────────────────────┘
    
  Public Push Core
    │
    ▼ (消费者)
    动态消费用户订阅的 topic
```

---

## 🎯 完整测试场景

### 测试 1: 下单 → 撮合 → 行情推送

```bash
# 1. 用户连接 WebSocket
wscat -c ws://localhost:8096/ws/public

# 2. 订阅成交频道
{"method": "SUBSCRIBE", "params": ["trade.BTCUSDT"], "id": 1}

# 3. 用户1 挂买单
curl -X POST http://localhost:8080/api/order/create \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": 5000000000000,
    "quantity": 100000000,
    "leverage": 10
  }'

# 4. 用户2 挂卖单（同价格，立即撮合）
curl -X POST http://localhost:8080/api/order/create \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 2,
    "symbol": "BTCUSDT",
    "side": "SELL",
    "orderType": "LIMIT",
    "price": 5000000000000,
    "quantity": 100000000,
    "leverage": 10
  }'

# 5. WebSocket 收到成交推送
{
  "e": "trade",
  "E": 1704067200123,
  "s": "BTCUSDT",
  "t": 12345,
  "p": "50000.00000000",
  "q": "1.00000000",
  "T": 1704067200100,
  "m": false
}
```

---

## 📊 性能指标

| 组件 | 延迟目标 | 实现方式 |
|-----|---------|---------|
| Match Engine → Kafka | < 1ms | Disruptor + 异步发送 |
| Market Price 计算 | < 5ms | 异步处理，批量聚合 |
| Kafka → Public Push | < 10ms | 动态消费者，批量消费 |
| WebSocket 推送 | < 5ms | 批量聚合，压缩传输 |
| 端到端总延迟 | < 50ms | 全流程优化 |

---

## 🔍 架构优势

### 1. 计算与推送分离
```
Market Price Core (8095)    Public Push Core (8096)
├─ 纯 CPU 计算               ├─ 纯 IO 推送
├─ 无 WebSocket              ├─ 无复杂计算
├─ 可独立扩容                ├─ 可独立扩容
└─ 热点 symbol 单独分片      └─ 连接数水平扩展
```

### 2. 分层限流
```
IP 级别 → Session 级别 → Channel 级别
         (Token Bucket 算法)
```

### 3. 可靠性保障
```
├─ Kafka 持久化 (可重放)
├─ Redis 快照 (快速恢复)
├─ 序列号连续性检查 (Gap检测)
└─ 自动重连和补全机制
```

---

## ⚠️ 注意事项

1. **必须启动 Kafka** - 行情数据流依赖 Kafka 作为消息总线
2. **必须启动 Redis** - 用于行情快照缓存
3. **服务启动顺序** - Match Engine → Market Price → Public Push
4. **端口占用** - 确保 8083, 8095, 8096 端口可用
5. **WebSocket 连接数** - 默认支持 10万+ 并发，可配置

---

## ✅ 结论

**从 API Gateway → OMS → Hard Risk → Match Engine → Market Price → Public Push 的完整链路已经实现！**

你可以执行以下测试：
1. ✅ 挂单测试 - 查看 OrderBook 状态
2. ✅ 撮合测试 - 买卖单匹配，查看成交
3. ✅ 行情推送测试 - WebSocket 接收实时成交/K线/深度
4. ✅ 全链路测试 - 下单到客户端推送端到端验证

