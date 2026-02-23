# 私有推送系统需求文档 (Private Push System PRD)

> **版本**: v1.1.0  
> **状态**: Production Grade  
> **最后更新**: 2024-02-20  
> **作者**: Exchange Product & Architecture Team

---

## 1. 文档目标

本文档定义合约交易所私有推送系统的完整需求，涵盖：
- 订单状态实时推送（完全成交、部分成交、挂单、撤单等）
- 账户资金变化推送
- 持仓变化推送
- 前端状态同步机制

**核心原则**：
1. **低延迟**：订单状态变更 → 客户端收到推送 < 100ms (P99)
2. **高可靠**：消息不丢失、不重复、有序到达
3. **最终一致性**：OMS状态与客户端展示状态最终一致
4. **可扩展**：支持百万级并发连接

---

## 2. 业务场景分析

### 2.1 订单生命周期推送场景

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           订单生命周期状态机                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   NEW ──────▶ RISK_PASSED ──────▶ SENT_TO_MATCH ──────▶ IN_ORDERBOOK        │
│    │                                                          │              │
│    │                      ┌───────────────────────────────────┘              │
│    │                      │                                                  │
│    │                      ▼                                                  │
│    │              PARTIALLY_FILLED ◀──────────────────┐                      │
│    │                      │                          │                      │
│    │                      │ (继续撮合)                │                      │
│    │                      ▼                          │                      │
│    │                 FILLED (完全成交)                │                      │
│    │                                                          │              │
│    └──────▶ CANCELING ──────▶ CANCELED (用户撤单/系统撤单)                    │
│                                                                              │
│    ┌──────▶ RISK_REJECTED (风控拒绝)                                         │
│    └──────▶ SYSTEM_REJECTED (系统拒绝)                                       │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 关键业务场景

| 场景 | 触发条件 | 推送内容 | 前端行为 |
|------|---------|---------|---------|
| **场景1: 订单创建成功** | 订单通过风控进入撮合 | 订单初始状态 NEW | 添加到"当前委托"列表 |
| **场景2: 订单部分成交** | 订单部分匹配 | executionReport (PARTIALLY_FILLED) + 本次成交详情 | 更新已成交数量，保持订单在"当前委托" |
| **场景3: 订单完全成交** | 订单全部匹配 | executionReport (FILLED) + 最后成交详情 | 移出"当前委托"，添加到"历史委托" |
| **场景4: 订单被拒绝** | 风控/系统拒绝 | executionReport (REJECTED) + 拒绝原因 | 从"当前委托"移除，显示错误提示 |
| **场景5: 订单撤单成功** | 用户撤单/系统撤单 | executionReport (CANCELED) | 从"当前委托"移除，添加到"历史委托" |
| **场景6: 批量成交** | 一个大单与多个对手方成交 | 多个 executionReport (同一orderId) | 累加成交数量，直到完全成交 |

### 2.3 部分成交的核心挑战

```
挑战：如何保证前端订单状态的一致性？

订单: BUY 1.0 BTC @ 50000 USDT

时间线:
T1: 创建订单 ──▶ 前端显示: 数量 1.0, 已成交 0
                  
T2: 部分成交 0.3 ──▶ 推送 executionReport
                     本次成交: 0.3 @ 50000
                     累计成交: 0.3
                     前端更新: 已成交 0.3/1.0 (30%)
                     
T3: 部分成交 0.5 ──▶ 推送 executionReport
                     本次成交: 0.5 @ 50000
                     累计成交: 0.8
                     前端更新: 已成交 0.8/1.0 (80%)
                     
T4: 部分成交 0.2 ──▶ 推送 executionReport
                     本次成交: 0.2 @ 50000
                     累计成交: 1.0
                     状态: FILLED
                     前端更新: 订单完成，移到历史记录
```

---

## 3. 系统架构设计

### 3.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              客户端层 (Clients)                              │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐            │
│  │  Web App│  │Mobile App│  │ 量化程序 │  │ 第三方  │  │  其他   │            │
│  └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘            │
│       └────────────┴────────────┴────────────┴────────────┘                 │
│                              WSS (TLS)                                      │
└──────────────────────────────┬──────────────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────────────┐
│                      公有推送系统 (Public Push) Port: 8096                   │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 公有频道: trade@{symbol}, depth@{symbol}, kline@{symbol}@{interval} │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                               │
                               │ (同进程/同集群，频道隔离)
                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      私有推送系统 (Private Push) Port: 8099                  │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 私有频道:                                                            │   │
│  │  - executionReport  (订单执行报告)                                  │   │
│  │  - account          (账户资金变化)                                  │   │
│  │  - position         (持仓变化)                                      │   │
│  │  - balance          (余额快照)                                      │   │
│  │  - fundingFee       (资金费结算)                                    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────┬──────────────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────────────┐
│                              Kafka 消息层                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  订单状态流:                                                                 │
│  ┌──────────────┐    order-state-{symbol}    ┌──────────────┐              │
│  │ Match Engine │ ─────────────────────────▶ │     OMS      │              │
│  └──────────────┘                            └──────┬───────┘              │
│                                                      │                       │
│                                                      ▼                       │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     private-order-state-topic                        │   │
│  │  (统一订单状态Topic，按 userId 分区，保证单个用户订单有序)            │   │
│  └──────────────────────────────┬──────────────────────────────────────┘   │
│                                 │                                            │
└─────────────────────────────────┼────────────────────────────────────────────┘
                                  │
┌─────────────────────────────────▼────────────────────────────────────────────┐
│                      私有推送服务 (Private Push Service)                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     Private Kafka Consumer                           │   │
│  │  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐              │   │
│  │  │ Order State   │ │ Account       │ │ Position      │              │   │
│  │  │ Consumer      │ │ Consumer      │ │ Consumer      │              │   │
│  │  └───────┬───────┘ └───────┬───────┘ └───────┬───────┘              │   │
│  │          └─────────────────┼─────────────────┘                       │   │
│  │                            ▼                                         │   │
│  │  ┌───────────────────────────────────────────────────────────────┐   │   │
│  │  │                   User Message Router                          │   │   │
│  │  │   userId ──▶ sessionId ──▶ WebSocket Connection               │   │   │
│  │  └───────────────────────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     Session Manager                                  │   │
│  │  - 用户认证 (JWT/API Key)                                            │   │
│  │  - Session 绑定 userId                                               │   │
│  │  - 心跳检测                                                          │   │
│  │  - ACK 确认机制                                                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 数据流详解

#### 3.2.1 订单状态推送流

```
用户下单:

1. 客户端 ──REST──▶ API Gateway ──▶ OMS
                    
2. OMS ──Kafka──▶ Match Engine (order-event-{symbol})

3. Match Engine 撮合:
   - 生成 TradeEvent ──▶ Kafka (trade-event)
   - 生成 OrderStateEvent ──▶ Kafka (order-state-{symbol})
   
4. OMS 消费 order-state-{symbol}:
   - 更新订单状态 (MySQL)
   - 发布到 private-order-state-topic
   
5. Private Push Service 消费 private-order-state-topic:
   - 解析 userId
   - 查找 userId 对应的 WebSocket Session
   - 发送 executionReport 消息
   
6. 客户端收到 WebSocket 消息:
   - 解析 executionReport
   - 更新本地订单状态
   - 发送 ACK 确认
```

#### 3.2.2 账户资金变化推送流

```
账户资金变化:

1. Ledger Core / Snapshot Account Core:
   - 资金冻结、解冻、结算
   - 余额快照更新
   - 发布到 private-account-change-topic
   
2. Private Push Service 消费 private-account-change-topic:
   - 解析 userId
   - 查找 userId 对应的 WebSocket Session
   - 发送 account 消息
   
3. 客户端收到 WebSocket 消息:
   - 解析 account 消息
   - 更新本地账户余额
   - 发送 ACK 确认
```

**触发时机**：
- 订单冻结资金：下单时冻结保证金
- 订单解冻资金：撤单时解冻保证金
- 成交结算：成交后结算资金
- 资金费结算：资金费结算时更新余额
- 强平结算：强平时结算资金

#### 3.2.3 持仓变化推送流

```
持仓变化:

1. Position Snapshot Core / Liquidation Core / ADL Core:
   - 持仓快照更新
   - 强平触发
   - ADL触发
   - 发布到 private-position-change-topic
   
2. Private Push Service 消费 private-position-change-topic:
   - 解析 userId
   - 查找 userId 对应的 WebSocket Session
   - 发送 position 消息
   
3. 客户端收到 WebSocket 消息:
   - 解析 position 消息
   - 更新本地持仓信息
   - 发送 ACK 确认
```

**触发时机**：
- 开仓：订单成交后开仓
- 平仓：订单成交后平仓
- 强平：强平触发时
- ADL：ADL触发时
- 资金费结算：资金费结算时更新持仓

#### 3.2.4 部分成交消息格式

```json
{
  "e": "executionReport",
  "E": 1708326400123,
  "s": "BTCUSDT",
  "i": 123456789,
  "c": "myClientOrderId123",
  "S": "BUY",
  "o": "LIMIT",
  "f": "GTC",
  "q": "1.00000000",
  "p": "50000.00000000",
  "X": "PARTIALLY_FILLED",
  "x": "TRADE",
  "z": "0.30000000",
  "l": "0.30000000",
  "L": "50000.00000000",
  "n": "0.00030000",
  "N": "USDT",
  "T": 1708326400110,
  "t": 987654321,
  "seq": 12345
}
```

**字段说明**:
- `X`: 订单当前状态 (NEW/PARTIALLY_FILLED/FILLED/CANCELED/REJECTED)
- `x`: 事件类型 (NEW/CANCELED/REPLACED/TRADE/EXPIRED)
- `z`: 累计已成交数量 (cumulative filled quantity)
- `l`: 本次成交数量 (last filled quantity)
- `L`: 本次成交价格 (last filled price)
- `n`: 本次成交手续费
- `t`: 本次成交的 tradeId
- `seq`: 序列号（用于客户端去重和排序）

### 3.3 Kafka Topic 设计

| Topic | 分区策略 | 生产者 | 消费者 | 说明 |
|-------|---------|--------|--------|------|
| `order-state-{symbol}` | symbol (单分区) | Match Engine | OMS | 订单状态回传 |
| `private-order-state` | userId % 100 | OMS | Private Push | 统一订单状态 |
| `private-account-change` | userId % 100 | Ledger | Private Push | 资金变化 |
| `private-position-change` | userId % 100 | Position Snapshot | Private Push | 持仓变化 |

---

## 4. 消息协议设计

### 4.1 WebSocket 连接建立

```
客户端                                          Private Push Service
   │                                                   │
   │  1. WebSocket Handshake                           │
   │     Headers:                                       │
   │       Authorization: Bearer {jwt_token}           │
   │  ───────────────────────────────────────────────▶ │
   │                                                   │
   │  2. 验证 JWT                                       │
   │     - 解析 userId                                  │
   │     - 绑定 session 与 userId                       │
   │                                                   │
   │  3. 响应连接建立                                     │
   │  ◀─────────────────────────────────────────────── │
   │     {                                             │
   │       "e": "connectionAck",                       │
   │       "E": 1708326400000,                         │
   │       "userId": 12345,                            │
   │       "sessionId": "sess_xxx"                     │
   │     }                                             │
   │                                                   │
   │  4. 订阅私有频道                                     │
   │  ───────────────────────────────────────────────▶ │
   │     {                                             │
   │       "method": "SUBSCRIBE",                      │
   │       "params": ["executionReport", "account"],   │
   │       "id": 1                                     │
   │     }                                             │
   │                                                   │
   │  5. 确认订阅                                        │
   │  ◀─────────────────────────────────────────────── │
   │     {                                             │
   │       "result": null,                             │
   │       "id": 1                                     │
   │     }                                             │
```

### 4.2 消息类型定义

#### 4.2.1 订单执行报告 (executionReport)

```typescript
interface ExecutionReport {
  e: "executionReport";        // 事件类型
  E: number;                   // 事件时间 (ms)
  s: string;                   // 交易对 (symbol)
  i: number;                   // 订单ID (orderId)
  c: string;                   // 客户端订单ID
  S: "BUY" | "SELL";          // 买卖方向
  o: "LIMIT" | "MARKET" | ...; // 订单类型
  f: "GTC" | "IOC" | "FOK";   // 有效时间
  q: string;                   // 原始数量
  p: string;                   // 订单价格
  
  // 状态信息
  X: OrderStatus;              // 订单当前状态
  x: EventType;                // 事件类型
  
  // 成交信息
  z: string;                   // 累计已成交数量
  Z: string;                   // 累计已成交金额
  l: string;                   // 本次成交数量
  L: string;                   // 本次成交价格
  
  // 费用信息
  n: string;                   // 本次成交手续费
  N: string;                   // 手续费资产
  
  // 扩展信息
  T: number;                   // 成交时间
  t: number;                   // 成交ID
  seq: number;                 // 序列号
}

type OrderStatus = 
  | "NEW"           // 新建
  | "PARTIALLY_FILLED"  // 部分成交
  | "FILLED"        // 完全成交
  | "CANCELED"      // 已撤单
  | "REJECTED"      // 已拒绝
  | "EXPIRED";      // 已过期

type EventType = 
  | "NEW"           // 新订单
  | "CANCELED"      // 撤单
  | "REPLACED"      // 改单
  | "TRADE"         // 成交
  | "EXPIRED";      // 过期
```

#### 4.2.2 账户资金变化 (account)

**完整消息格式**：

```json
{
  "e": "account",
  "E": 1708326400000,
  "u": 1708326400000,
  "seq": 12345,
  "B": [
    {
      "a": "USDT",
      "f": "10000.00000000",
      "l": "5000.00000000"
    }
  ],
  "C": [
    {
      "a": "USDT",
      "change": "100.00000000",
      "changeType": "FREEZE",
      "bizType": "ORDER",
      "bizId": "order_123"
    }
  ]
}
```

**TypeScript 接口定义**：

```typescript
interface AccountUpdate {
  e: "account";                // 事件类型
  E: number;                   // 事件时间 (ms)
  u: number;                   // 最后更新时间 (ms)
  seq: number;                 // 序列号（用于去重和排序）
  B: Balance[];                // 余额列表（快照）
  C?: Change[];                // 变化列表（可选，增量变化）
}

interface Balance {
  a: string;                   // 资产 (asset)
  f: string;                   // 可用余额 (free)
  l: string;                   // 冻结余额 (locked)
}

interface Change {
  a: string;                   // 资产
  change: string;               // 变化金额（正数=增加，负数=减少）
  changeType: "FREEZE" | "UNFREEZE" | "SETTLE" | "FUNDING_FEE";
  bizType: "ORDER" | "TRADE" | "LIQUIDATION" | "ADL" | "FUNDING";
  bizId: string;               // 业务ID（订单ID、成交ID等）
}
```

**字段说明**：
- `B`: 余额快照列表，包含所有资产的可用余额和冻结余额
- `C`: 变化列表（可选），仅包含本次变化的资产和金额
- `changeType`: 变化类型
  - `FREEZE`: 冻结（下单时）
  - `UNFREEZE`: 解冻（撤单时）
  - `SETTLE`: 结算（成交时）
  - `FUNDING_FEE`: 资金费结算
- `bizType`: 业务类型，标识变化来源
- `bizId`: 业务ID，用于关联具体业务（如订单ID）

**与Ledger集成**：
- Ledger Core 在资金变化时发布事件到 `private-account-change` topic
- 事件格式包含：userId、eventType、timestamp、balances、changes
- Private Push Service 消费事件并转换为 Binance 兼容格式

#### 4.2.3 持仓变化 (position)

**完整消息格式**：

```json
{
  "e": "position",
  "E": 1708326400000,
  "seq": 12345,
  "s": "BTCUSDT",
  "ps": "LONG",
  "pa": "1.00000000",
  "ep": "50000.00000000",
  "mp": "51000.00000000",
  "up": "1000.00000000",
  "l": 10,
  "m": "5000.00000000"
}
```

**TypeScript 接口定义**：

```typescript
interface PositionUpdate {
  e: "position";               // 事件类型
  E: number;                   // 事件时间 (ms)
  seq: number;                 // 序列号（用于去重和排序）
  s: string;                   // 交易对 (symbol)
  ps: "LONG" | "SHORT";        // 持仓方向 (position side)
  pa: string;                  // 持仓数量 (position amount)
  ep: string;                  // 开仓均价 (entry price)
  mp: string;                  // 标记价格 (mark price)
  up: string;                  // 未实现盈亏 (unrealized PnL)
  l: number;                   // 杠杆倍数 (leverage)
  m: string;                   // 保证金 (margin)
}
```

**字段说明**：
- `s`: 交易对，如 "BTCUSDT"
- `ps`: 持仓方向，LONG=多仓，SHORT=空仓
- `pa`: 持仓数量（8位小数）
- `ep`: 开仓均价（8位小数）
- `mp`: 标记价格（8位小数），用于计算未实现盈亏
- `up`: 未实现盈亏（8位小数），正数=盈利，负数=亏损
- `l`: 杠杆倍数
- `m`: 保证金（8位小数）

**与Position Snapshot集成**：
- Position Snapshot Core 在持仓变化时发布事件到 `private-position-change` topic
- 事件格式包含：userId、symbol、eventType、timestamp、position、change
- Private Push Service 消费事件并转换为 Binance 兼容格式

### 4.3 序列号生成机制

**核心要求**：
1. **全局唯一**：不同用户的序列号不冲突
2. **有序性**：同一用户的序列号严格递增
3. **持久性**：服务重启后序列号不重置

**生成算法**：

使用组合序列号：`userId * 10^12 + timestamp % 10^12 + sequence % 10^6`

```java
long nextSequenceOptimized() {
    long timestamp = System.currentTimeMillis();
    long seq = sequence.incrementAndGet();
    
    // 组合序列号：userId * 10^12 + timestamp % 10^12 + seq % 10^6
    return userId * 1_000_000_000_000L + 
           (timestamp % 1_000_000_000_000L) + 
           (seq % 1_000_000L);
}
```

**优势**：
- 不同用户的序列号不冲突（userId 前缀）
- 同一用户的序列号有序（timestamp + sequence）
- 服务重启后序列号不重置（基于 timestamp）

**替代方案**：
- 使用分布式序列号生成器（如 Snowflake）
- 使用 Redis 原子计数器（按用户）

### 4.4 ACK 确认机制

为确保消息不丢失，引入 ACK 机制：

```
服务端 ──▶ 客户端: 推送消息 (带 seq)
客户端 ──▶ 服务端: ACK { "method": "ACK", "seq": 12345 }

如果服务端在 5s 内未收到 ACK:
- 重发消息 (最多 3 次)
- 仍然失败则断开连接，让客户端重连
```

**ACK 配置**：
- 超时时间：5秒（可配置）
- 最大重试次数：3次（可配置）
- 重试间隔：1秒（可配置）

**ACK 流程**：
1. 服务端发送消息时，将消息加入待确认队列（pendingMessages）
2. 客户端收到消息后，发送 ACK 确认
3. 服务端收到 ACK 后，从待确认队列移除消息
4. 定时任务检查待确认消息，超时未确认则重发
5. 超过最大重试次数后，断开连接

### 4.5 消息去重机制

**核心问题**：
- 消息可能重复（网络重传、服务重启）
- 消息可能乱序（网络延迟）
- 消息可能跳跃（消息丢失后重连）

**去重策略**：

1. **客户端去重**：
   - 维护已接收的 seq 集合
   - 检测到 `seq <= lastSeq` 时丢弃（重复或乱序）
   - 检测到 seq 跳跃时请求快照

```typescript
class MessageDeduplicator {
  private lastSeq: number = 0;
  private receivedSeqs: Set<number> = new Set();
  
  handleMessage(seq: number): boolean {
    // 1. 检查是否重复
    if (seq <= this.lastSeq) {
      console.warn("Duplicate or out-of-order message", seq);
      return false; // 丢弃
    }
    
    // 2. 检查是否跳跃（seq 差距过大）
    if (seq - this.lastSeq > 1000) {
      console.warn("Sequence gap detected, requesting snapshot", seq);
      this.requestSnapshot();
      return false;
    }
    
    // 3. 更新状态
    this.lastSeq = seq;
    this.receivedSeqs.add(seq);
    
    // 4. 清理旧序列号（保留最近1000个）
    if (this.receivedSeqs.size > 1000) {
      const minSeq = this.lastSeq - 1000;
      for (const s of this.receivedSeqs) {
        if (s < minSeq) {
          this.receivedSeqs.delete(s);
        }
      }
    }
    
    return true; // 接受
  }
}
```

2. **服务端去重**（可选）：
   - 使用 Redis 缓存已发送的 seq（TTL 5分钟）
   - 发送前检查是否已发送
   - 避免重复发送

### 4.6 限流机制

**限流策略**：令牌桶算法

**限流粒度**：
1. **用户级别限流**：防止单个用户刷屏
2. **IP级别限流**：防止DDoS攻击

**限流配置**：
```yaml
private:
  push:
    rate-limit:
      messages-per-second: 100    # 每连接每秒最大消息数
      subscribe-per-second: 10    # 每连接每秒最大订阅数
```

**限流流程**：
1. 发送消息前检查用户限流
2. 发送消息前检查IP限流
3. 限流后丢弃消息并记录统计
4. 记录限流日志

**限流处理**：
- 限流后：丢弃消息，记录统计，不抛异常
- 限流日志：记录限流用户、IP、时间
- 监控告警：限流频率过高时告警

---

## 5. 前端状态同步机制

### 5.1 核心问题

**问题1: 如何保证订单状态不丢失？**
- 方案：ACK 确认 + 消息持久化 + 断线重连时拉取快照

**问题2: 部分成交如何更新？**
- 方案：基于 seq 的增量更新，服务端推送包含累计成交数量

**问题3: 消息乱序怎么办？**
- 方案：Kafka 分区保证单个用户有序，seq 用于客户端去重

### 5.2 前端订单状态管理

```typescript
// 订单状态管理器
class OrderStateManager {
  private orders: Map<number, Order> = new Map();
  private lastSeq: number = 0;
  
  // 处理 executionReport
  handleExecutionReport(report: ExecutionReport) {
    // 1. 检查序列号（去重和乱序检测）
    if (report.seq <= this.lastSeq) {
      console.warn("Duplicate or out-of-order message", report.seq);
      return;
    }
    this.lastSeq = report.seq;
    
    // 2. 查找或创建订单
    let order = this.orders.get(report.i);
    if (!order) {
      order = {
        orderId: report.i,
        clientOrderId: report.c,
        symbol: report.s,
        side: report.S,
        type: report.o,
        price: report.p,
        quantity: report.q,
        filledQuantity: "0",
        status: "NEW"
      };
      this.orders.set(report.i, order);
    }
    
    // 3. 更新订单状态（关键：使用服务端累计值）
    order.status = report.X;
    order.filledQuantity = report.z;  // 累计已成交
    order.lastFilledQuantity = report.l;  // 本次成交
    order.lastFilledPrice = report.L;
    
    // 4. 触发 UI 更新
    this.notifyUpdate(order);
    
    // 5. 发送 ACK
    this.sendAck(report.seq);
  }
  
  // 断线重连后拉取快照
  async syncAfterReconnect() {
    const snapshot = await fetch("/api/orders/open");
    // 合并快照和本地状态
    for (const order of snapshot) {
      const local = this.orders.get(order.orderId);
      if (!local || local.seq < order.seq) {
        this.orders.set(order.orderId, order);
      }
    }
  }
}
```

### 5.3 部分成交处理流程

```
场景：订单 BUY 1.0 BTC，分三次成交

T1: 推送 executionReport
    X: PARTIALLY_FILLED
    z: 0.3 (累计成交)
    l: 0.3 (本次成交)
    
    前端更新:
    - 当前委托列表: 显示 "已成交 0.3/1.0"
    - 成交历史: 新增一条成交记录
    
T2: 推送 executionReport
    X: PARTIALLY_FILLED
    z: 0.8 (累计成交)
    l: 0.5 (本次成交)
    
    前端更新:
    - 当前委托列表: 显示 "已成交 0.8/1.0"
    - 成交历史: 新增一条成交记录
    
T3: 推送 executionReport
    X: FILLED
    z: 1.0 (累计成交)
    l: 0.2 (本次成交)
    
    前端更新:
    - 当前委托列表: 移除该订单
    - 历史委托列表: 添加完成订单
    - 成交历史: 新增一条成交记录
```

---

## 6. 接口规范

### 6.1 REST API (查询快照)

#### 获取当前订单列表
```
GET /api/v1/private/orders/open

Response:
{
  "code": 0,
  "data": [
    {
      "orderId": 123456789,
      "clientOrderId": "myOrder123",
      "symbol": "BTCUSDT",
      "side": "BUY",
      "type": "LIMIT",
      "price": "50000.00000000",
      "quantity": "1.00000000",
      "filledQuantity": "0.30000000",
      "status": "PARTIALLY_FILLED",
      "createdTime": 1708326400000,
      "updatedTime": 1708326450000,
      "seq": 12345
    }
  ]
}
```

#### 获取订单历史
```
GET /api/v1/private/orders/history?symbol=BTCUSDT&startTime=xxx&endTime=xxx&limit=100

Response:
{
  "code": 0,
  "data": [
    {
      "orderId": 123456789,
      "clientOrderId": "myOrder123",
      "symbol": "BTCUSDT",
      "side": "BUY",
      "type": "LIMIT",
      "price": "50000.00000000",
      "quantity": "1.00000000",
      "filledQuantity": "1.00000000",
      "status": "FILLED",
      "createdTime": 1708326400000,
      "updatedTime": 1708326450000,
      "seq": 12345
    }
  ]
}
```

#### 获取账户余额快照
```
GET /api/v1/private/account/balance

Headers:
  X-User-Id: 12345

Response:
{
  "code": 0,
  "data": {
    "userId": 12345,
    "balances": [
      {
        "asset": "USDT",
        "available": "10000.00000000",
        "frozen": "5000.00000000"
      }
    ],
    "timestamp": 1708326400000
  }
}
```

#### 获取持仓快照
```
GET /api/v1/private/position?symbol=BTCUSDT

Headers:
  X-User-Id: 12345

Response:
{
  "code": 0,
  "data": [
    {
      "symbol": "BTCUSDT",
      "side": "LONG",
      "quantity": "1.00000000",
      "entryPrice": "50000.00000000",
      "markPrice": "51000.00000000",
      "unrealizedPnl": "1000.00000000",
      "leverage": 10,
      "margin": "5000.00000000"
    }
  ]
}
```

#### 获取会话统计（管理接口）
```
GET /api/v1/private/stats

Response:
{
  "code": 0,
  "data": {
    "sessions": 1000,
    "users": 500,
    "ips": 200
  }
}
```

**快照恢复流程**：

1. **客户端断线重连**：
   - 建立WebSocket连接
   - 订阅频道
   - 调用REST API拉取快照

2. **快照数据格式**：
   - 订单快照：包含订单ID、状态、已成交数量、seq等
   - 账户快照：包含所有资产余额
   - 持仓快照：包含所有持仓信息

3. **合并策略**：
   - 以服务端seq为准
   - 如果本地seq < 服务端seq，使用服务端数据
   - 如果本地seq >= 服务端seq，保留本地数据

```typescript
async syncAfterReconnect() {
  // 1. 拉取订单快照
  const orders = await fetch("/api/v1/private/orders/open");
  
  // 2. 拉取账户快照
  const account = await fetch("/api/v1/private/account/balance");
  
  // 3. 拉取持仓快照
  const positions = await fetch("/api/v1/private/position");
  
  // 4. 合并快照和本地状态
  for (const order of orders) {
    const local = this.orders.get(order.orderId);
    if (!local || local.seq < order.seq) {
      this.orders.set(order.orderId, order);
    }
  }
  
  // 5. 更新账户余额
  this.account = account;
  
  // 6. 更新持仓
  this.positions = positions;
}
```

### 6.2 WebSocket 协议

#### 订阅请求
```json
{
  "method": "SUBSCRIBE",
  "params": ["executionReport", "account", "position"],
  "id": 1
}
```

#### ACK 确认
```json
{
  "method": "ACK",
  "seq": 12345,
  "id": 2
}
```

---

## 7. 性能指标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| 端到端延迟 (P50) | < 50ms | Match Engine → 客户端收到 |
| 端到端延迟 (P99) | < 100ms | Match Engine → 客户端收到 |
| 消息丢失率 | < 0.001% | 含重试机制 |
| 有序性保证 | 100% | 单个用户订单事件严格有序 |
| 并发连接 | 100万+ | 单集群支持 |
| 消息吞吐量 | 10万 TPS | 单集群 |

---

## 8. 错误处理

### 8.1 服务端错误码

| 错误码 | 含义 | 处理建议 |
|--------|------|---------|
| 4001 | 认证失败 | 检查 JWT Token |
| 4002 | 订阅权限不足 | 检查用户权限 |
| 4003 | 消息格式错误 | 检查消息格式 |
| 4004 | 频率超限 | 降低订阅频率 |
| 4100 | 需要重连 | 客户端主动重连 |

### 8.2 客户端容错

```typescript
// 断线重连策略
class ReconnectStrategy {
  private retryCount = 0;
  private maxRetry = 10;
  private baseDelay = 1000; // 1s
  
  getRetryDelay(): number {
    // 指数退避
    const delay = this.baseDelay * Math.pow(2, this.retryCount);
    this.retryCount = Math.min(this.retryCount + 1, this.maxRetry);
    return Math.min(delay, 30000); // 最大30s
  }
  
  reset() {
    this.retryCount = 0;
  }
}
```

---

## 9. 附录

### 9.1 与 Binance 的差异对比

| 功能 | Binance | 本系统 |
|------|---------|--------|
| 订单推送 | executionReport | executionReport (兼容) |
| ACK 机制 | 无 | 有 (确保可靠性) |
| 快照恢复 | 提供 REST API | 提供 REST API + WebSocket 快照 |
| 消息有序 | 用户级别有序 | 用户级别有序 |

### 9.2 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| v1.0.0 | 2026-02-19 | 初始版本 |
| v1.1.0 | 2024-02-20 | 补充账户/持仓推送详细说明、序列号生成机制、快照恢复机制、消息去重机制、限流机制 |

---

*文档结束*
