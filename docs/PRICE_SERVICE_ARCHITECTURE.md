# 价格服务架构设计文档

## 1. 架构决策：价格服务是否应该合并？

### 1.1 分析结论：指数价格、标记价格、资金费率应该**分离但协同**

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        价格服务架构 (Price Services)                      │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐     │
│  │ Index Price     │    │ Mark Price      │    │ Funding Rate    │     │
│  │ Service         │◄───│ Service         │◄───│ Service         │     │
│  │ (Port: 8093)    │    │ (Port: 8094)    │    │ (Port: 8088)    │     │
│  └────────┬────────┘    └────────┬────────┘    └────────┬────────┘     │
│           │                      │                      │              │
│           │ 指数价格              │ 标记价格              │ 资金费率       │
│           │ (外部交易所加权)       │ (基于指数+溢价)        │ (基于溢价+利率) │
│           │                      │                      │              │
│           ▼                      ▼                      ▼              │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    Market Data Bus (Kafka)                       │   │
│  │  Topics: index-price-update | mark-price-update | funding-rate   │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│           │                      │                      │              │
│           ▼                      ▼                      ▼              │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐     │
│  │ Risk Engine     │    │ Liquidation     │    │ TP/SL           │     │
│  │ (保证金计算)      │    │ (强平触发)        │    │ (条件单触发)      │     │
│  └─────────────────┘    └─────────────────┘    └─────────────────┘     │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.2 分离的原因

| 维度 | Index Price | Mark Price | Funding Rate |
|------|-------------|------------|--------------|
| **更新频率** | 1-5秒 | 500ms-1s | 每8小时结算 |
| **计算复杂度** | 多源加权平均 | EMA平滑+溢价 | 溢价+利率+clamp |
| **数据源** | 外部交易所 | 内部标记+指数 | 持仓+标记价格 |
| **业务用途** | 公允价值参考 | 强平/未实现盈亏 | 资金费用结算 |
| **可用性要求** | 高(可降级) | 极高(影响强平) | 极高(影响结算) |

### 1.3 为什么资金费率服务不应该包含价格计算？

1. **单一职责原则 (SRP)**
   - Index Price: 只负责从外部获取并计算公允指数价格
   - Mark Price: 只负责基于OrderBook计算标记价格并平滑
   - Funding Rate: 只负责资金费率计算和结算

2. **不同的高可用要求**
   - Index Price 可以使用缓存降级（使用上一次的指数价格）
   - Mark Price 必须实时可用（影响强平）
   - Funding Rate 结算必须在特定时间点完成

3. **不同的扩展策略**
   - Index Price: 需要对接更多外部交易所时可以独立扩展
   - Mark Price: 需要更高频更新时可以独立优化
   - Funding Rate: 结算时需要批量处理，需要不同的资源分配

---

## 2. 数据流设计

### 2.1 指数价格服务 (Index Price Service)

```
外部交易所API (Binance/OKX/Coinbase)
         │
         ▼
┌─────────────────────────────────┐
│    Price Aggregator             │
│    - 多源数据采集                │
│    - 异常值过滤                  │
│    - 加权平均计算                │
└───────────┬─────────────────────┘
            │
    ┌───────┴───────┐
    ▼               ▼
┌─────────┐   ┌─────────────────┐
│  Redis  │   │  Kafka Topic    │
│  (Cache)│   │  index-price-update
└────┬────┘   └────────┬────────┘
     │                  │
     ▼                  ▼
┌─────────────────────────────────┐
│     Consumers                   │
│  - Mark Price Service           │
│  - Funding Rate Service         │
│  - Risk Monitor                 │
└─────────────────────────────────┘
```

### 2.2 标记价格服务 (Mark Price Service)

```
Kafka: match-trade-topic (成交数据)
         │
         ▼
┌─────────────────────────────────┐
│    Mark Price Calculator        │
│    - 基于最近成交价              │
│    - 基于OrderBook买一卖一        │
│    - EMA平滑处理                 │
└───────────┬─────────────────────┘
            │
    ┌───────┴───────┐
    ▼               ▼
┌─────────┐   ┌─────────────────┐
│  Redis  │   │  Kafka Topic    │
│  (Cache)│   │  mark-price-update
└────┬────┘   └────────┬────────┘
     │                  │
     ▼                  ▼
┌─────────────────────────────────┐
│     Consumers                   │
│  - Funding Rate Service         │
│  - Risk Monitor (强平价计算)      │
│  - Position Service ( unrealized PnL )
│  - TP/SL Service (触发判断)       │
│  - WebSocket Gateway (推送)       │
└─────────────────────────────────┘
```

### 2.3 资金费率服务 (Funding Rate Service)

```
┌─────────────────────────────────────────────────┐
│  Inputs                                         │
│  - index-price-update (Kafka)                   │
│  - mark-price-update (Kafka)                    │
│  - position-stats (Position Service API)        │
└───────────────────┬─────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────┐
│    Funding Rate Calculator      │
│    - Premium Index = (Mark-Index)/Index
│    - Funding Rate = Premium + Interest
│    - Clamp to [-0.75%, +0.75%]  │
└───────────┬─────────────────────┘
            │
    ┌───────┴───────┐
    ▼               ▼
┌─────────┐   ┌─────────────────┐
│  MySQL  │   │  Kafka Topic    │
│ (History)│  │  funding-rate-calc
└─────────┘   └────────┬────────┘
                       │
                       ▼
              ┌─────────────────┐
              │  Settlement Job │
              │  (每8小时执行)   │
              └────────┬────────┘
                       │
                       ▼
              ┌─────────────────┐
              │  Ledger Entry   │
              └─────────────────┘
```

---

## 3. Market Service 架构设计

### 3.1 Market Service 定位

**Market Service 是行情数据中心，负责聚合、计算、分发市场数据。**

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Market Service (Port: 8095)                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Input Layer (数据输入层)                                            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌───────────┐  │
│  │ Trade Event │  │ OrderBook   │  │ Mark Price  │  │ Index Price│  │
│  │ (撮合成交)   │  │ (深度数据)   │  │ (标记价格)   │  │ (指数价格) │  │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └─────┬─────┘  │
│         │                │                │               │        │
│         └────────────────┴────────────────┴───────────────┘        │
│                          │                                          │
│                          ▼                                          │
│  Processing Layer (处理层)                                          │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  - K线计算 (1m/5m/15m/1h/4h/1d)                              │   │
│  │  - 24h统计 (成交量/成交额/涨跌幅)                             │   │
│  │  - OrderBook聚合 (买盘/卖盘深度)                             │   │
│  │  - 最近成交 (Latest Trades)                                  │   │
│  └────────────────────────┬────────────────────────────────────┘   │
│                           │                                         │
│  Output Layer (输出层)                                               │
│  ┌────────────────────────┴────────────────────────────────────┐   │
│  │                                                             │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │   │
│  │  │ REST API     │  │ WebSocket    │  │ Kafka Publisher  │  │   │
│  │  │ (历史数据)    │  │ (实时推送)    │  │ (下游服务)        │  │   │
│  │  └──────────────┘  └──────────────┘  └──────────────────┘  │   │
│  │                                                             │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 Market Service 上游数据源

| 数据源 | Topic/接口 | 用途 | 优先级 |
|--------|-----------|------|--------|
| Match Engine | `trade-topic` | 实时成交数据 | P0 |
| Match Engine | `orderbook-snapshot` | OrderBook深度 | P0 |
| Mark Price Service | `mark-price-update` | 标记价格更新 | P0 |
| Index Price Service | `index-price-update` | 指数价格更新 | P1 |
| Funding Rate Service | `funding-rate-calc` | 资金费率更新 | P1 |
| Ledger Core | `account-change-topic` | 24h成交量统计 | P2 |

### 3.3 Market Service 核心功能

```java
// Market Service 核心服务接口
public interface MarketDataService {
    
    // K线数据管理
    List<Kline> getKlines(String symbol, KlineInterval interval, long start, long end);
    void onNewTrade(TradeEvent trade); // 触发K线更新
    
    // OrderBook管理
    OrderBookSnapshot getOrderBook(String symbol, int depth);
    void onOrderBookChange(OrderBookChange change);
    
    // 24小时统计
    Ticker24h getTicker24h(String symbol);
    
    // 最近成交
    List<RecentTrade> getRecentTrades(String symbol, int limit);
    
    // 标记价格
    MarkPriceInfo getMarkPrice(String symbol);
    
    // 资金费率
    FundingRateInfo getFundingRate(String symbol);
}
```

---

## 4. 推送服务架构设计

### 4.1 推送类型定义

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        推送服务总体架构                                   │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │                    WebSocket Gateway                            │   │
│   │                    (Port: 8096)                                 │   │
│   │  - 连接管理 (百万级并发)                                          │   │
│   │  - 协议解析 (WebSocket/HTTP2)                                    │   │
│   │  - 消息路由 (Public/Private)                                     │   │
│   │  - 心跳管理                                                      │   │
│   └────────────────────────┬────────────────────────────────────────┘   │
│                            │                                            │
│           ┌────────────────┼────────────────┐                           │
│           │                │                │                           │
│           ▼                ▼                ▼                           │
│   ┌──────────────┐ ┌──────────────┐ ┌──────────────┐                   │
│   │  Public Hub  │ │ Private Hub  ││ System Hub   │                   │
│   │  公有推送中心 │ │ 私有推送中心  ││ 系统推送中心  │                   │
│   └──────┬───────┘ └──────┬───────┘ └──────┬───────┘                   │
│          │                │                │                           │
│          ▼                ▼                ▼                           │
│   ┌──────────────┐ ┌──────────────┐ ┌──────────────┐                   │
│   │ Kafka Consumer│ │ Kafka Consumer│ │ Kafka Consumer│                   │
│   │ - trade-topic │ │ - order-state │ │ - notification│                   │
│   │ - kline-topic │ │ - position-delta│ - system-alert│                   │
│   │ - mark-price  │ │ - account-change│               │                   │
│   └──────────────┘ └──────────────┘ └──────────────┘                   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 4.2 公有推送 (Public Push)

**定义**: 所有用户可见的市场数据，无需认证即可订阅

| 频道 | 推送内容 | 频率 | 示例 |
|------|---------|------|------|
| `trade@{symbol}` | 实时成交 | 逐笔 | `{"p": "50000", "q": "0.01", "T": 1234567890}` |
| `depth@{symbol}` | OrderBook深度 | 100ms | `{"bids": [["50000", "1.5"]], "asks": ...}` |
| `kline@{symbol}@{interval}` | K线数据 | 按周期 | `{"t": 1234560000, "o": "50000", ...}` |
| `markPrice@{symbol}` | 标记价格 | 1s | `{"p": "50000.5", "r": "0.0001"}` |
| `indexPrice@{symbol}` | 指数价格 | 5s | `{"p": "50000"}` |
| `fundingRate@{symbol}` | 资金费率 | 事件触发 | `{"r": "0.0001", "T": 1704067200000}` |
| `ticker@{symbol}` | 24小时统计 | 1s | `{"c": "50000", "v": "1000", ...}` |
| `forceOrder@{symbol}` | 强平订单 | 逐笔 | `{"p": "49000", "q": "0.5", "S": "SELL"}` |

**公有推送数据流:**

```
Kafka Topics
    │
    ├─ trade-topic ──────────────┐
    ├─ orderbook-snapshot-topic ─┤
    ├─ mark-price-update ────────┤
    ├─ index-price-update ───────┼──► Public Stream Processor
    ├─ funding-rate-calc ────────┤    (数据标准化、聚合)
    └─ liquidation-topic ────────┘
                                       │
                                       ▼
                              ┌─────────────────┐
                              │  Public Hub     │
                              │  - 订阅管理      │
                              │  - 消息分发      │
                              └────────┬────────┘
                                       │
                    ┌──────────────────┼──────────────────┐
                    │                  │                  │
                    ▼                  ▼                  ▼
            ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
            │  Connection │    │  Connection │    │  Connection │
            │     #1      │    │     #2      │    │     #N      │
            │  (symbol=A) │    │  (symbol=B) │    │  (symbol=*) │
            └─────────────┘    └─────────────┘    └─────────────┘
```

### 4.3 私有推送 (Private Push)

**定义**: 仅特定用户可见的个人数据，需要认证后才能订阅

| 频道 | 推送内容 | 触发条件 | 示例 |
|------|---------|---------|------|
| `executionReport` | 订单状态变化 | 订单创建/成交/撤销 | `{"i": 123, "S": "FILLED", ...}` |
| `account` | 账户资金变化 | 保证金变动 | `{"B": [{"a": "USDT", "wb": "1000"}]}` |
| `position` | 持仓变化 | 开平仓 | `{"s": "BTCUSDT", "pa": "1.5", ...}` |
| `balance` | 余额变化 | 充值/提现/结算 | `{"a": "USDT", "f": "1000", "l": "100"}` |
| `marginCall` | 追加保证金通知 | 保证金率过低 | `{"s": "BTCUSDT", "m": "0.05"}` |
| `fundingFee` | 资金费用结算 | 资金费率结算 | `{"s": "BTCUSDT", "f": "-5.2"}` |

**私有推送数据流:**

```
Kafka Topics
    │
    ├─ order-state-topic ──────┐
    ├─ account-change-topic ───┤
    ├─ position-delta-topic ───┼──► Private Stream Processor
    ├─ funding-settlement ─────┤    (用户数据过滤)
    └─ margin-call-topic ──────┘
                                       │
                                       ▼
                              ┌─────────────────┐
                              │  Private Hub    │
                              │  - 用户认证      │
                              │  - 权限校验      │
                              │  - 单用户分发    │
                              └────────┬────────┘
                                       │
                    ┌──────────────────┼──────────────────┐
                    │                  │                  │
                    ▼                  ▼                  ▼
            ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
            │  User #1001 │    │  User #1002 │    │  User #1003 │
            │ Connection  │    │ Connection  │    │ Connection  │
            └─────────────┘    └─────────────┘    └─────────────┘
```

### 4.4 推送技术实现

```
┌─────────────────────────────────────────────────────────────────────┐
│                    WebSocket Gateway 架构                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    Load Balancer                            │   │
│  │              (基于 UserID 一致性哈希路由)                      │   │
│  └────────────────────────┬────────────────────────────────────┘   │
│                           │                                         │
│       ┌───────────────────┼───────────────────┐                    │
│       ▼                   ▼                   ▼                    │
│  ┌─────────┐        ┌─────────┐        ┌─────────┐                 │
│  │ WS Node │        │ WS Node │        │ WS Node │                 │
│  │  #1     │◄──────►│  #2     │◄──────►│  #3     │                 │
│  │ (Hash   │ Redis  │ (Hash   │ Redis  │ (Hash   │                 │
│  │  Ring)  │ Pub/Sub│  Ring)  │ Pub/Sub│  Ring)  │                 │
│  └────┬────┘        └────┬────┘        └────┬────┘                 │
│       │                  │                  │                       │
│       └──────────────────┼──────────────────┘                       │
│                          │                                          │
│                          ▼                                          │
│                   ┌─────────────┐                                   │
│                   │ Redis Cluster│                                  │
│                   │ - 连接状态    │                                  │
│                   │ - 订阅管理    │                                  │
│                   │ - 消息路由    │                                  │
│                   └─────────────┘                                   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 5. Notification Service 设计

### 5.1 服务定位

**Notification Service 是统一通知中心，负责将系统事件转换为用户可感知的通知。**

```
┌─────────────────────────────────────────────────────────────────────┐
│                Notification Service (Port: 8097)                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Input Events (Kafka Consumers)                                     │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐   │
│  │ Order Event │ │ Risk Event  │ │ Funding Fee │ │ System Event│   │
│  └──────┬──────┘ └──────┬──────┘ └──────┬──────┘ └──────┬──────┘   │
│         │               │               │               │          │
│         └───────────────┴───────────────┴───────────────┘          │
│                           │                                         │
│                           ▼                                         │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              Notification Router (规则引擎)                  │   │
│  │                                                             │   │
│  │  Rule Example:                                              │   │
│  │  IF event.type == "ORDER_FILLED"                            │   │
│  │     THEN send Push + Email                                  │   │
│  │                                                             │   │
│  │  IF event.type == "LIQUIDATION_WARNING"                     │   │
│  │     THEN send Push + SMS + Email                            │   │
│  └────────────────────────┬────────────────────────────────────┘   │
│                           │                                         │
│           ┌───────────────┼───────────────┐                        │
│           ▼               ▼               ▼                        │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │
│  │ Push Sender │  │ SMS Sender  │  │Email Sender │                 │
│  │ (App Push)  │  │ (Twilio)    │  │ (SendGrid)  │                 │
│  └─────────────┘  └─────────────┘  └─────────────┘                 │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.2 通知类型矩阵

| 事件类型 | 推送 | 短信 | 邮件 | 优先级 | 场景 |
|---------|------|------|------|--------|------|
| 订单成交 | ✅ | ❌ | ❌ | 低 | 实时交易反馈 |
| 订单撤销 | ✅ | ❌ | ❌ | 低 | 操作确认 |
| 强平预警 | ✅ | ✅ | ✅ | 高 | 保证金不足 |
| 强平执行 | ✅ | ✅ | ✅ | 紧急 | 仓位被平 |
| 资金费率结算 | ✅ | ❌ | ❌ | 中 | 资金费用收支 |
| 大额充值 | ✅ | ✅ | ✅ | 高 | 账户安全 |
| 登录异常 | ✅ | ✅ | ✅ | 紧急 | 安全风险 |
| 系统维护 | ✅ | ❌ | ✅ | 中 | 运营公告 |

---

## 6. 端口分配与服务汇总

| 服务 | 端口 | 数据库 | 核心功能 |
|------|------|--------|----------|
| api-gateway | 8080 | - | 统一入口、鉴权、限流 |
| oms-core | 8081 | exchange_oms | 订单生命周期管理 |
| hard-risk-core | 8082 | - | 同步硬风控检查 |
| match-engine-core | 8083 | - | 撮合引擎 |
| ledger-core | 8084 | exchange_ledger | 权威账本 |
| snapshot-account-core | 8085 | exchange_ledger | 资金快照 |
| position-snapshot-core | 8086 | exchange_ledger | 持仓快照 |
| replay-core | 8087 | - | 重放服务 |
| funding-rate-core | 8088 | exchange_funding | 资金费率结算 |
| tp-sl-core | 8089 | exchange_tpsl | 止盈止损 |
| margin-mode-core | 8090 | exchange_margin | 保证金模式 |
| adl-core | 8091 | exchange_adl | 自动减仓 |
| market-maker-core | 8092 | exchange_mm | 做市商接口 |
| **index-price-core** | **8093** | exchange_market | 指数价格计算 |
| **mark-price-core** | **8094** | exchange_market | 标记价格计算 |
| **market-price-core** | **8095** | exchange_market | 行情数据服务 |
| **websocket-gateway** | **8096** | - | WebSocket推送网关 |
| **notification-service** | **8097** | exchange_notification | 通知服务 |

---

## 7. Kafka Topic 汇总

### 7.1 价格相关 Topics

| Topic | 生产者 | 消费者 | 说明 |
|-------|--------|--------|------|
| `index-price-update` | index-price-core | funding-rate-core, mark-price-core, risk-monitor | 指数价格更新 |
| `mark-price-update` | mark-price-core | funding-rate-core, position-core, tp-sl-core, websocket-gateway | 标记价格更新 |
| `funding-rate-calc` | funding-rate-core | oms-core, position-core, websocket-gateway | 资金费率计算 |
| `funding-settlement` | funding-rate-core | ledger-core, account-core, notification | 资金费用结算 |

### 7.2 行情相关 Topics

| Topic | 生产者 | 消费者 | 说明 |
|-------|--------|--------|------|
| `trade-topic` | match-engine-core | market-price-core, websocket-gateway | 成交数据 |
| `orderbook-snapshot` | match-engine-core | market-price-core, websocket-gateway | OrderBook深度 |
| `kline-topic` | market-price-core | websocket-gateway | K线数据 |
| `ticker-topic` | market-price-core | websocket-gateway | 24小时统计 |
| `liquidation-topic` | liquidation-core | market-price-core, websocket-gateway | 强平数据 |

### 7.3 推送相关 Topics

| Topic | 生产者 | 消费者 | 说明 |
|-------|--------|--------|------|
| `ws-public-topic` | market-price-core | websocket-gateway | 公有推送数据 |
| `ws-private-topic` | various | websocket-gateway | 私有推送数据 |
| `notification-topic` | various | notification-service | 通知消息 |

---

## 8. 数据闭环验证

### 8.1 价格数据闭环

```
[外部交易所] ──► [Index Price Service] ──► Kafka(index-price-update)
                                               │
                                               ▼
[Mark Price Service] ◄── [Match Engine] ──► [Funding Rate Service]
       │                                          │
       └──────────────► Kafka ◄──────────────────┘
       (mark-price-update)
                          │
    ┌─────────────────────┼─────────────────────┐
    ▼                     ▼                     ▼
[Risk Monitor]      [Position Service]    [WebSocket Gateway]
(强平计算)            (未实现盈亏)            (实时推送)
```

### 8.2 交易数据闭环

```
[用户下单] ──► [OMS] ──► [Hard Risk] ──► [Match Engine]
                                              │
                                              ▼
[用户] ◄── [Notification] ◄── [Ledger] ◄── [Trade Event]
   ▲                                           │
   └────────── [WebSocket] ◄───────────────────┘
       (订单状态/成交推送)
```

---

## 9. 实施建议

### Phase 1: 核心价格服务 (Week 1-2)
1. 创建 index-price-core 模块
2. 创建 mark-price-core 模块
3. 完善 funding-rate-core 集成

### Phase 2: 行情服务 (Week 3-4)
1. 创建 market-price-core 模块
2. K线计算与存储
3. OrderBook聚合

### Phase 3: 推送服务 (Week 5-6)
1. 创建 websocket-gateway 模块
2. 公有推送实现
3. 私有推送实现

### Phase 4: 通知服务 (Week 7)
1. 创建 notification-service 模块
2. 多渠道通知接入

---

*文档版本: v1.0*  
*更新日期: 2026-02-18*  
*作者: Product Manager & CTO*
