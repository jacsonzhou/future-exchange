# 交易所级核心架构 V2（硬风控 + Ledger + 高性能撮合 + 分层风控）

> 目标：对标一线交易所（Binance/Coinbase/OKX 等）风格，提供 Cursor 可理解的整体架构、服务职责、调用链路、核心时序、以及代码级组件拆分。

---

## 1. 总体分层架构（High-Level）

```
                ┌────────────────────────────┐
                │        Client / API        │
                └─────────────┬──────────────┘
                              │
                        ┌─────▼─────┐
                        │ API GW     │  认证/限流/灰度
                        └─────┬─────┘
                              │
         ┌────────────────────▼────────────────────┐
         │            Core Trading Zone             │
         │                                           │
         │  ┌──────────┐   ┌──────────┐            │
         │  │ OrderSvc │   │ Account  │            │
         │  │ (OMS)    │   │ Service  │            │
         │  └────┬─────┘   └────┬─────┘            │
         │       │              │                  │
         │  ┌────▼────┐   ┌────▼────┐            │
         │  │ Risk    │   │ Ledger  │            │
         │  │ Engine  │   │ Service │            │
         │  └────┬────┘   └────┬────┘            │
         │       │              │                  │
         │  ┌────▼──────────────────────────┐     │
         │  │   Matching Engine (Disruptor) │     │
         │  └────┬──────────────────────────┘     │
         │       │                                  │
         │  ┌────▼────┐   ┌────────────┐           │
         │  │ Deal /  │   │ Position   │           │
         │  │ Trade   │   │ Service    │           │
         │  └────┬────┘   └────┬───────┘           │
         │       │              │                   │
         │  ┌────▼──────────────────────────┐     │
         │  │        Market Data             │     │
         │  │ (OrderBook / Ticker / Depth)  │     │
         │  └───────────────────────────────┘     │
         │                                           │
         └───────────────────────────────────────────┘

                    ┌──────────────────────┐
                    │   Kafka / EventBus   │
                    └──────────────────────┘

                    ┌──────────────────────┐
                    │   MySQL / RocksDB    │
                    │   (Ledger/Order)    │
                    └──────────────────────┘
```

---

## 2. 核心服务职责拆解（Service Responsibility）

### 2.1 API Gateway
- 认证（JWT / API Key）
- 限流（用户 / IP / 接口级）
- 灰度 / 熔断
- 请求路由

---

### 2.2 Order Service (OMS)
**职责：订单生命周期管理，不做撮合，不改资金**

- 接收下单/撤单
- 订单合法性校验（价格、数量、状态）
- 订单状态机
- 写 Order DB
- 向 Risk Engine 发起预风控
- 向 Matching Engine 投递 OrderEvent

**不做：**
- 不扣钱
- 不撮合

---

### 2.3 Account Service
**职责：账户资产查询 + 可用余额缓存**

- 维护 Available / Frozen
- 提供给 Risk 查询
- 非最终账本（最终以 Ledger 为准）

---

### 2.4 Risk Engine（分层风控核心）

#### 2.4.1 Pre-Trade Risk（下单前）
- 余额是否足够
- 杠杆/保证金校验
- 最大下单数量
- 价格偏离保护

#### 2.4.2 In-Trade Risk（撮合中）
- 强平触发
- 风险率实时计算
- ADL（自动减仓）

#### 2.4.3 Post-Trade Risk（成交后）
- 风险率更新
- 触发强平单

> Risk 是状态型内存引擎（Stateful），通过订阅 Trade/Position Event 实时维护内存态。

---

### 2.5 Ledger Service（核心账本）

**这是金融系统的核心真相源（Source of Truth）**

- 双录分录（Double Entry）
- 资金冻结/解冻
- 成交结算
- 手续费
- 对账

```
LedgerEntry:
  debit_account
  credit_account
  asset
  amount
  biz_type
  ref_id
```

特点：
- 强一致
- 严格顺序
- 幂等

---

### 2.6 Matching Engine（高性能撮合）

**设计目标：亚毫秒级撮合 + 内存态 + WAL**

#### 推荐：Disruptor + 内存 OrderBook

- 单线程撮合（每 symbol 一线程）
- RingBuffer 事件驱动
- 内存 OrderBook（PriceLevel + LinkedList）
- WAL 顺序日志

```
OrderEvent -> RingBuffer -> MatchingLoop -> TradeEvent
```

#### 为什么不用 TreeMap？

| 方案 | 优点 | 致命问题 |
|------|------|----------|
| TreeMap | 实现简单 | GC压力大、锁竞争、指针跳转、cache miss |
| Disruptor + 自定义结构 | 极致性能 | 实现复杂 |

TreeMap 问题：
- 红黑树 = 指针结构 → CPU cache 不友好
- 并发需要锁
- GC 压力

一线交易所：
- 基本不用 Java TreeMap
- 使用数组 + intrusive list + off-heap

结论：
> TreeMap 可用于低频撮合，不适合交易所级核心撮合。

---

### 2.7 Deal / Trade Service

- 接收 TradeEvent
- 生成 Trade Record
- 发送给 Ledger
- 发布成交事件

---

### 2.8 Position Service

- 维护持仓
- 计算浮盈浮亏
- 风险率
- 向 Risk 推送 PositionEvent

---

### 2.9 Market Data Service

- 维护 L2 OrderBook
- 生成 Depth / Ticker
- WebSocket 推送

---

## 3. 核心交易链路（主链路）

```
Client
  -> API GW
    -> OMS
      -> Risk (Pre)
      -> Matching Engine
        -> TradeEvent
          -> Deal Service
            -> Ledger
              -> Account Update
            -> Position Service
            -> Risk (Post)
          -> Market Data
```

---

## 4. 核心时序图（ASCII）

```
Client      OMS        Risk        Matching       Deal        Ledger
  |           |           |             |            |            |
  | PlaceOrd  |           |             |            |            |
  |---------->|           |             |            |            |
  |           | PreCheck  |             |            |            |
  |           |---------> |             |            |            |
  |           |           | OK          |            |            |
  |           |<--------- |             |            |            |
  |           | SendEvent |             |            |            |
  |           |-----------------------> |            |            |
  |           |           |   Match     |            |            |
  |           |           |             | TradeEvent |            |
  |           |           |             |----------> |            |
  |           |           |             |            | Settle     |
  |           |           |             |            |----------> |
  |           |           |             |            | OK         |
```

---

## 5. 代码级组件图（Matching Engine 示例）

```
MatchingEngine
 ├── RingBuffer<OrderEvent>
 ├── MatchingLoop
 ├── OrderBookManager
 │    └── OrderBook (per symbol)
 │          ├── BidBook
 │          ├── AskBook
 │          ├── PriceLevel[]
 │          └── IntrusiveOrderList
 ├── TradePublisher
 └── WALAppender
```

---

## 6. 分层风控设计（重点）

| 层级 | 位置 | 目的 |
|------|------|------|
| Pre-Trade | OMS前 | 防止非法单 |
| In-Trade | 撮合中 | 防止穿仓 |
| Post-Trade | 成交后 | 风险率维护 |
| Liquidation | 独立引擎 | 自动强平 |

---

## 7. Cursor 友好调用链总结

```
API -> OMS -> Risk -> Matching -> Deal -> Ledger -> Position -> Risk -> MarketData
```

---

## 8. 关键设计原则（一线交易所风格）

1. Ledger 是唯一资金真相
2. Matching 内存态 + WAL
3. Risk 是状态型内存引擎
4. 单 symbol 单线程撮合
5. Event Driven
6. 幂等 + 可重放

---

如果你需要：
- Kafka Schema
- Protobuf 定义
- Java 接口骨架
- Disruptor 示例代码

我可以继续帮你拆到代码级。


# Service-Level Detailed Requirements (For Cursor)

## 1. API Gateway
### Responsibilities
- Unified external entry
- Auth (JWT/OAuth)
- Rate limit / IP throttle
- Symbol-level trading switch
- Circuit breaker enforcement

### APIs
- POST /api/order/create
- POST /api/order/cancel
- GET /api/order/query

### Sync Calls
- OMS.createOrder()
- HardRiskGate.preCheck()

### Non-Functional
- P99 < 10ms
- Stateless
- Redis for circuit-breaker + limits

---

## 2. OMS (Order Management System)
### Responsibilities
- Order lifecycle state machine
- Idempotency
- Order persistence
- Order event publishing

### Core States
NEW -> RISK_PASSED -> SENT_TO_MATCH -> PARTIAL_FILLED -> FILLED -> CANCELED

### APIs
- createOrder()
- cancelOrder()
- queryOrder()

### Kafka
- order-wal-topic (source of truth)
- order-event-topic

### DB
- order_info
- order_event

---

## 3. Hard Risk Gate (Sync Hard Risk)
### Responsibilities
- Pre-trade risk validation
- Margin sufficiency
- Position limit
- User restriction
- Symbol halt

### Sync Checks
- Available margin
- Max leverage
- Max open orders
- Frozen flags

### Latency
- Must be < 3ms

---

## 4. Match Engine Core
### Responsibilities
- Deterministic matching
- OrderBook maintenance
- Trade generation

### Architecture
- Disruptor RingBuffer
- Single writer per symbol
- In-memory OrderBook (arrays + price levels)

### Inputs
- order-wal-topic

### Outputs
- trade-topic
- order-status-topic

### HA
- WAL + Hot Standby
- Snapshot + replay

---

## 5. Clearing Core (Ledger)
### Responsibilities
- Double-entry ledger
- Balance updates
- Fee accounting
- Insurance fund

### Ledger Model
- debit/credit
- immutable journal

### Tables
- ledger_journal
- account_balance
- fee_record

### Idempotency
- trade_id + side

---

## 6. Position Snapshot Service
### Responsibilities
- Position state
- PnL calculation
- Margin ratio
- Liquidation price

### Inputs
- trade-topic
- mark-price-topic

### Outputs
- risk-event-topic

### Storage
- position_snapshot
- Redis cache

---

## 7. Account Snapshot Service
### Responsibilities
- Available / frozen balance
- Margin accounting
- Withdrawable balance

### Inputs
- ledger events

### Storage
- account_snapshot
- Redis cache

---

## 8. Soft Risk Engine (Async)
### Responsibilities
- User-level risk
- Portfolio risk
- Circuit breaker trigger
- ADL trigger

### Inputs
- risk-event-topic
- account-change-topic
- market-stats-topic

### Outputs
- liquidation-trigger-topic
- circuit-breaker-topic
- user-restriction-topic

### Rules
- Margin ratio thresholds
- Exposure limits
- Volatility halts

---

# Cross-Service Contracts
## TradeMessage
- trade_id
- symbol
- price
- qty
- buy_order_id
- sell_order_id
- ts

## RiskEvent
- user_id
- symbol
- margin_ratio
- liquidation_price
- ts

## LedgerEntry
- entry_id
- account_id
- debit
- credit
- ref_trade_id

---

# Coding Principles for Cursor
- Deterministic replay
- Idempotent consumers
- Single writer per symbol
- Event sourcing first
- Snapshot is acceleration only


# 9. Java Interfaces + DTO + Protobuf (Service Contracts)

## 9.1 API Gateway
### Java Interface
```java
public interface GatewayApi {
  OrderResponse createOrder(CreateOrderRequest req);
  CancelOrderResponse cancelOrder(CancelOrderRequest req);
  OrderQueryResponse queryOrder(OrderQueryRequest req);
}
```

### DTO
```java
@Data
public class CreateOrderRequest {
  long userId;
  String symbol;
  Side side; // BUY/SELL
  OrderType type; // LIMIT/MARKET
  BigDecimal price;
  BigDecimal quantity;
  int leverage;
}
```

---

## 9.2 OMS (Order Management System)
### Java Interface
```java
public interface OmsService {
  OrderResult createOrder(CreateOrderCmd cmd);
  CancelResult cancelOrder(CancelOrderCmd cmd);
  OrderView queryOrder(long orderId);
}
```

### DTO
```java
@Data
public class CreateOrderCmd {
  long userId;
  String symbol;
  Side side;
  OrderType type;
  BigDecimal price;
  BigDecimal qty;
  int leverage;
  String clientOrderId;
}
```

### Protobuf (order.proto)
```proto
message OrderEvent {
  string order_id = 1;
  int64 user_id = 2;
  string symbol = 3;
  string side = 4;
  string type = 5;
  string price = 6;
  string qty = 7;
  string status = 8;
  int64 ts = 9;
}
```

---

## 9.3 Hard Risk Gate
### Java Interface
```java
public interface HardRiskService {
  RiskResult preCheck(RiskCheckCmd cmd);
}
```

```java
@Data
public class RiskCheckCmd {
  long userId;
  String symbol;
  BigDecimal notional;
  int leverage;
}
```

---

## 9.4 Match Engine Core
### Input Command
```java
@Data
public class MatchOrderCmd {
  long orderId;
  long userId;
  String symbol;
  Side side;
  BigDecimal price;
  BigDecimal qty;
}
```

### Output Trade DTO
```java
@Data
public class Trade {
  String tradeId;
  String symbol;
  long buyOrderId;
  long sellOrderId;
  BigDecimal price;
  BigDecimal qty;
  long ts;
}
```

### Protobuf (trade.proto)
```proto
message TradeMessage {
  string trade_id = 1;
  string symbol = 2;
  string price = 3;
  string qty = 4;
  string buy_order_id = 5;
  string sell_order_id = 6;
  int64 ts = 7;
}
```

---

## 9.5 Clearing Core (Ledger)
### Java Interface
```java
public interface LedgerService {
  void applyTrade(Trade trade);
}
```

### Ledger Entry
```java
@Data
public class LedgerEntry {
  String entryId;
  long accountId;
  BigDecimal debit;
  BigDecimal credit;
  String refTradeId;
  long ts;
}
```

### Protobuf (ledger.proto)
```proto
message LedgerEntryMsg {
  string entry_id = 1;
  int64 account_id = 2;
  string debit = 3;
  string credit = 4;
  string ref_trade_id = 5;
  int64 ts = 6;
}
```

---

## 9.6 Position Snapshot Service
```java
public interface PositionService {
  void onTrade(Trade trade);
  void onMarkPrice(MarkPrice markPrice);
  PositionView queryPosition(long userId, String symbol);
}
```

```java
@Data
public class PositionSnapshot {
  long userId;
  String symbol;
  BigDecimal size;
  BigDecimal entryPrice;
  BigDecimal unrealizedPnl;
  BigDecimal marginRatio;
  BigDecimal liquidationPrice;
}
```

---

## 9.7 Account Snapshot Service
```java
public interface AccountSnapshotService {
  void onLedgerEntry(LedgerEntry entry);
  AccountView queryAccount(long userId);
}
```

```java
@Data
public class AccountSnapshot {
  long userId;
  BigDecimal available;
  BigDecimal frozen;
  BigDecimal equity;
}
```

---

## 9.8 Soft Risk Engine
```java
public interface SoftRiskService {
  void onRiskEvent(RiskEvent event);
}
```

```java
@Data
public class RiskEvent {
  long userId;
  String symbol;
  BigDecimal marginRatio;
  BigDecimal liquidationPrice;
  long ts;
}
```

---

# 10. Disruptor Matching Engine Skeleton

## 10.1 RingBuffer Event
```java
public class MatchEvent {
  public MatchOrderCmd cmd;
}
```

## 10.2 Event Factory
```java
public class MatchEventFactory implements EventFactory<MatchEvent> {
  public MatchEvent newInstance() { return new MatchEvent(); }
}
```

## 10.3 Event Handler
```java
public class MatchEventHandler implements EventHandler<MatchEvent> {
  private final OrderBook orderBook;

  public void onEvent(MatchEvent event, long seq, boolean endOfBatch) {
    orderBook.process(event.cmd);
  }
}
```

## 10.4 Disruptor Bootstrap
```java
Disruptor<MatchEvent> disruptor = new Disruptor<>(
    new MatchEventFactory(),
    1024 * 1024,
    Executors.defaultThreadFactory(),
    ProducerType.MULTI,
    new BusySpinWaitStrategy()
);

disruptor.handleEventsWith(new MatchEventHandler(orderBook));
disruptor.start();
```

---

# 11. OrderBook In-Memory Structure (No TreeMap)

## Design Goals
- Cache-friendly
- O(1) price level access
- Single-writer
- No object churn

## Core Structures

```java
public class OrderBook {
  // price index -> level
  private final Long2ObjectOpenHashMap<PriceLevel> bids;
  private final Long2ObjectOpenHashMap<PriceLevel> asks;

  // best price tracking
  private long bestBid;
  private long bestAsk;

  public void process(MatchOrderCmd cmd) {
    if (cmd.side == Side.BUY) matchBuy(cmd);
    else matchSell(cmd);
  }
}
```

## PriceLevel (Linked Orders)
```java
public class PriceLevel {
  long price;
  OrderNode head;
  OrderNode tail;
}
```

## OrderNode (Intrusive List)
```java
public class OrderNode {
  long orderId;
  long userId;
  long qty;
  OrderNode prev;
  OrderNode next;
}
```

## Why Not TreeMap
- Pointer chasing
- LogN cost
- Poor cache locality
- GC pressure

## Why This Layout
- Flat maps + arrays
- Sequential memory access
- Single-threaded = no locks
- Deterministic replay

---

# 12. Matching Flow (Pseudo)
```java
while (buy.qty > 0 && bestAsk <= buy.price) {
  PriceLevel level = asks.get(bestAsk);
  OrderNode maker = level.head;
  executeTrade(buy, maker);
}
```

---

# Cursor Coding Notes
- Generate DTOs from proto
- Keep MatchEngine single-thread per symbol
- Never allocate in hot path
- Snapshot outside disruptor thread


# 13. Ultra-High Performance OrderBook (Array + Price Index + Bitmap)

## Goals
- Zero hash lookup
- Predictable memory
- Fast best-price scan

## Structure

```java
public class UltraOrderBook {
  // price index offset from reference price
  private final PriceLevel[] bidLevels;
  private final PriceLevel[] askLevels;

  // bitmap to mark non-empty levels
  private final long[] bidBitmap;
  private final long[] askBitmap;

  private int bestBidIdx;
  private int bestAskIdx;
}
```

### Bitmap Scan
```java
int nextBid = highestSetBit(bidBitmap);
int nextAsk = lowestSetBit(askBitmap);
```

### Why
- No map
- No Tree
- CPU branch-predictable
- Used in HFT-grade engines

---

# 14. Ledger Double-Entry Accounting (Real Exchange Grade)

## Core Principle
Every trade generates >= 4 ledger entries.

## Example: Open Long
User A opens long 1 BTC @ 50,000, 10x

Notional = 50,000
Margin = 5,000
Fee = 10

### Entries

| Account | Debit | Credit | Note |
|--------|-------|--------|------|
| UserA:Available | 5,010 | 0 | Freeze margin + fee |
| UserA:FrozenMargin | 0 | 5,000 | Margin |
| UserA:Fee | 0 | 10 | Fee |
| Exchange:FeeIncome | 0 | 10 | Exchange income |

---

## Example: Close Long with Profit
Close @ 51,000, Profit = 1,000

| Account | Debit | Credit | Note |
|--------|-------|--------|------|
| UserA:FrozenMargin | 5,000 | 0 | Release |
| UserA:Available | 0 | 6,000 | Margin + PnL |
| Exchange:Insurance | 0 | 0 | No usage |

---

## Ledger Table (Journal)

```sql
ledger_journal(
  entry_id PK,
  account_id,
  currency,
  debit,
  credit,
  ref_type,
  ref_id,
  ts
)
```

## Guarantees
- Sum(debit) == Sum(credit)
- Immutable
- Auditable

---

# 15. Kafka Offset + Idempotency + Failover Design

## Trade Consumer Idempotency

Key = (trade_id, side)

```sql
unique(trade_id, account_id)
```

Consumer Logic:

1. Begin Tx
2. Insert ledger_journal
3. If duplicate -> ignore
4. Commit Tx
5. Commit Kafka Offset

---

## Exactly-Once Effect (Practical)

Pattern: DB Transaction + Offset Commit

```text
process()
  begin db tx
  apply ledger
  commit db tx
  commit kafka offset
```

At-least-once Kafka
Exactly-once effect in DB

---

# 16. Matching Failover Deep Dive

## WAL + Standby Replay

```text
OMS -> order-wal-topic
          |
     Matching A (Primary)
     Matching B (Standby)
```

Both consume same WAL.

Standby:
- Builds OrderBook
- Does NOT publish trades

---

## Leader Switch

1. Primary dies
2. etcd elects new leader
3. Standby promoted
4. New primary starts publishing

Critical: trade_id sequence continues from WAL offset

---

# 17. Deterministic Replay Rules

- Single writer per symbol
- No wall-clock logic
- All randomness removed
- Same input => same trades

This guarantees:
- Snapshot correctness
- Standby correctness
- Audit replay

---

# 18. GC + Memory Management (Real World)

## Hot Path Rules
- No new objects
- Pre-allocate OrderNode
- Object pools

## JVM Flags (example)

-XX:+UseZGC
-XX:MaxGCPauseMillis=2
-XX:+AlwaysPreTouch

---

# 19. Latency Budget (CTO Level)

| Stage | Target |
|-------|--------|
| Gateway | < 1 ms |
| Hard Risk | < 2 ms |
| OMS | < 2 ms |
| Kafka Append | < 1 ms |
| Matching | < 10 us |
| Trade Fanout | < 2 ms |

---

# 20. Audit + Regulator Ready Design

## Full Replay
- WAL retained 7-30 days
- Snapshot daily

## Audit Mode
- Replay WAL to shadow engine
- Compare trades

## Benefits
- Regulator audits
- Dispute resolution
- Internal fraud detection

---

# CTO Design Principles

1. Ledger is source of truth for money
2. WAL is source of truth for orders
3. Matching is deterministic pure function
4. Snapshot is cache
5. Standby is HA
6. No consensus in hot path
7. Everything replayable
8. Auditability over cleverness


# 21. Cross-Symbol Risk Netting (组合保证金 / 跨币种净额)

## Goal
Reduce margin by recognizing portfolio hedges.

## Architecture

```
Position Service
   -> Portfolio Aggregator
        -> Risk Graph Engine
             -> Net Exposure
```

## Core Model

```java
class PortfolioExposure {
  Map<String, BigDecimal> delta;   // directional
  Map<String, BigDecimal> vega;    // volatility
  Map<String, BigDecimal> gamma;
}
```

## Example

BTC Long + ETH Short (correlated)

Net Delta = BTC_delta - corr * ETH_delta

Result:
- Lower margin
- Better capital efficiency

## Why Top Tier
- Requires correlation matrix
- Requires portfolio aggregation
- Complex but huge advantage

---

# 22. Portfolio Margin (SPAN / CME Style)

## Scenario-Based Risk

Define stress scenarios:

| Scenario | BTC | ETH |
|----------|-----|-----|
| Up 5% | +5% | +4% |
| Down 5% | -5% | -4% |
| Vol Spike | +10% vol | +12% vol |


## Algorithm

For each scenario:
  PnL = sum(position_i * price_change_i)

Margin = worst_case_loss

## Why
- More accurate
- Institutional grade
- Required for large accounts

---

# 23. Funding Rate Engine (顶级实现)

## Components

```
Index Price Service
Premium Index Calculator
Funding Rate Engine
Funding Settlement Ledger
```

## Funding Rate Formula

```
FundingRate = clamp(
  (PremiumIndex + InterestRate),
  -MaxRate,
  +MaxRate
)
```

## Settlement Flow

Every 8 hours:

For each position:
  funding = positionSize * markPrice * fundingRate

Ledger Entries:

Long pays Short or vice versa

---

# 24. Stress Engine (Systemic Risk)

## Purpose
Simulate market crash.

## Flow

```
Snapshot Positions
Apply Shock Curves
Simulate Liquidations
Estimate Insurance Fund Drain
```

## Outputs
- Max drawdown
- Cascading liquidation depth
- System halt thresholds

---

# 25. ADL (Auto-Deleveraging) Advanced

## Ranking Formula

Score = leverage * pnl / margin

Highest score ADL first.

## Engine

```
ADL Ranker
Position Selector
Forced Reduction Orders
```

## Properties
- Deterministic
- Auditable
- Fair

---

# 26. Market Protection Systems

## Volatility Interruptions

- Price band
- Trading halt
- Auction mode

## Auction Mode

Collect orders for N seconds
Re-open with clearing price

---

# 27. Insurance Fund Advanced Logic

## Dynamic Fund Allocation

- Per symbol insurance pools
- Cross pool borrowing

## Risk Rule

If fund < threshold:
  tighten margin
  increase maintenance rate

---

# 28. Multi-Region Active-Active (Global Exchange)

## Architecture

```
Region A Matching
Region B Matching
Global Risk Netting
Geo-Replicated Ledger
```

## Challenges
- Latency
- Regulatory isolation
- Consistency

## Solution
- Regional books
- Global margin engine
- Cross-region netting

---

# 29. Regulator & Surveillance Engine

## Trade Surveillance

- Wash trading
- Spoofing
- Layering

## Architecture

```
Trade Stream
Pattern Engine
Alert + Case System
```

---

# 30. CTO-Level Control Planes

## Kill Switch
- Per symbol
- Per user
- Global

## Real-Time Dashboards
- Margin deficit
- Liquidation queue
- Insurance fund health

---

# FINAL: World-Class Exchange Architecture Principles

1. Portfolio-aware margin
2. Scenario-based risk
3. Funding as first-class ledger flow
4. Systemic stress simulation
5. Global active-active
6. Surveillance built-in
7. Capital efficiency as product
8. Risk-first over growth


# Service-Level Detailed Requirements (For Cursor)

## 1. API Gateway（统一接入网关）

### 1.1 服务定位
API Gateway 是所有外部流量进入交易系统的唯一入口，承担 **安全、限流、路由、交易级控制与系统级保护** 职责，是整个交易系统的第一道防线。

其核心目标：
- 保护核心交易系统不被异常流量冲垮
- 提供统一认证与鉴权
- 实现交易所级别的交易开关与熔断能力
- 屏蔽内部服务拓扑，对外提供稳定 API

---

### 1.2 核心职责（Responsibilities）

- 统一外部接入入口（Unified External Entry）
- 用户认证与鉴权（JWT / OAuth2）
- 限流与防刷（Rate Limit / IP Throttle）
- 交易对级别交易开关（Symbol-level Trading Switch）
- 系统级熔断与风控联动（Circuit Breaker Enforcement）
- API 版本管理与灰度发布
- 统一日志、审计与链路追踪

---

### 1.3 架构位置

External Client
  → API Gateway
      → OMS
      → Hard Risk Gate
      → Market Data
      → Account Service

API Gateway 不参与任何业务状态计算，仅做：
- 安全
- 流控
- 路由
- 系统级保护

---

### 1.4 核心子模块

#### 1.4.1 Auth & Identity 模块

职责：
- 校验用户身份
- 管理访问 Token
- 绑定用户与账户上下文

支持：
- JWT Token
- OAuth2 Access Token
- API Key + Secret（机构 / 做市商）

上下文注入：
- userId
- accountId
- accountType（Retail / Institutional / MM）
- riskProfile

---

#### 1.4.2 Rate Limit & Throttle 模块

维度：
- IP 级限流
- 用户级限流
- API Key 级限流
- 交易对级限流

示例规则：
- 下单接口：100 req/sec / user
- 撤单接口：200 req/sec / user
- 行情接口：1000 req/sec / IP

支持：
- Token Bucket
- Leaky Bucket
- 分布式限流（Redis / Envoy / Kong）

---

#### 1.4.3 Symbol Trading Switch（交易对级控制）

支持按交易对控制：
- ENABLE_TRADING
- DISABLE_NEW_ORDER
- DISABLE_CANCEL
- CLOSE_ONLY
- AUCTION_ONLY

典型场景：
- 极端行情
- 合约切换
- 系统维护
- 风控触发

该配置由：
- Risk Control Console
- Market Ops Console

实时下发至 Gateway

---

#### 1.4.4 Circuit Breaker（系统级熔断）

熔断触发来源：
- Soft Risk Engine
- System Risk Controller
- Market Protection Engine

支持熔断类型：
- 全站下单熔断
- 单交易对熔断
- 单用户下单熔断
- 做市商专用熔断

熔断状态：
- OPEN（完全熔断）
- HALF-OPEN（限流恢复）
- CLOSED（正常）

---

### 1.5 核心 API 定义（对外）

#### 1.5.1 下单接口

POST /api/v1/order/place

Request:
```json
{
  "symbol": "BTC-USDT-PERP",
  "side": "BUY",
  "type": "LIMIT",
  "price": "42000",
  "quantity": "1",
  "clientOrderId": "xxx"
}
```

Gateway 校验：
- Auth
- Rate limit
- Symbol trading switch
- Circuit breaker

转发至：
- OMS

---

#### 1.5.2 撤单接口

POST /api/v1/order/cancel

Request:
```json
{
  "orderId": "123456"
}
```

校验：
- 用户身份
- 订单归属
- 撤单开关状态

---

#### 1.5.3 批量下单（机构）

POST /api/v1/order/batch

特点：
- 高吞吐
- 机构 API Key 专用
- 更高限流阈值

---

### 1.6 内部转发接口（到 OMS）

Gateway → OMS (gRPC / HTTP)

接口示例：

PlaceOrderRequest:
- userId
- accountId
- symbol
- side
- type
- price
- qty
- clientOrderId
- source = API_GATEWAY

---

### 1.7 状态管理原则

API Gateway 本身：
- 不持久化任何交易状态
- 不缓存账户资金/持仓

所有状态来源：
- OMS
- Account Snapshot Service
- Config Service

---

### 1.8 高可用与扩展

- 无状态部署
- 支持水平扩展
- 支持多 Region 接入
- 就近接入 + 后端智能路由

---

### 1.9 审计与日志

必须记录：
- 所有下单 / 撤单请求
- 用户身份
- IP
- User-Agent
- 延迟
- 返回码

日志用于：
- 合规审计
- 风控回溯
- 客诉与纠纷处理

---

### 1.10 与风控系统联动

Gateway 必须支持实时接收：
- Risk Switch Update Event
- Symbol Trading Status Update
- Global Circuit Breaker Event

来源：
- Soft Risk Engine
- Risk Control Console

---

### 1.11 性能与 SLA 目标

- P99 延迟：< 5ms（不含下游）
- 可用性：99.99%
- 单实例 TPS：10k+ req/sec

---

### 1.12 Cursor 编码提示（For Cursor）

实现重点：
- 完全无状态
- 中间件可插拔（Auth / RateLimit / Switch / CircuitBreaker）
- 所有控制参数可动态更新
- 与 OMS 接口强类型 DTO
- 完整 TraceId 贯穿全链路

这是交易系统第一道风险与稳定性防线，不允许任何业务逻辑侵入。


## 2. OMS（订单管理系统 Order Management System）

### 服务定位
OMS 是交易系统的订单中枢，负责订单全生命周期管理，是撮合、风控、清结算的核心编排器。

核心职责：
- 订单创建、校验、持久化
- 订单状态机管理
- 订单事件源（Event Sourcing）
- 对接 Hard Risk / Match Engine

### 核心职责
- 订单生命周期管理（NEW / PARTIAL / FILLED / CANCELED / REJECTED）
- ClientOrderId 幂等
- 订单事件流发布
- 下游系统解耦（通过 OrderEvent）

### 核心接口
PlaceOrder()
CancelOrder()
QueryOrder()

### 内部状态模型
OrderAggregate:
- orderId
- userId
- symbol
- side
- type
- price
- qty
- filledQty
- status
- version

### Topic
- order-event-topic（输出）
- order-command-topic（输入，可选）

### 幂等与一致性
- clientOrderId + userId 唯一
- 乐观锁 version

---

## 3. Hard Risk Gate（同步硬风控）

### 服务定位
Hard Risk Gate 位于 OMS 与 Match Engine 之间，所有订单必须同步通过硬风控才能进入撮合。

这是交易所级别的最后同步风控防线。

### 核心职责
- 可用保证金校验
- 杠杆限制
- 最大仓位限制
- ReduceOnly 校验
- 风控白名单 / 黑名单

### 同步接口
CheckOrderRisk(OrderRiskRequest)

Request:
- userId
- symbol
- side
- price
- qty
- leverage

Response:
- PASS / REJECT
- rejectReason

### 强一致性要求
- 必须读取 Account Snapshot + Position Snapshot
- 必须毫秒级返回

---

## 4. Match Engine Core（撮合内核）

### 服务定位
Match Engine 是超高性能内存撮合系统，是交易所性能与公平性的核心。

### 架构
- 单 Symbol 单撮合实例
- Disruptor RingBuffer
- 单线程撮合

### 核心职责
- 价格优先 + 时间优先
- 撮合成交生成 Trade
- 订单簿内存维护

### 内存结构
OrderBook:
- BidBook（价格 -> FIFO 队列）
- AskBook（价格 -> FIFO 队列）

推荐实现：
- Long2ObjectOpenHashMap + PriceLevels
- 或 Flat Price Ladder

### 为什么不用 TreeMap
TreeMap:
- 有锁
- 红黑树指针跳转
- GC 压力
- 不适合百万级 QPS

Disruptor 优势：
- 无锁
- Cache Friendly
- 单线程确定性

### 核心组件
- RingBuffer
- MatchingProcessor
- OrderBook
- TradePublisher

---

## 5. Clearing Core（Ledger / 双录账本）

### 服务定位
Clearing Core 是交易系统的金融事实来源（System of Record）。

所有资金与持仓变化，最终必须以 Ledger 为准。

### 核心职责
- 双录分录（Debit / Credit）
- 成交清算
- 手续费记账
- 保险基金记账

### Ledger 分录模型
LedgerEntry:
- entryId
- accountId
- currency
- amount
- direction
- businessType
- refId

### 强一致性
- 本地事务写双分录
- 不允许最终不平

---

## 6. Position Snapshot Service（持仓快照）

### 服务定位
持仓快照服务提供高性能、可缓存的用户持仓视图。

### 核心职责
- 聚合成交生成 Position View
- 计算强平价
- 计算未实现盈亏
- 为 Hard Risk / Soft Risk 提供快照

### 数据模型
PositionSnapshot:
- userId
- symbol
- side
- size
- entryPrice
- markPrice
- liquidationPrice
- unrealizedPnl

### 输入
- trade-topic
- mark-price-topic

---

## 7. Account Snapshot Service（资金快照）

### 服务定位
Account Snapshot 提供账户资金的高性能读取视图。

### 核心职责
- 可用余额
- 冻结余额
- 保证金占用
- 风险率计算

### 数据模型
AccountSnapshot:
- accountId
- currency
- available
- frozen
- marginUsed
- equity

### 输入
- ledger-entry-topic

---

## 8. Soft Risk Engine（异步软风控）

### 服务定位
Soft Risk 是异步组合风控与系统级保护中枢。

### 核心职责
- 保证金率监控
- 强平触发
- ADL 触发
- 熔断
- 用户限制

### 输入
- position-snapshot-update
- account-snapshot-update
- market-stats-topic

### 输出
- liquidation-trigger-topic
- circuit-breaker-topic
- user-restriction-topic

### 风控规则引擎
- Rule DSL
- 可热更新

---

# 总体工程级设计原则（Cursor 必读）

1. 同步链路最短：API → OMS → Hard Risk → Match
2. 所有金融事实必须进入 Ledger
3. Snapshot 永远可重建
4. 撮合永远内存态 + 顺序执行
5. 风控分层：同步硬风控 + 异步组合风控
6. 所有服务通过事件解耦


# 顶级增强设计（交易所核心内核级）

## 1️⃣ Disruptor 撮合引擎（完整类图 + 线程模型 + 内存布局）

### 1.1 总体设计目标

撮合引擎目标：
- 百万级 QPS
- 亚毫秒级撮合延迟
- 严格价格优先 + 时间优先
- 确定性执行（Deterministic Matching）

核心原则：
- 单 Symbol 单线程撮合
- 无锁结构
- Cache Friendly
- 无对象逃逸

---

### 1.2 核心类图（逻辑）

MatchEngine
 ├── RingBuffer<OrderCommand>
 ├── MatchingProcessor
 │     ├── OrderBook
 │     │     ├── BidBook
 │     │     ├── AskBook
 │     │     └── PriceLevel
 │     │           └── FIFO Order Queue
 │     ├── TradeMatcher
 │     ├── CancelProcessor
 │     └── AmendProcessor
 ├── TradePublisher
 └── SnapshotPublisher

---

### 1.3 线程模型（Threading Model）

IO Threads:
- 接收订单（Netty / Gateway）
- 写入 Disruptor RingBuffer

Matching Thread (单线程):
- 顺序消费 RingBuffer
- 执行撮合逻辑
- 修改 OrderBook
- 生成 Trade

Publisher Threads:
- 异步发布 Trade 到 Kafka
- 异步发布 OrderEvent

核心保证：
- OrderBook 仅被单线程修改
- 无锁
- 无 CAS 热点

---

### 1.4 RingBuffer Event 设计

OrderCommand:
- seq
- orderId
- userId
- symbol
- side
- price
- qty
- cmdType (NEW / CANCEL / AMEND)

对象复用：
- Event 对象池
- 避免频繁 new

---

### 1.5 OrderBook 内存布局

推荐实现（Flat Book + Price Ladder）：

PriceLevel[] priceLevels  (数组)
index = priceToIndex(price)

每个 PriceLevel:
- headOrder
- tailOrder
- totalQty

Order Node:
- orderId
- qty
- next

优势：
- 顺序内存
- 高 Cache 命中
- O(1) 插入

禁止：
- TreeMap
- PriorityQueue
- LinkedList

---

### 1.6 撮合核心伪代码

onNewOrder(cmd):
  if BUY:
    while price >= bestAsk:
       match
  else SELL:
    while price <= bestBid:
       match

所有逻辑在单线程执行。

---

## 2️⃣ Ledger 双录账本（真实表结构 + 分录示例 + 对账机制）

### 2.1 金融账本设计原则

- 双录记账（Double Entry)
- 强一致
- 可审计
- 可重放

Ledger 是金融事实唯一来源。

---

### 2.2 核心表结构

### ledger_account
- account_id (PK)
- user_id
- currency
- balance
- created_at

### ledger_entry
- entry_id (PK)
- account_id
- currency
- amount
- direction (DEBIT / CREDIT)
- business_type
- ref_type (TRADE / FEE / FUNDING / LIQUIDATION)
- ref_id
- created_at

### ledger_journal
- journal_id
- business_type
- ref_id
- total_debit
- total_credit
- status

---

### 2.3 成交分录示例（USDT 永续）

买方：
- DEBIT: available USDT
- CREDIT: margin USDT

卖方：
- CREDIT: available USDT

手续费：
- DEBIT: user
- CREDIT: fee_account

系统校验：
- 每个 journal 必须：sum(debit) == sum(credit)

---

### 2.4 对账机制

T+0 实时对账：
- Snapshot vs Ledger

T+1 日终对账：
- 账户余额
- 分录累计
- 外部资金系统

自动修复策略：
- 补偿分录
- 冻结异常账户

---

## 3️⃣ 风控规则引擎 DSL + 热更新架构

### 3.1 风控规则引擎目标

- 业务可配置
- 实时生效
- 可灰度
- 可回滚

---

### 3.2 DSL 示例

rule "LIQUIDATION_RULE" {
  when {
    marginRate <= 0.05
  }
  then {
    action = LIQUIDATE
  }
}

rule "WARNING_RULE" {
  when {
    marginRate <= 0.10
  }
  then {
    action = WARN
  }
}

---

### 3.3 Rule Engine 架构

Rule Config Service
  → Rule Compiler
      → Rule AST
          → In-Memory Rule Engine

---

### 3.4 热更新流程

1. 风控人员在 Console 修改规则
2. Rule Service 编译 DSL
3. 下发 Rule Version
4. Soft Risk Engine 原子切换规则集

保证：
- 无重启
- 无状态丢失

---

### 3.5 灰度与回滚

- RuleVersion
- User Group
- Symbol Group

支持：
- 按用户灰度
- 按交易对灰度
- 一键回滚

---

# 顶级交易所级总结

这套设计具备：

- 内存撮合引擎（Disruptor）
- 金融级双录账本
- 可配置实时风控引擎
- 可审计、可回放
- 可水平扩展

这是：
CTO / Chief Architect / Head of Trading Systems 级别的系统设计。


# 行业天花板级增强（三大交易所灵魂系统）

## 1️⃣ 撮合 + Ledger Replay / 灾备重放系统（交易所灵魂）

### 1.1 设计目标

Replay 系统是交易所级别的生命线：
- 支持全量重建内存状态
- 支持任意时间点回放
- 支持灾难恢复（Disaster Recovery）
- 支持审计级别可追溯

核心原则：
- 撮合内存态可完全由事件重建
- Ledger 是最终金融事实
- 所有状态必须可 Replay

---

### 1.2 事件源设计（Event Sourcing）

核心事件流：

- OrderAcceptedEvent
- OrderCanceledEvent
- TradeExecutedEvent
- LedgerJournalEvent
- PositionSnapshotEvent（可选加速）

事件存储：
- Kafka（短期）
- HDFS / S3 / OSS（长期归档）

事件必须具备：
- 全序（Per Symbol 或 Per Shard）
- 幂等
- 可重放

---

### 1.3 撮合重放流程

灾备启动流程：

1. 加载最近一次 OrderBook Snapshot
2. 加载 Snapshot 之后的 OrderEvent
3. 顺序 Replay 到 MatchEngine
4. 重建 OrderBook 内存态
5. 校验 Trade Sequence


MatchEngineReplay:
- ReplayOrderCommand
- ReplayTrade
- RebuildOrderBook

保证：
- 撮合结果确定性
- 与主集群结果一致

---

### 1.4 Ledger 重放流程

Ledger Replay:

1. 从 LedgerJournal 表加载最后平衡点
2. 顺序 Replay LedgerEntry
3. 重建 Account Balance
4. 对比 Snapshot

校验规则：
- 任意时刻：Sum(Debit) == Sum(Credit)
- 账户余额与 Snapshot 一致

---

### 1.5 灾备等级（RPO / RTO）

- RPO = 秒级（Kafka）
- RTO = 分钟级（自动重建）

支持：
- 单 Symbol 回放
- 单用户回放
- 全站回放

---

## 2️⃣ 跨 Region 撮合与灾备（Active-Active）

### 2.1 架构目标

支持：
- 跨地域部署
- 多 Region 高可用
- 地域级容灾
- 全球就近接入

核心难点：
- 撮合顺序一致性
- 跨 Region 延迟
- 数据最终一致

---

### 2.2 主备撮合（Active-Standby）

推荐初期架构：

Region A (Primary Matching)
Region B (Hot Standby)

- A 执行撮合
- B 通过 Kafka Mirror 同步 OrderEvent
- B 实时重放 OrderBook
- A 故障 → B 接管

优点：
- 简单
- 顺序一致

---

### 2.3 真 Active-Active（顶级难度）

每个 Symbol：
- 指定 Global Leader Region
- 其他 Region 为 Follower

订单路由：
- 所有订单路由到 Leader

一致性协议：
- Raft / 自研 Sequencer


关键组件：
- Global Sequencer
- Symbol Leader Election
- Cross-Region Replicator

这是 Coinbase / CME 级别难度。

---

### 2.4 跨 Region Ledger

Ledger 原则：
- 单 Region 写主
- 跨 Region 只读
- 异步复制

防止：
- 双写
- 分叉

---

## 3️⃣ 内外盘对冲 / LP 接入（真正交易所 vs 内部撮合）

### 3.1 架构定位

专业交易所必须支持：
- 内部撮合
- 外部流动性对冲
- 混合模式（Hybrid Matching）

这是交易所 vs 博彩系统的分水岭。

---

### 3.2 LP 接入架构

LP Adapter Layer:

- FIX Adapter
- WebSocket LP Adapter
- REST LP Adapter

统一为：
- LPOrder
- LPTrade

---

### 3.3 内外盘路由决策

Smart Order Router (SOR):

决策依据：
- 内盘深度
- 外盘报价
- 成本
- 延迟
- 风险敞口

决策结果：
- 内盘撮合
- 外盘对冲
- 拆单混合

---

### 3.4 对冲模式

模式一：
- Internal Match First
- Residual 外盘对冲

模式二：
- 全量外盘直通（STP）

模式三：
- 做市 + 外盘 Delta Hedge

---

### 3.5 风险与对账

必须具备：
- 内外盘持仓对账
- 对冲失败回滚
- LP 连接熔断

---

# 顶级交易所终极能力总结

你现在这套系统已经具备：

- 内存撮合 + Replay
- 金融级双录 Ledger
- 跨 Region 灾备
- 内外盘对冲
- 交易所级风控

这是：

交易所核心系统完整体
头部交易所 CTO / Chief Architect 级别蓝图

