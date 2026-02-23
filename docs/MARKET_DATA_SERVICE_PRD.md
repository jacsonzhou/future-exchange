# 📊 合约交易系统 — 行情服务（Market Data Service）产品PRD

> 定位：对标 Binance / OKX / Bybit 架构级别  
> 目标：毫秒级实时行情 + 可水平扩展 + 与资金系统解耦  
> 版本：v2.0 Production Ready  
> 最后更新：2026-02-18

---

## 一、设计目标（Design Goals）

### 1.1 核心原则

| 原则 | 说明 |
|------|------|
| **单一事实源** | 行情数据只来自撮合引擎（唯一市场事实源） |
| **与账本解耦** | 行情服务不依赖资金结算、持仓计算 |
| **低延迟优先** | 推送延迟 < 10ms，优先于强一致性 |
| **推送优先** | Push First, Query Second，客户端只订阅不轮询 |
| **水平扩展** | 支持多合约分片，无状态WS网关 |

### 1.2 性能目标（Production SLA）

| 指标 | 目标值 | 对标交易所 |
|------|--------|-----------|
| 成交推送延迟 | ≤ 10 ms | Binance ~5ms |
| 深度更新延迟 | ≤ 5 ms | OKX ~3ms |
| K线生成延迟 | ≤ 50 ms | Bybit ~30ms |
| 支持WS连接数 | ≥ 500,000 | Binance 1M+ |
| 单合约吞吐 | ≥ 200k trades/s | OKX 100k+/s |
| 丢包恢复时间 | ≤ 500 ms | 自动重建 |

---

## 二、系统定位（System Responsibility）

### 2.1 行情服务负责

```
┌─────────────────────────────────────────────────────────┐
│  ✅ 行情服务（Market Data Service）                      │
├─────────────────────────────────────────────────────────┤
│  ├─ 成交（Trades）- 实时成交推送                        │
│  ├─ 深度（OrderBook Depth）- L2/L3 盘口                │
│  ├─ 最优价（BBO）- Best Bid/Offer                       │
│  ├─ K线（Candles）- 多周期聚合                         │
│  ├─ Ticker（24h统计）- 涨跌幅/成交量                    │
│  ├─ 标记价（Mark Price）- 平滑后的公允价格              │
│  ├─ 指数价（Index Price）- 外部市场加权                 │
│  ├─ WebSocket 推送 - 订阅/发布模型                      │
│  └─ 快照+增量恢复 - 断线重建机制                        │
└─────────────────────────────────────────────────────────┘
```

### 2.2 行情服务不负责

| 功能 | 负责服务 | 原因 |
|------|---------|------|
| 余额查询 | snapshot-account-core | 资金数据，非行情 |
| 持仓查询 | position-snapshot-core | 用户私有数据 |
| 盈亏计算 | ledger-core | 需要资金结算 |
| 风控检查 | hard-risk-core | 交易前检查 |
| 清结算 | ledger-core | 资金流转 |

**设计哲学**：行情是市场事实的实时投影，不是账户计算结果。

---

## 三、输入数据（Data Source）

### 3.1 Kafka Topic 设计

```
┌─────────────────────────────────────────────────────────┐
│              Match Engine（撮合引擎）                    │
│                      ↓ 发布                              │
│         Kafka: match-event-topic（全局）                │
│                      ↓ 消费                              │
│              Market Data Service                        │
└─────────────────────────────────────────────────────────┘
```

| Topic | 分区策略 | 消费者 | 说明 |
|-------|---------|--------|------|
| `match-event-topic` | symbol分区 | 行情服务 | 撮合事件总线 |
| `orderbook-delta-{symbol}` | 单分区 | 行情服务 | 深度增量更新 |
| `index-price-topic` | 多分区 | 行情服务 | 外部指数价 |

### 3.2 事件类型定义

#### 3.2.1 成交事件（Trade Event）

```json
{
  "eventType": "TRADE",
  "sequence": 912381233,
  "symbol": "BTCUSDT",
  "tradeId": "T982347234",
  "price": "50231.50",
  "quantity": "0.12",
  "side": "BUY",
  "isMakerBuy": false,
  "timestamp": 1712345678123,
  "takerOrderId": 10001,
  "makerOrderId": 20001
}
```

**用途**：
- 最新成交价更新
- 成交流推送（trade频道）
- 成交量统计（ticker）
- K线数据构建（open/high/low/close）
- 标记价格计算（EMA平滑）

#### 3.2.2 订单簿增量（OrderBook Delta）

```json
{
  "eventType": "DEPTH_DELTA",
  "sequence": 912381234,
  "symbol": "BTCUSDT",
  "timestamp": 1712345678124,
  "bids": [
    ["50231.10", "1.20"],
    ["50230.50", "0.00"]
  ],
  "asks": [
    ["50231.60", "0.50"],
    ["50232.00", "1.00"]
  ],
  "isSnapshot": false
}
```

**用途**：
- 深度维护（L2/L3）
- 买一卖一（BBO）计算
- 盘口推送（depth频道）

---

## 四、内部核心模块架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Market Data Service Architecture                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │   Kafka      │  │   Kafka      │  │   Kafka      │              │
│  │   Consumer   │  │   Consumer   │  │   Consumer   │              │
│  │  (Trade)     │  │  (Depth)     │  │  (Index)     │              │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘              │
│         │                 │                 │                       │
│         └────────┬────────┴────────┬────────┘                       │
│                  ▼                 ▼                                 │
│         ┌─────────────────────────────────┐                         │
│         │      Event Dispatcher           │                         │
│         │    （Disruptor RingBuffer）      │                         │
│         └─────────────────────────────────┘                         │
│                  │                                                   │
│    ┌─────────────┼─────────────┬─────────────┐                     │
│    ▼             ▼             ▼             ▼                     │
│ ┌──────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐               │
│ │Trade │   │OrderBook│   │ Kline   │   │ Ticker  │               │
│ │Engine│   │  Cache  │   │ Engine  │   │ Engine  │               │
│ └──┬───┘   └────┬────┘   └────┬────┘   └────┬────┘               │
│    │            │             │             │                      │
│    ▼            ▼             ▼             ▼                      │
│ ┌──────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐               │
│ │Redis │   │ Memory  │   │  Redis  │   │  Redis  │               │
│ │Cache │   │  Cache  │   │   +     │   │  Cache  │               │
│ │      │   │         │   │   DB    │   │         │               │
│ └──────┘   └─────────┘   └─────────┘   └─────────┘               │
│    │            │             │             │                      │
│    └────────────┴─────────────┴─────────────┘                      │
│                 │                                                   │
│                 ▼                                                   │
│        ┌─────────────────┐                                         │
│        │  PubSub Router  │                                         │
│        │  （消息总线）    │                                         │
│        └────────┬────────┘                                         │
│                 │                                                   │
│    ┌────────────┼────────────┐                                     │
│    ▼            ▼            ▼                                     │
│ ┌──────┐   ┌──────┐   ┌──────────┐                               │
│ │Trade │   │Depth │   │  Kline   │                               │
│ │ WS   │   │ WS   │   │   WS     │                               │
│ └──────┘   └──────┘   └──────────┘                               │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.1 OrderBook Cache（内存盘口）

**目标**：本地实时维护每份完整盘口，支持毫秒级查询

**数据结构**（生产级实现）：

```java
// PriceLevel: 侵入式链表，避免GC
public class PriceLevel {
    long price;              // 价格（8字节）
    long quantity;           // 总数量（8字节）
    int orderCount;          // 订单数（4字节）
    PriceLevel prev;         // 前指针（4/8字节）
    PriceLevel next;         // 后指针（4/8字节）
}

// OrderBook: 每个symbol独立
public class OrderBook {
    private final Long2ObjectMap<PriceLevel> bidLevels;  // 买盘 price->level
    private final Long2ObjectMap<PriceLevel> askLevels;  // 卖盘 price->level
    private final PriceLevel bidHead;  // 最高买价（ intrusive linked list ）
    private final PriceLevel askHead;  // 最低卖价
    private long lastSequence;  // 最后更新序号
    private long lastUpdateId;  // 最后快照ID
}
```

**核心特性**：
- 数组 + 侵入式链表：O(1) 查询，CPU Cache友好
- 支持增量更新：applyDelta() 
- 支持快照生成：getSnapshot(depth)
- 支持断线重建：snapshot + delta 对齐
- 多合约分片：symbol -> shard -> OrderBook

### 4.2 Trade Engine（成交处理器）

**职责**：
- 维护最新成交价（lastPrice）
- 成交流聚合（100ms窗口聚合）
- 24h成交量统计
- VWAP计算（按量加权平均价）

**数据流**：
```
Trade Event → 实时推送（WebSocket）
          ↓
     滑动窗口聚合（100ms）
          ↓
     Ticker Engine（统计）
          ↓
     Kline Engine（K线）
```

### 4.3 Kline Engine（K线生成器）

**支持周期**：

| 周期 | 秒数 | 用途 |
|------|------|------|
| 1s | 1 | 高频交易 |
| 1m | 60 | 短线交易 |
| 5m | 300 | 波段分析 |
| 15m | 900 | 技术分析 |
| 1h | 3600 | 日内交易 |
| 4h | 14400 | 趋势分析 |
| 1d | 86400 | 长线持仓 |

**生成逻辑**（标准交易所实现）：

```java
open  = 周期内第一笔成交价
high  = max(周期内所有成交价)
low   = min(周期内所有成交价)
close = 最新成交价
volume = sum(成交量)
turnover = sum(成交额) = sum(price * qty)
takerBuyVolume = sum(主动买入量)
```

**存储策略**：
- 内存：当前周期K线（实时更新）
- Redis：最近1000根K线（快速查询）
- MySQL：历史K线（持久化）
- ClickHouse：海量历史（分析查询）

### 4.4 Ticker Engine（24h统计器）

**计算字段**：

```json
{
  "symbol": "BTCUSDT",
  "priceChange": "-231.20",
  "priceChangePercent": "-0.45",
  "weightedAvgPrice": "50100.50",
  "lastPrice": "50231.50",
  "lastQty": "0.12",
  "openPrice": "50462.70",
  "highPrice": "51000.00",
  "lowPrice": "49800.00",
  "volume": "12321.50",
  "quoteVolume": "617234123.00",
  "openTime": 1712265600000,
  "closeTime": 1712352000000,
  "firstId": 912300000,
  "lastId": 912381233,
  "count": 81233
}
```

**滑动窗口实现**：
- 使用RingBuffer存储最近24h成交
- 每分钟触发窗口滑动
- 增量更新统计值

### 4.5 Mark Price Engine（标记价格引擎）

**计算逻辑**（Binance/OKX标准）：

```
标记价格 = EMA(最新成交价) * 0.5 + 指数价 * 0.5

其中：
EMA(t) = α * Price(t) + (1-α) * EMA(t-1)
α = 2 / (N+1), N=30（30分钟EMA）
```

**用途**：
- 防止市场操纵导致的价格异常
- 作为强平计算的基准价格
- 提供给风控系统

---

## 五、WebSocket 推送系统

### 5.1 架构设计

```
┌──────────────────────────────────────────────────────────────┐
│                    WebSocket Gateway                          │
│                    （无状态，可水平扩展）                      │
├──────────────────────────────────────────────────────────────┤
│                                                               │
│   ┌─────────────┐    ┌─────────────┐    ┌─────────────┐     │
│   │  Connection │    │  Connection │    │  Connection │     │
│   │   Manager   │    │   Manager   │    │   Manager   │     │
│   │  (WS Node 1)│    │  (WS Node 2)│    │  (WS Node N)│     │
│   └──────┬──────┘    └──────┬──────┘    └──────┬──────┘     │
│          │                  │                  │             │
│          └──────────────────┼──────────────────┘             │
│                             │                                │
│                    ┌────────┴────────┐                       │
│                    │   Redis PubSub  │                       │
│                    │  （消息总线）    │                       │
│                    └────────┬────────┘                       │
│                             │                                │
│          ┌──────────────────┼──────────────────┐             │
│          ▼                  ▼                  ▼             │
│   ┌─────────────┐    ┌─────────────┐    ┌─────────────┐     │
│   │Market Data  │    │Market Data  │    │Market Data  │     │
│   │  Service 1  │    │  Service 2  │    │  Service N  │     │
│   └─────────────┘    └─────────────┘    └─────────────┘     │
│                                                               │
└──────────────────────────────────────────────────────────────┘
```

### 5.2 订阅频道设计

| 频道 | 格式 | 描述 |
|------|------|------|
| `trade@{symbol}` | `trade.BTCUSDT` | 实时成交 |
| `depth@{symbol}` | `depth.BTCUSDT` | 深度更新（L2） |
| `depth@{symbol}@100ms` | `depth.BTCUSDT@100ms` | 100ms节流深度 |
| `kline@{symbol}@{interval}` | `kline.BTCUSDT.1m` | K线数据 |
| `ticker@{symbol}` | `ticker.BTCUSDT` | 24h统计 |
| `markPrice@{symbol}` | `markPrice.BTCUSDT` | 标记价格 |
| `indexPrice@{symbol}` | `indexPrice.BTCUSDT` | 指数价格 |
| `bookTicker@{symbol}` | `bookTicker.BTCUSDT` | 最优盘口 |
| `!miniTicker@arr` | - | 所有交易对简化ticker |
| `!ticker@arr` | - | 所有交易对完整ticker |

### 5.3 推送模式：快照 + 增量

**首次订阅**：
```json
// 1. 发送完整快照
{
  "stream": "depth.BTCUSDT",
  "data": {
    "lastUpdateId": 1027024,
    "bids": [["50231.50", "1.234"], ["50231.00", "0.567"], ...],
    "asks": [["50232.00", "0.890"], ["50232.50", "1.123"], ...]
  }
}

// 2. 之后只发送增量
{
  "stream": "depth.BTCUSDT",
  "data": {
    "e": "depthUpdate",
    "E": 1712345678124,
    "s": "BTCUSDT",
    "U": 1027025,
    "u": 1027028,
    "b": [["50231.50", "1.100"], ["50230.50", "0.000"]],
    "a": [["50232.00", "0.950"]]
  }
}
```

### 5.4 深度恢复机制（关键）

```
客户端流程：

1. 获取 REST snapshot
   GET /api/v1/depth?symbol=BTCUSDT&limit=100
   → { "lastUpdateId": 1000, "bids": [...], "asks": [...] }

2. 记录 lastUpdateId = 1000

3. 连接 WebSocket，订阅 depth.BTCUSDT

4. 接收增量消息，检查连续性
   if (msg.U <= lastUpdateId+1 && msg.u >= lastUpdateId+1) {
       // 可以应用增量
       applyDelta(msg);
       lastUpdateId = msg.u;
   } else if (msg.U > lastUpdateId+1) {
       // 丢包了，需要重连并重拉snapshot
       reconnectAndResubscribe();
   }

5. 持续监控 seq 连续性
```

---

## 六、存储设计（Storage Strategy）

### 6.1 存储选型矩阵

| 数据类型 | 存储介质 | 数据结构 | 保留策略 | 查询延迟 |
|---------|---------|---------|---------|---------|
| 当前深度 | 内存 | OrderBook | 实时 | < 1μs |
| 最新成交 | Redis | String/Stream | 24h | < 1ms |
| K线（实时） | 内存 | RingBuffer | 当前周期 | < 1μs |
| K线（近期） | Redis | SortedSet | 1000根 | < 5ms |
| K线（历史） | MySQL | 时序表 | 1年 | < 50ms |
| K线（海量） | ClickHouse | 分区表 | 永久 | < 100ms |
| Ticker统计 | Redis | Hash | 滑动窗口 | < 1ms |
| 深度快照 | Redis | JSON | 最近10个 | < 5ms |

### 6.2 Redis Key 设计

```
# 最新成交
market:trade:{symbol}:last          →  String (JSON)
market:trade:{symbol}:stream        →  Stream (最近1000条)

# K线数据
market:kline:{symbol}:{interval}:current  →  String (当前K线JSON)
market:kline:{symbol}:{interval}:history  →  SortedSet (score=timestamp)

# 24h统计
market:ticker:{symbol}              →  Hash (各字段)
market:ticker:all                   →  Hash (所有symbol摘要)

# 深度快照
market:depth:{symbol}:snapshot      →  String (JSON)
market:depth:{symbol}:lastUpdateId  →  String (long)

# 标记价格
market:markprice:{symbol}           →  String (JSON with EMA)

# 指数价格  
market:indexprice:{symbol}          →  String (JSON)

# WebSocket订阅管理
ws:session:{sessionId}:subscriptions  →  Set (频道列表)
ws:channel:{channel}:subscribers      →  Set (sessionId列表)
```

### 6.3 MySQL 表结构

```sql
-- K线历史表（按月分区）
CREATE TABLE t_kline_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    interval_val VARCHAR(10) NOT NULL COMMENT '周期(1m,5m,1h,1d)',
    open_time BIGINT NOT NULL COMMENT '开盘时间戳',
    close_time BIGINT NOT NULL COMMENT '收盘时间戳',
    open_price BIGINT NOT NULL COMMENT '开盘价(8位小数)',
    high_price BIGINT NOT NULL COMMENT '最高价(8位小数)',
    low_price BIGINT NOT NULL COMMENT '最低价(8位小数)',
    close_price BIGINT NOT NULL COMMENT '收盘价(8位小数)',
    volume BIGINT NOT NULL COMMENT '成交量',
    quote_volume BIGINT NOT NULL COMMENT '成交额',
    trade_count INT NOT NULL COMMENT '成交笔数',
    taker_buy_volume BIGINT NOT NULL COMMENT '主动买入量',
    taker_buy_quote_volume BIGINT NOT NULL COMMENT '主动买入额',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol_interval_time (symbol, interval_val, open_time),
    KEY idx_symbol_interval (symbol, interval_val, open_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
PARTITION BY RANGE (open_time DIV 2592000000) (  -- 按月分区
    PARTITION p202401 VALUES LESS THAN (1704067200),
    PARTITION p202402 VALUES LESS THAN (1706745600),
    ...
);

-- 成交历史表（按日分区，用于审计和对账）
CREATE TABLE t_trade_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_id VARCHAR(50) NOT NULL COMMENT '成交ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    sequence BIGINT NOT NULL COMMENT '撮合序号',
    price BIGINT NOT NULL COMMENT '成交价',
    quantity BIGINT NOT NULL COMMENT '成交量',
    side TINYINT NOT NULL COMMENT '方向(1买2卖)',
    trade_time BIGINT NOT NULL COMMENT '成交时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_trade_id (trade_id),
    KEY idx_symbol_time (symbol, trade_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
PARTITION BY RANGE (trade_time DIV 86400000) (  -- 按日分区
    ...
);
```

---

## 七、高可用设计（High Availability）

### 7.1 多层级HA方案

```
┌─────────────────────────────────────────────────────────────┐
│                      客户端 (Client)                         │
│                    支持自动重连+重订阅                        │
└─────────────────────────────────────────────────────────────┘
                              │
                    ┌─────────┴─────────┐
                    ▼                   ▼
┌──────────────────────────┐  ┌──────────────────────────┐
│    CDN / Load Balancer   │  │    CDN / Load Balancer   │
│      (Anycast IP)        │  │      (Anycast IP)        │
└──────────┬───────────────┘  └──────────┬───────────────┘
           │                             │
           ▼                             ▼
┌──────────────────────────┐  ┌──────────────────────────┐
│    WebSocket Gateway     │  │    WebSocket Gateway     │
│     (AZ1 - Node 1~10)    │  │     (AZ2 - Node 1~10)    │
│    无状态，可水平扩展      │  │    无状态，可水平扩展      │
└──────────┬───────────────┘  └──────────┬───────────────┘
           │                             │
           └──────────┬──────────────────┘
                      ▼
┌─────────────────────────────────────────────────────────────┐
│              Redis Cluster (PubSub Message Bus)              │
│                     跨可用区部署                              │
└─────────────────────────────────────────────────────────────┘
                              │
           ┌──────────────────┼──────────────────┐
           ▼                  ▼                  ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│ Market Service  │  │ Market Service  │  │ Market Service  │
│  (Shard 1)      │  │  (Shard 2)      │  │  (Shard 3)      │
│  BTC, ETH       │  │  SOL, AVAX      │  │  Others         │
└────────┬────────┘  └────────┬────────┘  └────────┬────────┘
         │                    │                    │
         └────────────────────┼────────────────────┘
                              ▼
┌─────────────────────────────────────────────────────────────┐
│            Kafka Cluster（跨可用区，3副本）                   │
│        match-event-topic / orderbook-delta-*                │
└─────────────────────────────────────────────────────────────┘
```

### 7.2 故障场景处理

| 故障场景 | 检测时间 | 恢复策略 | 数据丢失 |
|---------|---------|---------|---------|
| WS Gateway 宕机 | 5s（健康检查） | 客户端重连到其他节点 | 无 |
| Market Service 宕机 | 3s | 其他节点接管shard | 内存数据需重建 |
| Redis 宕机 | 1s | 切换到从节点 | 无（主从同步） |
| Kafka 分区不可用 | 10s | 切换到ISR副本 | 无（多副本） |
| 网络分区 | 依赖配置 | 最小可用原则 | 可能短暂不一致 |

### 7.3 数据一致性保障

```
撮合引擎（单线程，seq严格递增）
         │
         ▼ publish
Kafka（单分区，保证顺序）
         │
         ▼ consume
Market Service（单线程消费每个symbol）
         │
         ▼ apply
OrderBook Cache（内存更新）
         │
         ▼ publish
Redis PubSub（广播到所有WS Gateway）
```

**一致性级别**：
- 成交事件：严格顺序一致（按seq）
- 深度更新：顺序一致（按lastUpdateId）
- K线数据：时间一致（按openTime）
- Ticker统计：最终一致（滑动窗口）

---

## 八、性能优化策略

### 8.1 延迟优化

| 优化点 | 策略 | 效果 |
|-------|------|------|
| 序列化 | Protobuf（替代JSON） | -50% CPU，-60% 带宽 |
| 内存分配 | 对象池（PriceLevel, Trade） | -90% GC压力 |
| 线程模型 | Disruptor + 单线程处理 | < 1ms 处理延迟 |
| 网络传输 | WebSocket 压缩 + 批量推送 | -70% 带宽 |
| 热点数据 | Caffeine 本地缓存 | < 1μs 查询 |

### 8.2 吞吐优化

| 优化点 | 策略 | 效果 |
|-------|------|------|
| 消息聚合 | 100ms窗口聚合小成交 | +300% 吞吐 |
| 批量写入 | Kafka batch + MySQL batch insert | +500% 吞吐 |
| 连接管理 | WebSocket 多路复用 | 单节点10万连接 |
| 分片策略 | symbol一致性哈希 | 水平扩展 |

### 8.3 内存优化

```
OrderBook 内存占用估算（每个symbol）：
- 100档深度 × 2（买卖）× 40字节 ≈ 8KB
- 1000个symbol ≈ 8MB
- 缓存100个快照 ≈ 800MB

Trade 内存占用（24h滑动窗口）：
- 每秒1000笔 × 86400秒 × 100字节 ≈ 8.6GB
- 实际：RingBuffer固定大小，老数据淘汰
```

---

## 九、监控与告警

### 9.1 关键指标（SLA监控）

| 指标 | 类型 | 阈值 | 告警级别 |
|------|------|------|---------|
| 推送延迟 P99 | Gauge | > 20ms | Critical |
| Kafka消费延迟 | Gauge | > 1000ms | Warning |
| WS连接数 | Gauge | > 80%容量 | Warning |
| 消息丢失率 | Counter | > 0.1% | Critical |
| 序列号断层 | Counter | > 0 | Critical |
| GC暂停时间 | Timer | > 100ms | Warning |
| CPU使用率 | Gauge | > 80% | Warning |
| 内存使用率 | Gauge | > 85% | Warning |

### 9.2 监控Dashboard

```
┌─────────────────────────────────────────────────────────────┐
│                  Market Data 监控大盘                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  [延迟热力图]     [Kafka Lag]      [WS连接数趋势]           │
│                                                              │
│  [成交TPS]        [深度更新频率]   [消息队列深度]            │
│                                                              │
│  [Symbol健康状态]                                             │
│  BTCUSDT ●  ETHUSDT ●  SOLUSDT ○  ...                       │
│  （绿色健康/黄色延迟/红色断开）                                │
│                                                              │
│  [Top 10 延迟Symbol]    [最近告警事件]                       │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 十、API 接口规范

### 10.1 REST API

```
# 获取深度
GET /api/v1/depth?symbol=BTCUSDT&limit=100

Response:
{
  "lastUpdateId": 1027024,
  "messageOutputTime": 1712345678123,
  "bids": [["50231.50", "1.234"], ...],
  "asks": [["50232.00", "0.890"], ...]
}

# 获取K线
GET /api/v1/klines?symbol=BTCUSDT&interval=1m&limit=500

Response:
[
  [1712345640000, "50200.00", "50250.00", "50190.00", "50231.50", "100.5", 1712345699999, "5041234.5", 123, "60.2", "3021234.0"],
  ...
]

# 获取24h统计
GET /api/v1/ticker/24hr?symbol=BTCUSDT
GET /api/v1/ticker/24hr          # 所有symbol

# 获取最新成交
GET /api/v1/trades?symbol=BTCUSDT&limit=100

# 获取标记价格
GET /api/v1/premiumIndex?symbol=BTCUSDT
```

### 10.2 WebSocket 协议

```
# 连接
wss://api.exchange.com/ws/market

# 订阅
{
  "method": "SUBSCRIBE",
  "params": ["trade.BTCUSDT", "depth.BTCUSDT@100ms"],
  "id": 1
}

# 取消订阅
{
  "method": "UNSUBSCRIBE",
  "params": ["trade.BTCUSDT"],
  "id": 2
}

# 心跳
Client → {"ping": 1712345678123}
Server → {"pong": 1712345678123}

# 服务端推送（成交）
{
  "stream": "trade.BTCUSDT",
  "data": {
    "e": "trade",
    "E": 1712345678123,
    "s": "BTCUSDT",
    "t": 123456789,
    "p": "50231.50",
    "q": "0.12",
    "b": 10001,
    "a": 20001,
    "T": 1712345678120,
    "m": false
  }
}
```

---

## 十一、数据流总结

```
┌──────────────────────────────────────────────────────────────────┐
│                         完整数据流向                              │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────┐                                                │
│  │ Match Engine │ ◄── 撮合核心（唯一事实源）                      │
│  └──────┬───────┘                                                │
│         │ publish                                                │
│         ▼                                                        │
│  ┌──────────────┐                                                │
│  │    Kafka     │ ◄── 消息总线（顺序保证）                        │
│  │ match-event  │                                                │
│  └──────┬───────┘                                                │
│         │ consume                                                │
│         ▼                                                        │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │              Market Data Service                             │ │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐       │ │
│  │  │  Trade   │ │ OrderBook│ │  Kline   │ │  Ticker  │       │ │
│  │  │  Engine  │ │  Cache   │ │  Engine  │ │  Engine  │       │ │
│  │  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘       │ │
│  │       └─────────────┴─────────────┴─────────────┘            │ │
│  │                     │                                         │ │
│  │                     ▼ publish                                 │ │
│  │              ┌──────────────┐                                 │ │
│  │              │  PubSub Bus  │ ◄── Redis Message Bus           │ │
│  │              │   (Redis)    │                                 │ │
│  │              └──────┬───────┘                                 │ │
│  └─────────────────────┼─────────────────────────────────────────┘ │
│                        │ subscribe                                │
│         ┌──────────────┼──────────────┐                          │
│         ▼              ▼              ▼                          │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐                 │
│  │  WS Node 1  │ │  WS Node 2  │ │  WS Node N  │ ◄── 无状态网关   │
│  └──────┬──────┘ └──────┬──────┘ └──────┬──────┘                 │
│         │               │               │                         │
│         └───────────────┴───────────────┘                         │
│                         │                                         │
│                         ▼ push                                    │
│                  ┌─────────────┐                                  │
│                  │   Client    │ ◄── 最终用户                     │
│                  └─────────────┘                                  │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

---

## 十二、核心设计思想

> **行情是市场事实的实时投影，不是账户计算结果。**

### 12.1 为什么与账本解耦？

1. **关注点分离**：行情关注市场状态，账本关注资金状态
2. **性能独立**：行情可水平扩展，不受清结算影响
3. **故障隔离**：账本故障不影响行情推送
4. **数据一致性简化**：行情只需保证顺序一致，无需事务

### 12.2 为什么用推送而非轮询？

1. **延迟**：推送 < 10ms，轮询 ≥ 100ms
2. **带宽**：推送只发变化，轮询重复传输
3. **实时性**：行情变化立即送达
4. **连接数**：WebSocket长连接更高效

### 12.3 为什么用 Kafka 做输入？

1. **顺序保证**：单分区保证撮合事件顺序
2. **可重放**：支持故障恢复和重放
3. **背压处理**：消费速度跟不上时自动缓冲
4. **多消费组**：行情、风控、审计独立消费

---

## 十三、交付 checklist

- [ ] OrderBook Cache（内存盘口）实现
- [ ] Trade Engine（成交处理）实现
- [ ] Kline Engine（K线生成）实现
- [ ] Ticker Engine（24h统计）实现
- [ ] Mark Price Engine（标记价格）实现
- [ ] Kafka Consumer（事件消费）实现
- [ ] WebSocket Gateway（推送网关）实现
- [ ] REST API（查询接口）实现
- [ ] Redis 存储层实现
- [ ] MySQL/ClickHouse 持久化实现
- [ ] 监控告警接入
- [ ] 压力测试报告
- [ ] 灾备恢复演练

---

*本文档面向产品经理、技术负责人和核心开发人员。实际实现请参考技术设计文档和代码。*
