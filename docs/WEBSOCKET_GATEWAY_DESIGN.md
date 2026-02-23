# WebSocket Gateway 设计文档

## 1. 服务定位

WebSocket Gateway 是交易所的实时数据推送网关，负责：
- 处理百万级并发 WebSocket 连接
- 管理用户订阅（公有/私有频道）
- 将后端 Kafka 事件转发到客户端
- 实现连接保活和心跳机制

## 2. 架构设计

```
┌─────────────────────────────────────────────────────────────────────┐
│                      WebSocket Gateway (Port: 8096)                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    Connection Manager                        │   │
│  │  - 连接生命周期管理                                           │   │
│  │  - 用户认证 (JWT/API Key)                                    │   │
│  │  - 心跳检测 (Ping/Pong)                                      │   │
│  │  - 连接限流 (IP/用户级)                                       │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                              │                                      │
│  ┌───────────────────────────┼───────────────────────────────────┐ │
│  │                           ▼                                   │ │
│  │  ┌─────────────────────────────────────────────────────────┐ │ │
│  │  │                 Subscription Manager                     │ │ │
│  │  │                                                          │ │ │
│  │  │  Public Subscriptions:                                   │ │ │
│  │  │   - trade@{symbol}       实时成交                         │ │ │
│  │  │   - depth@{symbol}       OrderBook深度                    │ │ │
│  │  │   - kline@{symbol}@{interval}  K线                       │ │ │
│  │  │   - markPrice@{symbol}   标记价格                         │ │ │
│  │  │   - ticker@{symbol}      24小时统计                       │ │ │
│  │  │                                                          │ │ │
│  │  │  Private Subscriptions (需认证):                          │ │ │
│  │  │   - executionReport      订单状态变化                      │ │ │
│  │  │   - account              账户资金变化                      │ │ │
│  │  │   - position             持仓变化                         │ │ │
│  │  │   - balance              余额变化                         │ │ │
│  │  │   - fundingFee           资金费用结算                      │ │ │
│  │  └─────────────────────────────────────────────────────────┘ │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                              │                                      │
│  ┌───────────────────────────┼───────────────────────────────────┐ │
│  │                           ▼                                   │ │
│  │  ┌─────────────────────────────────────────────────────────┐ │ │
│  │  │                  Message Router                          │ │ │
│  │  │                                                          │ │ │
│  │  │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐ │ │ │
│  │  │  │ Public Hub  │    │ Private Hub │    │ System Hub  │ │ │ │
│  │  │  │  (行情数据)  │◄──►│  (用户数据)  │◄──►│  (系统通知)  │ │ │ │
│  │  │  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘ │ │ │
│  │  │         │                  │                  │        │ │ │
│  │  │         └──────────────────┼──────────────────┘        │ │ │
│  │  │                            │                           │ │ │
│  │  │         ┌──────────────────┼──────────────────┐        │ │ │
│  │  │         ▼                  ▼                  ▼        │ │ │
│  │  │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐ │ │ │
│  │  │  │ Connection  │    │ Connection  │    │ Connection  │ │ │ │
│  │  │  │    #1       │    │    #2       │    │    #N       │ │ │ │
│  │  │  └─────────────┘    └─────────────┘    └─────────────┘ │ │ │
│  │  └─────────────────────────────────────────────────────────┘ │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## 3. 连接管理

### 3.1 连接建立流程

```
客户端                                    WebSocket Gateway
   │                                           │
   │  1. WebSocket Handshake                   │
   │ ─────────────────────────────────────────>│
   │                                           │
   │  2. 响应连接建立                           │
   │ <─────────────────────────────────────────│
   │                                           │
   │  3. 订阅请求 {method: "SUBSCRIBE", params: ["btcusdt@trade"]}
   │ ─────────────────────────────────────────>│
   │                                           │
   │  4. 确认订阅 {"result": null, "id": 1}     │
   │ <─────────────────────────────────────────│
   │                                           │
   │  5. 实时数据推送                           │
   │ <═════════════════════════════════════════│
```

### 3.2 私有连接认证流程

```
客户端                                    WebSocket Gateway
   │                                           │
   │  1. WebSocket Handshake                   │
   │     Headers: X-API-KEY, X-API-SIGN        │
   │ ─────────────────────────────────────────>│
   │                                           │
   │  2. 验证签名                               │
   │     - 验证API Key                          │
   │     - 验证时间戳                           │
   │     - 验证签名                             │
   │                                           │
   │  3. 认证成功/失败                           │
   │ <─────────────────────────────────────────│
   │                                           │
   │  4. 订阅私有频道                            │
   │     {method: "SUBSCRIBE", params: ["executionReport"]}
   │ ─────────────────────────────────────────>│
```

## 4. 消息协议

### 4.1 客户端请求格式

```json
// 订阅请求
{
  "method": "SUBSCRIBE",
  "params": [
    "btcusdt@trade",
    "btcusdt@depth",
    "executionReport"
  ],
  "id": 1
}

// 取消订阅
{
  "method": "UNSUBSCRIBE",
  "params": [
    "btcusdt@trade"
  ],
  "id": 2
}

// 心跳Ping
{
  "method": "PING",
  "id": 3
}

// 查询订阅列表
{
  "method": "LIST_SUBSCRIPTIONS",
  "id": 4
}
```

### 4.2 服务端响应格式

```json
// 订阅确认
{
  "result": null,
  "id": 1
}

// Pong响应
{
  "method": "PONG",
  "id": 3
}

// 错误响应
{
  "error": {
    "code": 1,
    "msg": "Invalid symbol"
  },
  "id": 1
}
```

### 4.3 数据推送格式

```json
// 成交数据 (trade@btcusdt)
{
  "e": "trade",           // 事件类型
  "E": 1234567890,        // 事件时间
  "s": "BTCUSDT",         // 交易对
  "p": "50000.50",        // 成交价
  "q": "0.01",            // 成交量
  "T": 1234567889,        // 成交时间
  "m": true               // 是否为主动卖出
}

// OrderBook深度 (depth@btcusdt)
{
  "e": "depth",
  "E": 1234567890,
  "s": "BTCUSDT",
  "b": [["50000", "1.5"], ["49999", "2.0"]],  // 买盘
  "a": [["50001", "1.0"], ["50002", "0.5"]],  // 卖盘
  "u": 12345              // 更新ID
}

// K线数据 (kline@btcusdt@1m)
{
  "e": "kline",
  "E": 1234567890,
  "s": "BTCUSDT",
  "k": {
    "t": 1234560000,      // K线开始时间
    "T": 1234560059,      // K线结束时间
    "s": "BTCUSDT",
    "i": "1m",            // 周期
    "o": "50000",         // 开盘价
    "c": "50100",         // 收盘价
    "h": "50200",         // 最高价
    "l": "49900",         // 最低价
    "v": "100",           // 成交量
    "n": 50               // 成交笔数
  }
}

// 订单状态变化 (executionReport)
{
  "e": "executionReport",
  "E": 1234567890,
  "s": "BTCUSDT",
  "i": 12345,             // 订单ID
  "c": "myOrder1",        // 客户端订单ID
  "S": "BUY",             // 买卖方向
  "o": "LIMIT",           // 订单类型
  "q": "1.0",             // 原始数量
  "z": "0.5",             // 已成交数量
  "p": "50000",           // 订单价格
  "X": "PARTIALLY_FILLED", // 订单状态
  "x": "TRADE",           // 事件类型
  "l": "0.5",             // 本次成交数量
  "L": "50000",           // 本次成交价格
  "n": "0.0005",          // 手续费
  "N": "USDT"             // 手续费资产
}
```

## 5. Kafka Consumer 设计

### 5.1 公有数据消费者

```java
@Component
public class PublicDataConsumer {
    
    @Autowired
    private PublicMessageHub publicHub;
    
    // 成交数据
    @KafkaListener(topics = "trade-topic")
    public void onTrade(TradeEvent event) {
        String channel = event.getSymbol().toLowerCase() + "@trade";
        publicHub.broadcast(channel, event);
    }
    
    // OrderBook深度
    @KafkaListener(topics = "orderbook-snapshot-topic")
    public void onOrderBook(OrderBookSnapshot event) {
        String channel = event.getSymbol().toLowerCase() + "@depth";
        publicHub.broadcast(channel, event);
    }
    
    // 标记价格
    @KafkaListener(topics = "mark-price-update")
    public void onMarkPrice(MarkPriceEvent event) {
        String channel = event.getSymbol().toLowerCase() + "@markPrice";
        publicHub.broadcast(channel, event);
    }
    
    // K线数据
    @KafkaListener(topics = "kline-topic")
    public void onKline(KlineEvent event) {
        String channel = event.getSymbol().toLowerCase() + "@kline_" + event.getInterval();
        publicHub.broadcast(channel, event);
    }
}
```

### 5.2 私有数据消费者

```java
@Component
public class PrivateDataConsumer {
    
    @Autowired
    private PrivateMessageHub privateHub;
    
    // 订单状态
    @KafkaListener(topics = "order-state-topic")
    public void onOrderState(OrderStateEvent event) {
        privateHub.sendToUser(event.getUserId(), "executionReport", event);
    }
    
    // 账户资金变化
    @KafkaListener(topics = "account-change-topic")
    public void onAccountChange(AccountChangeEvent event) {
        privateHub.sendToUser(event.getUserId(), "account", event);
    }
    
    // 持仓变化
    @KafkaListener(topics = "position-delta-topic")
    public void onPositionChange(PositionDeltaEvent event) {
        privateHub.sendToUser(event.getUserId(), "position", event);
    }
    
    // 资金费用结算
    @KafkaListener(topics = "funding-settlement")
    public void onFundingFee(FundingFeeEvent event) {
        privateHub.sendToUser(event.getUserId(), "fundingFee", event);
    }
}
```

## 6. 高可用设计

### 6.1 集群架构

```
                        Load Balancer
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
        ▼                     ▼                     ▼
   ┌─────────┐          ┌─────────┐          ┌─────────┐
   │ WS Node │◄────────►│ WS Node │◄────────►│ WS Node │
   │   #1    │  Redis   │   #2    │  Redis   │   #3    │
   └────┬────┘  Pub/Sub └────┬────┘  Pub/Sub └────┬────┘
        │                     │                     │
        └─────────────────────┼─────────────────────┘
                              │
                    ┌─────────┴─────────┐
                    │   Redis Cluster   │
                    │  - 连接状态       │
                    │  - 订阅信息       │
                    │  - 消息路由       │
                    └───────────────────┘
```

### 6.2 水平扩展策略

1. **一致性哈希路由**: 基于 UserID 路由到特定节点
2. **Redis Pub/Sub**: 节点间消息同步
3. **会话粘滞**: 同一用户连接固定到同一节点

## 7. 配置参数

```yaml
websocket:
  gateway:
    port: 8096
    
    # 连接管理
    connection:
      max-connections-per-ip: 100
      max-connections-per-user: 10
      heartbeat-interval-seconds: 30
      heartbeat-timeout-seconds: 60
      
    # 消息限流
    rate-limit:
      subscribe-per-second: 10
      message-per-second: 100
      
    # 集群配置
    cluster:
      enabled: true
      redis:
        host: localhost
        port: 6379
        
    # Kafka配置
    kafka:
      public-topics:
        - trade-topic
        - orderbook-snapshot-topic
        - mark-price-update
        - kline-topic
      private-topics:
        - order-state-topic
        - account-change-topic
        - position-delta-topic
        - funding-settlement
```

## 8. 监控指标

| 指标名 | 类型 | 说明 |
|-------|------|------|
| ws_connections_total | Gauge | 当前连接数 |
| ws_connections_per_user | Histogram | 每个用户的连接数分布 |
| ws_message_received | Counter | 接收消息总数 |
| ws_message_sent | Counter | 发送消息总数 |
| ws_message_dropped | Counter | 丢弃消息数（限流） |
| ws_latency | Histogram | 消息处理延迟 |
| ws_subscriptions_total | Gauge | 总订阅数 |
| ws_kafka_lag | Gauge | Kafka消费延迟 |

---

*文档版本: v1.0*  
*更新日期: 2026-02-18*
