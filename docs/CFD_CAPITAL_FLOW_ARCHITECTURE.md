# CFD合约交易资金流与定价架构分析

> 本文档详细分析当前CFD合约交易系统的资金冻结、双录账本、定价机制与行情标准化流程
>
> 最后更新：2026-03-07

---

## 📋 执行摘要

### 核心发现

✅ **已实现且正确**：
- 完整的双录分录账本系统（Ledger Core）
- 下单资金冻结/解冻机制
- CFD Dealer内存定价（DUAL模式）
- K线权威表与冲突检测
- 启动时历史K线回补

⚠️ **存在架构差距**：
1. **行情时基不统一**：CFD Dealer消费`market.ext.*`，Public Push可混用标准/ext通道
2. **Dealer定价双源**：OMS下单时仍从Redis pull参考价格
3. **Topic碎片化**：使用topic-per-symbol而非partition策略
4. **K线补齐非强制**：缺少watermark机制阻止乱序事件

### 优先级优化

| 优先级 | 改进项 | 影响 | 工作量 |
|--------|--------|------|--------|
| P0 | Dealer定价统一为内存OrderBook | 防止stale price拒单 | 2天 |
| P0 | 行情时基统一（所有服务消费market.*标准通道） | 消除时基不一致 | 3天 |
| P1 | K线补齐加入watermark机制 | 防止乱序覆盖 | 2天 |
| P2 | Topic优化为partition策略 | 降低运维复杂度 | 5天 |

---

## 🏗️ 当前架构详解

### 1. 下单资金冻结流程

#### 1.1 完整链路

```
Client (下单请求)
    ↓ POST /api/v1/oms/order/submit
    ↓ Headers: X-User-Id (Gateway透传)

┌─────────────────────────────────────────────────────────┐
│ OMS Core (8081) - submitOrder()                        │
│                                                         │
│ [1] 参数校验                                            │
│     ├─ userId, clientOrderId, symbol 必需               │
│     ├─ quantity > 0                                     │
│     └─ price > 0 (LIMIT) 或 null (MARKET)              │
│                                                         │
│ [2] 幂等性检查                                          │
│     ├─ 查询 t_idempotent_key 表                         │
│     ├─ 比对 request_hash (参数签名)                     │
│     └─ 如存在且hash匹配 → 返回缓存结果                   │
│                                                         │
│ [3] 创建订单记录                                        │
│     ├─ 生成 orderId (Snowflake)                         │
│     ├─ status = 0 (NEW)                                │
│     ├─ freeze_status = 0 (未冻结)                       │
│     └─ INSERT t_order + idempotent_key                 │
│                                                         │
│ [4] 硬风控检查 (TODO: 当前Mock)                         │
│     └─ 更新 status = 1 (PENDING_RISK)                  │
│                                                         │
│ [5] ⭐ 计算并冻结保证金                                  │
│     ├─ margin = (price × quantity) / leverage          │
│     ├─ Feign调用: ledgerClient.freezeMargin()          │
│     │   └─ 同步阻塞，等待Ledger返回                     │
│     ├─ 成功 → freeze_status = 1, status = 2 (FROZEN)   │
│     └─ 失败 → 抛出OmsException，订单rejected            │
│                                                         │
│ [6] 路由到执行引擎                                      │
│     ├─ CFD模式: cfd-order-command-{symbol}             │
│     └─ 标准模式: order-event-{symbol}                   │
└─────────────────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────────────────┐
│ Ledger Core (8084) - freezeMargin()                    │
│                                                         │
│ ⭐ 双录分录记账                                          │
│                                                         │
│ Entry 1 (USER_AVAILABLE):                              │
│   ├─ debit: 0                                          │
│   ├─ credit: amount (减少可用资金)                      │
│   ├─ balance_before: 10000.00000000                    │
│   ├─ balance_after: 9500.00000000                      │
│   ├─ business_type: MARGIN_FREEZE                      │
│   ├─ ref_order_id: {orderId}                           │
│   ├─ pair_entry_id: Entry2.id                          │
│   └─ idempotent_key: freeze_{orderId}                  │
│                                                         │
│ Entry 2 (USER_FROZEN):                                 │
│   ├─ debit: amount (增加冻结金额)                       │
│   ├─ credit: 0                                         │
│   ├─ balance_before: 0.00000000                        │
│   ├─ balance_after: 500.00000000                       │
│   ├─ business_type: MARGIN_FREEZE                      │
│   ├─ ref_order_id: {orderId}                           │
│   ├─ pair_entry_id: Entry1.id                          │
│   └─ idempotent_key: freeze_{orderId}                  │
│                                                         │
│ ✅ 保证：借贷平衡 (debit = credit)                      │
│ ✅ 保证：双向引用 (pair_entry_id)                       │
│ ✅ 保证：幂等性 (uk_idempotent)                         │
│                                                         │
│ [返回] FreezeResult.success()                          │
└─────────────────────────────────────────────────────────┘
```

#### 1.2 保证金计算逻辑

**文件**: `oms-core/src/main/java/com/exchange/oms/service/impl/OmsServiceImpl.java:781-799`

```java
/**
 * 计算所需保证金
 * @param price 价格 (8位小数long，如 5000000000000 = 50000 USDT)
 * @param quantity 数量 (8位小数long，如 1000000 = 0.01 BTC)
 * @param leverage 杠杆 (整数，如 10)
 * @return 保证金 (8位小数long)
 */
private long calculateRequiredMargin(long price, long quantity, int leverage) {
    // 转换为实际值
    double actualPrice = price / 100_000_000.0;      // 50000.0
    double actualQuantity = quantity / 100_000_000.0; // 0.01

    // 计算保证金
    double marginDouble = (actualPrice * actualQuantity) / leverage;
    // (50000 * 0.01) / 10 = 50 USDT

    // 转换回long格式
    return (long)(marginDouble * 100_000_000); // 5000000000 (50 USDT)
}
```

**示例**：
- 价格：50000 USDT
- 数量：0.1 BTC
- 杠杆：10x
- **所需保证金** = (50000 × 0.1) / 10 = **500 USDT**

#### 1.3 Ledger账户类型

| 账户类型 | 代码 | 用途 | 余额含义 |
|---------|------|------|---------|
| USER_AVAILABLE | 1 | 可用资金 | 可用于开新仓 |
| USER_FROZEN | 2 | 冻结保证金 | 挂单中锁定 |
| USER_POSITION_MARGIN | 3 | 持仓保证金 | 已开仓占用 |
| FEE_ACCOUNT | 11 | 手续费账户 | 系统收入 |
| INSURANCE_FUND | 12 | 保险基金 | 强平兜底 |
| SYSTEM_PNL | 13 | 系统盈亏 | CFD对手盘 |
| FUNDING_POOL | 14 | 资金费率池 | 费率结算 |
| LIQUIDATION_CLEARING | 15 | 强平清算 | 强平中转 |

---

### 2. 双录账本机制详解

#### 2.1 核心原则

```
1. Ledger = 唯一真实来源 (Single Source of Truth)
   - 所有资金变动必须通过Ledger记账
   - Snapshot服务只是派生视图，非权威数据

2. 双录分录必须满足
   - 每笔业务生成恰好2条配对的Entry
   - 借 (Debit) = 贷 (Credit)
   - pair_entry_id 双向引用

3. 幂等性保证
   - idempotent_key 唯一约束
   - Kafka消息重复消费安全

4. 可审计性
   - balance_before / balance_after 快照
   - biz_seq 全局递增序号 (用于回放)
```

#### 2.2 成交记账流程

**输入**: TradeEvent (从Match Engine或CFD Dealer)

```json
{
  "tradeId": "BTCUSDT-12345-1",
  "symbol": "BTCUSDT",
  "makerOrderId": "ORD_001",
  "takerOrderId": "ORD_002",
  "makerUserId": 10001,
  "takerUserId": 10002,
  "price": 5000000000000,        // 50000 USDT
  "quantity": 1000000,            // 0.01 BTC
  "makerFee": 500000,             // 0.005 USDT (maker fee)
  "takerFee": 1000000,            // 0.01 USDT (taker fee)
  "timestamp": 1704067200000
}
```

**记账逻辑** (`LedgerServiceImpl.applyTrade()`):

```
Maker (买入开多)
├─ Entry 1: USER_AVAILABLE
│   ├─ debit: 0
│   ├─ credit: margin + fee
│   └─ 说明: 扣除保证金和手续费
│
├─ Entry 2: USER_POSITION_MARGIN
│   ├─ debit: margin
│   ├─ credit: 0
│   └─ 说明: 增加持仓保证金
│
└─ Entry 3: FEE_ACCOUNT
    ├─ debit: fee
    ├─ credit: 0
    └─ 说明: 手续费入账

Taker (卖出开空)
├─ Entry 4: USER_AVAILABLE
│   ├─ debit: 0
│   ├─ credit: margin + fee
│   └─ 说明: 扣除保证金和手续费
│
├─ Entry 5: USER_POSITION_MARGIN
│   ├─ debit: margin
│   ├─ credit: 0
│   └─ 说明: 增加持仓保证金
│
└─ Entry 6: FEE_ACCOUNT
    ├─ debit: fee
    ├─ credit: 0
    └─ 说明: 手续费入账
```

**关键字段**:

```sql
CREATE TABLE t_ledger_entry (
    entry_id BIGINT PRIMARY KEY,           -- 分录ID
    user_id BIGINT,                        -- 用户ID
    account_type TINYINT,                  -- 账户类型 (1-15)
    debit BIGINT NOT NULL DEFAULT 0,       -- 借方金额 (增加)
    credit BIGINT NOT NULL DEFAULT 0,      -- 贷方金额 (减少)
    balance_before BIGINT,                 -- 变更前余额
    balance_after BIGINT,                  -- 变更后余额
    business_type TINYINT,                 -- 业务类型
    ref_trade_id VARCHAR(128),             -- 关联成交ID
    ref_order_id VARCHAR(128),             -- 关联订单ID
    pair_entry_id BIGINT,                  -- ⭐ 配对分录ID
    biz_seq BIGINT,                        -- ⭐ 全局业务序号
    idempotent_key VARCHAR(255),           -- ⭐ 幂等键
    created_at BIGINT,
    UNIQUE KEY uk_idempotent (idempotent_key)
);
```

#### 2.3 撤单解冻流程

```
Client 撤单请求
    ↓ POST /api/v1/oms/order/cancel

OMS Core
    ├─ [1] 乐观锁更新订单状态 (最多重试3次)
    │     └─ status = 5 (CANCELED)
    │
    ├─ [2] 计算解冻金额
    │     ├─ unfreezeAmount = (remainingQty / totalQty) × frozenMargin
    │     └─ 示例: 部分成交后取消，只解冻未成交部分
    │
    ├─ [3] 调用 Ledger 解冻
    │     └─ ledgerClient.unfreezeMargin(orderId, unfreezeAmount)
    │
    └─ [4] 更新订单
          └─ freeze_status = 2 (已解冻)

Ledger Core - unfreezeMargin()
    ├─ Entry 1 (USER_FROZEN):
    │   ├─ debit: 0
    │   ├─ credit: amount (减少冻结)
    │   └─ business_type: MARGIN_UNFREEZE
    │
    └─ Entry 2 (USER_AVAILABLE):
        ├─ debit: amount (增加可用)
        ├─ credit: 0
        └─ business_type: MARGIN_UNFREEZE
```

---

### 3. CFD Dealer定价机制

#### 3.1 架构概览

**当前模式**: DUAL (内存 + Redis)

```
┌──────────────────────────────────────────────────────┐
│  Binance Depth Feed (WebSocket)                      │
│  market.ext.binance.depth.BTCUSDT                    │
└───────────────────┬──────────────────────────────────┘
                    │
                    ▼
┌──────────────────────────────────────────────────────┐
│  CFD Dealer Core - ReferenceDepthConsumer            │
│                                                      │
│  [1] 解析Binance深度快照                             │
│      ├─ bids: [[price, qty], ...]                   │
│      ├─ asks: [[price, qty], ...]                   │
│      └─ 提取前20档 (configurable)                    │
│                                                      │
│  [2] 双写策略                                        │
│      ├─ 主路径: ReferenceBookStore.upsert()         │
│      │   └─ 内存ConcurrentHashMap                   │
│      └─ 备路径: Redis.set(cfd:reference:book:{s})   │
│                                                      │
│  [3] 触发LIMIT订单检查                               │
│      └─ limitWorkingOrderService.trigger()          │
└──────────────────────────────────────────────────────┘
                    │
        ┌───────────┴──────────┐
        │                      │
        ▼                      ▼
┌──────────────────┐  ┌──────────────────┐
│ Memory (Primary) │  │ Redis (Fallback) │
│ ReferenceBookStore│  │ TTL: 10s         │
│                  │  │ JSON格式         │
│ State: READY     │  │                  │
│ StaleMs: 150     │  │                  │
│ Levels: 20       │  │                  │
└──────────────────┘  └──────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────┐
│  ReferencePricingService - getReferenceBook()        │
│                                                      │
│  Mode: DUAL                                          │
│  ├─ [1] 尝试从内存获取                               │
│  │     └─ 校验: state=READY && stale<1500ms        │
│  │                                                  │
│  ├─ [2] 失败则从Redis获取                            │
│  │     └─ 校验: eventTime > now-1500ms             │
│  │                                                  │
│  └─ [3] 双源对比 (每200次)                           │
│        ├─ 比较价差 > 5bps → 告警                    │
│        └─ 记录日志用于监控                           │
└──────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────┐
│  MarketExecutionService - executeMarket()            │
│                                                      │
│  [1] 获取参考盘口                                    │
│  [2] 按价格档位吃单 (VWAP)                           │
│      ├─ BUY: 从asks[0]开始，按量吃单                │
│      └─ SELL: 从bids[0]开始，按量吃单               │
│  [3] 计算成交价格                                    │
│      └─ VWAP = Σ(price × qty) / Σ(qty)             │
│  [4] 生成TradeEvent                                 │
└──────────────────────────────────────────────────────┘
```

#### 3.2 存在的问题

**⚠️ OMS仍从Redis Pull价格**

**文件**: `oms-core/.../OmsServiceImpl.java:939-998`

```java
// CFD MARKET订单缺少价格时，OMS从Redis读取
private void applyCfdMarketReferencePrice(Order order) {
    String key = "cfd:reference:book:" + order.getSymbol();
    String json = redisTemplate.opsForValue().get(key);

    if (json == null) {
        throw new OmsException("Reference book unavailable");
    }

    ReferenceBook book = parseBook(json);

    // ⚠️ 问题：Redis可能stale
    long staleness = System.currentTimeMillis() - book.getEventTime();
    if (staleness > cfdReferenceMaxStaleMs) {
        throw new OmsException("Reference book too stale");
    }

    // 选择价格：BUY用ask，SELL用bid
    long price = order.getSide() == BUY ? book.getBestAsk() : book.getBestBid();
    order.setPrice(price);
}
```

**问题分析**：
1. OMS与Dealer分别读取定价源，可能不一致
2. Redis snapshot可能滞后
3. 增加Redis读取开销

**⭐ 推荐改进**：
```java
// OMS不再自行读价格，而是：
// 1. MARKET订单不设置price字段，传null
// 2. 发送到Dealer后，由Dealer从内存orderbook实时定价
// 3. Dealer返回成交结果，包含实际成交价格
```

---

### 4. 行情标准化流程

#### 4.1 当前数据流

```
┌─────────────────────────────────────────────────────┐
│  Binance WebSocket Feed                             │
└───────────────┬─────────────────────────────────────┘
                │
                ▼
┌─────────────────────────────────────────────────────┐
│  Kafka External Topics                              │
│  ├─ market.ext.binance.depth.{symbol}              │
│  ├─ market.ext.binance.trade.{symbol}              │
│  ├─ market.ext.binance.kline.{symbol}.{interval}   │
│  └─ market.ext.binance.ticker.{symbol}             │
└───────┬─────────────────────────────────────────────┘
        │
        │                    ┌─────────────────────────┐
        │                    │  Match Engine Core      │
        │                    │  (内部撮合)             │
        │                    └────────┬────────────────┘
        │                             │
        │                             ▼
        │                    ┌─────────────────────────┐
        │                    │  trade-event-{symbol}   │
        │                    │  order-state-{symbol}   │
        │                    └────────┬────────────────┘
        │                             │
        ├─────────────────────────────┤
        │                             │
        ▼                             ▼
┌─────────────────────────────────────────────────────┐
│  Market Price Core (8095)                           │
│  行情标准化层                                        │
│                                                     │
│  [输入]                                             │
│  ├─ ExternalMarketEventConsumer                    │
│  │   └─ 消费: market.ext.binance.*                │
│  └─ MatchEventConsumer                             │
│      └─ 消费: trade-event-*, market.depth.raw.*   │
│                                                     │
│  [处理]                                             │
│  ├─ 金额标准化 (8位小数)                            │
│  ├─ 时基统一 (毫秒时间戳)                           │
│  ├─ K线聚合 (1m基准)                                │
│  ├─ OrderBook维护 (20档深度)                        │
│  └─ 24h统计计算                                     │
│                                                     │
│  [输出]                                             │
│  ├─ market.trade.{symbol}                          │
│  ├─ market.kline.{symbol}.{interval}               │
│  ├─ market.depth.{symbol}                          │
│  ├─ market.ticker.{symbol}                         │
│  └─ Redis Snapshot (for push初始化)                │
└───────┬─────────────────────────────────────────────┘
        │
        ├────────────────────────────┬────────────────┐
        │                            │                │
        ▼                            ▼                ▼
┌──────────────┐          ┌──────────────┐  ┌──────────────┐
│ Public Push  │          │ CFD Dealer   │  │ Snapshot     │
│ (标准通道)   │          │ ⚠️ 消费ext   │  │ Services     │
└──────────────┘          └──────────────┘  └──────────────┘
```

#### 4.2 问题：时基不一致

**⚠️ 现象**：

```
情况1: K线与盘口时基不同源
├─ Public Push K线: 来自 market.kline.BTCUSDT.1m
│   └─ 源: Market Price Core聚合 (延迟~50ms)
│
└─ CFD Dealer盘口: 来自 market.ext.binance.depth.BTCUSDT
    └─ 源: Binance直连 (延迟~10ms)

结果: K线时间点 T 的价格与盘口不一致

情况2: Public Push混用
├─ 配置: ext-channel-enabled=true
├─ 客户端可订阅: kline.ext.binance.BTCUSDT.1m
└─ 不同客户端看到不同数据 (ext vs standard)
```

**⭐ 目标架构要求**：

```
所有下游服务必须消费统一的标准化通道：
├─ market.trade.{symbol}
├─ market.kline.{symbol}.{interval}
├─ market.depth.{symbol}
└─ market.ticker.{symbol}

禁止：
❌ CFD Dealer直接消费market.ext.*
❌ Public Push混用ext和standard
❌ 不同服务从不同源获取同一数据
```

---

### 5. K线处理机制

#### 5.1 实时K线聚合

```
成交流 (trade-event-BTCUSDT)
    ↓
KlineEngineWithStorage.onTrade()
    │
    ├─ [1] 计算开盘时间
    │     └─ openTime = (timestamp / 60000) * 60000
    │
    ├─ [2] 更新1m K线
    │     ├─ open: 首笔价格
    │     ├─ close: 最新价格
    │     ├─ high: max(high, price)
    │     ├─ low: min(low, price)
    │     ├─ volume: += qty
    │     └─ tradeCount++
    │
    ├─ [3] 检测收线
    │     └─ 下一笔成交openTime变化 → 前一根K线closed=true
    │
    ├─ [4] 发布K线事件
    │     └─ market.kline.BTCUSDT.1m (带closed标记)
    │
    └─ [5] ClickHouse异步持久化
```

#### 5.2 启动回补机制

**文件**: `market-price-core/.../BinanceKlineBackfillJob.java`

```
@EventListener(ApplicationReadyEvent.class)
public void onApplicationReady() {
    if (!backfillEnabled) return;

    // 延迟启动 (默认5秒)
    Thread.sleep(startupDelayMs);

    for (String symbol : symbols) {
        try {
            backfillSymbol(symbol);
        } catch (Exception e) {
            // 重试机制 (最多3次)
            retryBackfill(symbol);
        }
    }
}

private void backfillSymbol(String symbol) {
    // [1] 查询现有数据范围
    Long earliest = authorityRepo.findEarliestOpenTime(symbol, "1m");
    Long latest = authorityRepo.findLatestOpenTime(symbol, "1m");

    // [2] 双向回补
    //   Backward: [now-365d, earliest)
    //   Forward:  (latest, now]

    long backfillStart = alignToInterval(now() - 365*86400000L, 60000);

    // 向后补
    backfillRange(symbol, backfillStart, earliest);

    // 向前补
    backfillRange(symbol, latest, now());

    // [3] 更新watermark
    if (backfillWithWatermark) {
        upsertWatermark(symbol, "1m", latestClosedOpenTime);
    }
}

private void backfillRange(String symbol, long start, long end) {
    long cursor = start;

    while (cursor < end) {
        // REST API: /fapi/v1/klines
        List<Kline> batch = binanceClient.getKlines(
            symbol, "1m", cursor, cursor + 1500*60000, 1500
        );

        if (batch.isEmpty()) break;

        // 过滤：只导入已收线的K线
        List<Kline> closed = batch.stream()
            .filter(k -> k.getCloseTime() < now())
            .collect(Collectors.toList());

        // 持久化
        authorityService.saveBackfillBatch(closed);
        klineService.saveKlines(closed);

        // 推进游标
        cursor = batch.get(batch.size()-1).getOpenTime() + 60000;

        Thread.sleep(requestDelayMs); // 限速
    }
}
```

#### 5.3 权威表与去重

**表结构**: `t_kline_authority`

```sql
CREATE TABLE t_kline_authority (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source VARCHAR(32) NOT NULL,           -- binance
    symbol VARCHAR(32) NOT NULL,           -- BTCUSDT
    interval_val VARCHAR(8) NOT NULL,      -- 1m, 5m, ...
    open_time BIGINT NOT NULL,             -- K线开盘时间
    close_time BIGINT NOT NULL,            -- K线收盘时间

    open_price BIGINT,
    high_price BIGINT,
    low_price BIGINT,
    close_price BIGINT,
    volume BIGINT,

    is_closed BOOLEAN DEFAULT false,       -- 收线标记
    payload_hash VARCHAR(64),              -- ⭐ 内容哈希

    first_seen_at BIGINT,                  -- 首次收到时间
    last_seen_at BIGINT,                   -- 最后收到时间

    UNIQUE KEY uk_source_symbol_interval_open
        (source, symbol, interval_val, open_time)
);
```

**去重逻辑**:

```
收到K线数据 (candleClosed=true)
    ↓
计算 payload_hash = SHA256(OHLCV + volume + tradeCount)
    ↓
INSERT ON DUPLICATE KEY UPDATE
    ├─ CASE 1: 新记录
    │   └─ INSERT成功 → AuthorityWriteResult.INSERTED
    │
    ├─ CASE 2: hash相同
    │   └─ UPDATE last_seen_at → DUPLICATE (忽略)
    │
    └─ CASE 3: hash不同 ⚠️
        ├─ 记录冲突到 t_kline_conflict 表
        ├─ conflictCount++
        ├─ 日志告警
        └─ 返回 CONFLICT (丢弃新数据)
```

**冲突场景**：
```
时间轴:
T=0:    收到 BTCUSDT 1m [1000-1060), closed=true
        OHLCV = [50000, 50100, 49900, 50050]
        保存到authority表，hash=ABC123

T=5s:   再次收到同一根K线，但数据变化
        OHLCV = [50000, 50100, 49900, 50051]  // close价格变了
        hash=DEF456 ≠ ABC123

处理:
1. 检测到冲突
2. 记录到t_kline_conflict:
   - original_hash: ABC123
   - conflicting_hash: DEF456
   - diff_fields: ["close_price"]
3. 告警日志
4. 丢弃新数据（保留首次收线版本）
```

#### 5.4 ⚠️ 缺失：Watermark机制

**当前问题**：
```
启动回补完成 → 恢复实时消费
    ↓
Kafka Consumer从offset恢复
    ↓
可能收到回补期间的旧消息
    ↓
⚠️ 如果旧消息event_time在回补范围内
    → 可能覆盖已回补的正确数据
```

**推荐改进**：

```java
// 回补完成后设置watermark
long watermark = latestBackfilledOpenTime;
watermarkService.set(symbol, interval, watermark);

// 实时消费时检查
@KafkaListener(topics = "market.ext.binance.kline.*")
public void onKlineEvent(KlineEvent event) {
    long watermark = watermarkService.get(event.getSymbol(), event.getInterval());

    // ⭐ 丢弃watermark之前的事件
    if (event.getOpenTime() <= watermark) {
        log.debug("Discard stale event: {} <= watermark {}",
            event.getOpenTime(), watermark);
        return;
    }

    // 正常处理
    processKline(event);
}
```

---

## 🔍 差距分析与优化建议

### Priority 0: Dealer定价统一

**问题**：
- OMS在下单时从Redis读取参考价格 (`applyCfdMarketReferencePrice`)
- Dealer自己维护内存orderbook
- 两个来源可能不同步

**影响**：
- 价格不一致导致用户体验差
- Redis stale可能导致拒单
- 增加Redis依赖

**解决方案**：

```
[改进前]
Client → OMS (读Redis定价) → Dealer (内存定价) → 成交

[改进后]
Client → OMS (不定价) → Dealer (唯一定价源) → 成交
```

**实施步骤**：
1. 修改OMS：MARKET订单不调用`applyCfdMarketReferencePrice`
2. 订单price字段传null给Dealer
3. Dealer从内存orderbook实时定价
4. 成交事件包含实际成交价格
5. 删除`applyCfdMarketReferencePrice`方法

**工作量**: 2天

---

### Priority 0: 行情时基统一

**问题**：
- CFD Dealer消费`market.ext.binance.depth.*`
- Public Push消费`market.depth.*`
- 两者时基不同

**影响**：
- K线与盘口时间不一致
- 客户端看到的数据有时间差
- 回测和分析困难

**解决方案**：

```
[改进前]
Binance → market.ext.* → CFD Dealer (直连)
                      ↓
                Market Price Core → market.* → Public Push

[改进后]
Binance → market.ext.* → Market Price Core → market.*
                                           ↓
                               ├─ CFD Dealer
                               ├─ Public Push
                               └─ 所有下游服务
```

**实施步骤**：

1. **CFD Dealer改造**：
   ```yaml
   # cfd-dealer-core配置
   pricing:
     source-topic: market.depth.{symbol}  # 改为标准通道
     # 删除: market.ext.binance.depth.*
   ```

2. **Market Price Core增强**：
   - 确保`market.depth.*`输出及时性
   - 延迟目标：< 50ms (P99)
   - 增加监控指标

3. **Public Push统一**：
   ```yaml
   # public-push-core配置
   ext-channel-enabled: false  # 禁用ext通道
   ```

4. **验证**：
   - 同一时刻K线close价格 = 盘口mid价格 (误差<0.01%)
   - 成交事件时间戳与K线时间对齐

**工作量**: 3天

---

### Priority 1: K线Watermark机制

**问题**：
- 启动回补后恢复实时消费
- 旧消息可能覆盖回补数据
- 无机制阻止乱序事件

**解决方案**：

```sql
-- 新增watermark表
CREATE TABLE t_kline_watermark (
    symbol VARCHAR(32) NOT NULL,
    interval_val VARCHAR(8) NOT NULL,
    watermark_open_time BIGINT NOT NULL,  -- 已处理到的最大openTime
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (symbol, interval_val)
);
```

**实施步骤**：

1. **回补时更新watermark**：
   ```java
   // BinanceKlineBackfillJob
   public void backfillSymbol(String symbol) {
       // ... 回补逻辑 ...

       // 回补完成后设置watermark
       long maxOpenTime = getLatestBackfilledOpenTime(symbol, "1m");
       watermarkRepo.upsert(symbol, "1m", maxOpenTime);
   }
   ```

2. **实时消费时检查**：
   ```java
   // ExternalMarketEventConsumer
   @KafkaListener(topics = "market.ext.binance.kline.*")
   public void onKlineEvent(KlineEvent event) {
       Long watermark = watermarkRepo.get(event.getSymbol(), event.getInterval());

       if (watermark != null && event.getOpenTime() <= watermark) {
           log.debug("Discard stale kline: openTime={} <= watermark={}",
               event.getOpenTime(), watermark);
           return;
       }

       // 正常处理
       processKline(event);

       // 更新watermark
       if (event.isClosed()) {
           watermarkRepo.updateIfGreater(
               event.getSymbol(),
               event.getInterval(),
               event.getOpenTime()
           );
       }
   }
   ```

3. **监控**：
   - watermark滞后告警 (> 5分钟)
   - 丢弃事件计数

**工作量**: 2天

---

### Priority 2: Topic优化

**问题**：
- 当前：`trade-event-BTCUSDT`, `trade-event-ETHUSDT`, ...
- 管理复杂：每个symbol一个topic
- Kafka集群负担：1000+ topics

**解决方案**：

```
[改进前]
trade-event-BTCUSDT (1 partition)
trade-event-ETHUSDT (1 partition)
... (50+ topics)

[改进后]
trade-event (50 partitions)
  ├─ partition key: symbol
  └─ 自动负载均衡
```

**实施步骤**：

1. **创建新topic**：
   ```bash
   kafka-topics --create \
     --topic trade-event \
     --partitions 50 \
     --replication-factor 3
   ```

2. **生产者改造**：
   ```java
   // Match Engine / CFD Dealer
   kafkaTemplate.send(
       "trade-event",           // 单topic
       event.getSymbol(),       // partition key
       event
   );
   ```

3. **消费者改造**：
   ```java
   @KafkaListener(
       topics = "trade-event",   // 不再用topicPattern
       groupId = "ledger-service"
   )
   public void onTrade(TradeEvent event) {
       // 无需改动，symbol从event中获取
   }
   ```

4. **灰度迁移**：
   - Week 1-2: 双写 (旧topic + 新topic)
   - Week 3: 消费者切换到新topic
   - Week 4: 停止旧topic生产
   - Week 5: 删除旧topics

**收益**：
- 减少topic数量：50+ → 5个
- 简化运维
- 更好的负载均衡

**工作量**: 5天

---

## 📊 资金流转完整状态图

```
用户资金生命周期
═══════════════════════════════════════════════

初始状态: 充值入金
┌────────────────────────────────────┐
│ USER_AVAILABLE: 10000 USDT         │
│ USER_FROZEN: 0                     │
│ USER_POSITION_MARGIN: 0            │
└────────────────────────────────────┘

事件1: 下LIMIT单 (BUY 0.1 BTC @ 50000, 10x)
所需保证金: 500 USDT
    ↓ freezeMargin()
┌────────────────────────────────────┐
│ USER_AVAILABLE: 9500 USDT  (↓500) │
│ USER_FROZEN: 500 USDT      (↑500) │
│ USER_POSITION_MARGIN: 0            │
└────────────────────────────────────┘
Ledger Entries:
  - Entry 1: AVAILABLE credit 500
  - Entry 2: FROZEN debit 500

事件2: 部分成交 (0.05 BTC @ 50000)
实际保证金: 250 USDT
手续费: 2.5 USDT (0.1% taker)
    ↓ applyTrade()
┌────────────────────────────────────┐
│ USER_AVAILABLE: 9500 USDT          │
│ USER_FROZEN: 250 USDT      (↓250) │  // 释放已成交部分
│ USER_POSITION_MARGIN: 250  (↑250) │  // 进入持仓
│                                    │
│ 手续费已扣: -2.5 USDT              │
└────────────────────────────────────┘
Ledger Entries:
  - Entry 3: FROZEN credit 250
  - Entry 4: POSITION_MARGIN debit 250
  - Entry 5: AVAILABLE credit 2.5 (fee)
  - Entry 6: FEE_ACCOUNT debit 2.5

事件3: 取消剩余订单
剩余保证金: 250 USDT
    ↓ unfreezeMargin()
┌────────────────────────────────────┐
│ USER_AVAILABLE: 9747.5 USDT (↑250-2.5)│
│ USER_FROZEN: 0             (↓250) │
│ USER_POSITION_MARGIN: 250          │
└────────────────────────────────────┘
Ledger Entries:
  - Entry 7: FROZEN credit 250
  - Entry 8: AVAILABLE debit 250

事件4: 持仓盈利平仓 (SELL 0.05 BTC @ 52000)
盈利: (52000 - 50000) × 0.05 = 100 USDT
释放保证金: 250 USDT
手续费: 2.6 USDT (0.1% taker)
    ↓ applyTrade()
┌────────────────────────────────────┐
│ USER_AVAILABLE: 10094.9 USDT       │
│   = 9747.5 + 250 + 100 - 2.6       │
│ USER_FROZEN: 0                     │
│ USER_POSITION_MARGIN: 0    (↓250) │
└────────────────────────────────────┘
Ledger Entries:
  - Entry 9: POSITION_MARGIN credit 250
  - Entry 10: AVAILABLE debit 250 (释放保证金)
  - Entry 11: SYSTEM_PNL credit 100 (CFD亏损)
  - Entry 12: AVAILABLE debit 100 (用户盈利)
  - Entry 13: AVAILABLE credit 2.6 (fee)
  - Entry 14: FEE_ACCOUNT debit 2.6

最终状态:
┌────────────────────────────────────┐
│ USER_AVAILABLE: 10094.9 USDT       │
│ 盈利: 94.9 USDT                    │
│   = 100 (持仓盈利) - 5.1 (手续费)  │
└────────────────────────────────────┘
```

---

## 🛠️ 实施路径

### Phase 1: 紧急修复 (1周)

**目标**: 修复Dealer定价和行情时基不一致

| 任务 | 负责人 | 工作量 | 验收标准 |
|------|--------|--------|---------|
| OMS移除Redis定价 | 后端A | 1天 | MARKET单不再读Redis |
| Dealer改为消费market.* | 后端B | 2天 | 消费标准通道，延迟<50ms |
| Public Push禁用ext通道 | 后端C | 0.5天 | 配置关闭ext-channel-enabled |
| 集成测试 | QA | 1天 | K线与盘口时基一致 |

### Phase 2: 稳定性增强 (1周)

**目标**: K线watermark和对账

| 任务 | 负责人 | 工作量 | 验收标准 |
|------|--------|--------|---------|
| Watermark表和逻辑 | 后端A | 1.5天 | 启动回补后阻止旧事件 |
| KlineReconcileJob增强 | 后端B | 1天 | 自动触发小窗口回补 |
| 监控告警 | 运维 | 0.5天 | watermark滞后>5min告警 |
| 压力测试 | QA | 1天 | 重启恢复无数据丢失 |

### Phase 3: 架构优化 (2周)

**目标**: Topic合并和性能优化

| 任务 | 负责人 | 工作量 | 验收标准 |
|------|--------|--------|---------|
| 新topic规划 | 架构师 | 1天 | 设计文档评审通过 |
| 生产者改造 | 后端团队 | 3天 | 双写验证 |
| 消费者改造 | 后端团队 | 3天 | 灰度切流 |
| 性能测试 | QA | 2天 | TPS无衰减 |
| 清理旧topic | 运维 | 1天 | 删除50+旧topics |

---

## 📈 监控指标

### Dealer定价

```
# 内存orderbook新鲜度
dealer_reference_book_staleness_ms{symbol="BTCUSDT"} < 500

# Redis fallback次数
dealer_redis_fallback_total{symbol="BTCUSDT"} < 10/min

# 定价失败率
dealer_pricing_failure_rate < 0.1%
```

### 行情标准化

```
# 标准化延迟
market_price_core_latency_p99_ms < 50

# K线收线延迟
kline_close_delay_seconds < 2

# 冲突检测
kline_conflict_total{symbol="BTCUSDT"} == 0
```

### Ledger账本

```
# 分录配对完整性
ledger_entry_orphan_total == 0

# 借贷平衡检查
ledger_debit_credit_mismatch == 0

# 幂等重试次数
ledger_idempotent_retry_total < 5/min
```

---

## 📚 附录

### A. 关键文件路径

| 功能 | 文件路径 |
|------|---------|
| OMS下单 | `oms-core/src/main/java/com/exchange/oms/service/impl/OmsServiceImpl.java` |
| Ledger记账 | `ledger-core/src/main/java/com/exchange/ledger/service/impl/LedgerServiceImpl.java` |
| CFD定价 | `cfd-dealer-core/src/main/java/com/exchange/cfddealer/service/impl/ReferencePricingServiceImpl.java` |
| 行情标准化 | `market-price-core/src/main/java/com/exchange/market/consumer/ExternalMarketEventConsumer.java` |
| K线回补 | `market-price-core/src/main/java/com/exchange/market/job/BinanceKlineBackfillJob.java` |

### B. 配置模板

```yaml
# oms-core配置
oms:
  cfd:
    execution-mode: CFD_DEALER        # CFD模式
    market-price-from-redis: false    # ⚠️ 改为false

# cfd-dealer-core配置
cfd:
  dealer:
    pricing-mode: MEMORY              # 生产环境推荐MEMORY
    reference:
      source-topic: market.depth.{symbol}  # ⭐ 改为标准通道
      max-stale-ms: 1500
      min-depth-levels: 5

# market-price-core配置
market-data:
  kline:
    authority-enabled: true
    backfill-with-watermark: true     # ⭐ 启用watermark
    reconcile:
      auto-repair: true               # 自动修复gap

# public-push-core配置
public-push:
  ext-channel-enabled: false          # ⭐ 禁用ext通道
```

### C. 术语表

| 术语 | 英文 | 说明 |
|------|------|------|
| 双录分录 | Double-Entry Accounting | 每笔业务生成配对的借贷记录 |
| 幂等性 | Idempotency | 重复操作结果不变 |
| 时基 | Time Base | 数据的时间参考点 |
| Watermark | Watermark | 已处理事件的时间边界 |
| VWAP | Volume Weighted Average Price | 成交量加权平均价 |
| Stale | Stale | 数据过时 |

---

*最后更新：2026-03-07*
*文档维护：架构组*
