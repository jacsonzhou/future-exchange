# OMS 下限价单全链路代码层 Review 报告

> 范围：OMS → Hard-Risk → Match-Engine → Ledger → Snapshot → Position → OMS 状态回传 → Private Push
> 时间：2026-04-19

---

## 1. 链路总览

```
Client → API Gateway → OmsController.submitOrder()
                              │
                              ▼
                    ┌─────────────────────┐
                    │  OmsServiceImpl     │
                    │  1. 幂等检查        │
                    │  2. 创建订单(NEW)   │
                    │  3. 硬风控检查      │ ──Feign──→ HardRiskServiceImpl
                    │  4. 冻结保证金      │ ──Feign──→ LedgerServiceImpl.freezeMargin
                    │  5. 发Kafka         │ ──Kafka──→ order-event-{symbol}
                    └─────────────────────┘
                              │
                              ▼
                    ┌─────────────────────┐
                    │  MatchEngineCore    │
                    │  DisruptorEngine    │
                    │  OrderBook.addOrder │
                    │  ├─ 撮合成交 → Trade│
                    │  └─ 未成交 → 挂单    │
                    └─────────────────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
        trade-event   order-state-{symbol}  depth update
              │               │
              ▼               ▼
    ┌─────────────────┐ ┌─────────────────┐
    │ LedgerService   │ │ OrderStateConsumer│
    │ applyTrade()    │ │ 更新OMS订单状态   │
    │ 双录分录        │ │ 释放成交保证金    │
    │ trade-entry-*   │ │ Private Push      │
    └─────────────────┘ └─────────────────┘
              │
              ▼
    ┌─────────────────┐ ┌─────────────────┐
    │ snapshot-account│ │ position-snapshot│
    │ 更新余额快照    │ │ 更新持仓快照     │
    └─────────────────┘ └─────────────────┘
```

---

## 2. 🔴 Critical 级别问题（资金损失/系统故障风险）

### 2.1 OMS 撤单时保证金永不解冻（资金泄漏）

**文件**: `oms-core/src/main/java/com/exchange/oms/service/impl/OmsServiceImpl.java`

**问题描述**:
- 下单时创建订单：`order.setFreezeStatus(0)` (第121行)
- 风控通过后：`updateOrderStatus(order, 2, "FROZEN", ...)` 只更新了 `status` 字段为 2，**从未更新 `freezeStatus` 字段**
- 撤单时检查：`if (order.getFreezeStatus() != null && order.getFreezeStatus() == 1)` (第248行)
- `freezeStatus` 永远是 0，所以 `ledgerClient.unfreezeMargin()` **永远不会被调用**

**影响**: 用户撤单后，保证金被永久冻结在 Ledger 中，无法释放。

**修复建议**:
```java
// 冻结成功后
order.setFreezeStatus(1);
orderMapper.updateById(order); // 或统一在 updateOrderStatus 中处理
```

---

### 2.2 OMS 异常回滚时解冻金额为 0（资金泄漏）

**文件**: `oms-core/src/main/java/com/exchange/oms/service/impl/OmsServiceImpl.java:621-636`

**问题描述**:
- `rollbackLedgerFreeze` 方法创建 `UnfreezeRequest` 时，**没有设置 `amount` 字段**
- `LedgerServiceImpl.unfreezeMargin` 中 `normalizeAmount(null)` 返回 `BigDecimal.ZERO`
- 异常发生时（如 Kafka 发送失败），实际解冻金额为 0，保证金没有释放

**修复建议**:
```java
private void rollbackLedgerFreeze(Long orderId, Long userId, BigDecimal amount) {
    // ... 设置 amount
    unfreezeRequest.setAmount(amount);
}
```

---

### 2.3 OrderStateConsumer 释放保证金精度完全错误

**文件**: `oms-core/src/main/java/com/exchange/oms/consumer/OrderStateConsumer.java:440-451`

**问题描述**:
```java
private BigDecimal calculateRequiredMargin(BigDecimal price, BigDecimal quantity, int leverage) {
    BigDecimal actualPrice = price.divide(SCALE_BD, 8, RoundingMode.HALF_UP);   // ❌
    BigDecimal actualQuantity = quantity.divide(SCALE_BD, 8, RoundingMode.HALF_UP); // ❌
    return actualPrice.multiply(actualQuantity).divide(BigDecimal.valueOf(leverage), 8, RoundingMode.HALF_UP);
}
```
- `OmsOrder` 的 `price` 和 `quantity` 字段存储的是**实际金额**（如 50000.00, 0.1），未经过 1e8 缩放
- 但 `SCALE_BD = 100_000_000L`
- 计算时将实际金额除以 1e8，导致 `actualPrice = 0.0005`, `actualQuantity = 0.000000001`
- 释放的保证金几乎为 0，用户资金被大量错误冻结

**修复建议**:
```java
// OmsOrder 的 price/quantity 是实际金额，不需要除以 SCALE
private BigDecimal calculateRequiredMargin(BigDecimal price, BigDecimal quantity, int leverage) {
    return price.multiply(quantity).divide(BigDecimal.valueOf(leverage), 8, RoundingMode.HALF_UP);
}
```

---

### 2.4 Ledger 分录不支持双向持仓开空仓

**文件**: `ledger-core/src/main/java/com/exchange/ledger/service/impl/LedgerServiceImpl.java:454-547`

**问题描述**:
- `generateTradeEntries` 中，Maker 卖出时的分录：
  ```java
  // Maker卖出
  entries.add(createEntry(makerUserId, USER_POSITION_MARGIN, 0, marginAmount, ...)); // 持仓减少
  entries.add(createEntry(makerUserId, USER_AVAILABLE, marginAmount, 0, ...));       // 可用增加
  ```
- 这意味着 **SELL 被永远视为"平仓"**（释放持仓保证金到可用余额）
- 在 Hedge Mode（双向持仓）下，用户卖出开仓（开空仓）时，应该是可用余额 → 持仓保证金（与买入开仓相同方向）
- `PositionServiceImpl` 已支持 Hedge Mode，但 `LedgerServiceImpl` 的分录逻辑仍基于 Net Mode

**影响**: 开空仓时 Ledger 分录方向错误，导致可用余额增加、持仓保证金减少，与真实持仓变动方向相反。

**修复建议**: 分录生成需要结合用户的持仓方向和订单意图（开仓/平仓），不能仅根据 Buy/Sell 简单判断。

---

### 2.5 OMS 存在两条并行下单路径（数据不一致）

**文件**: 
- `oms-core/src/main/java/com/exchange/oms/service/impl/OmsServiceImpl.java`
- `oms-core/src/main/java/com/exchange/oms/service/impl/OrderServiceImpl.java`

**问题描述**:
- 路径A：`OmsController.submitOrder()` → `OmsServiceImpl.submitOrder()`
  - 使用 `OmsOrder` 表（字段：id, user_id, symbol, side, type, price, quantity, status...）
  - 直接调用 `LedgerClient.freezeMargin()`
  - 使用 `OrderEventCommand` → Kafka
- 路径B：`OrderServiceImpl.createOrder()`
  - 使用 `Order` 表（字段：order_id, user_id, symbol, side, order_type, price, quantity, status...）
  - 使用 `MarginPreHoldService.preHold()`（Redis 预扣）
  - 使用 `OrderCommand` → Kafka/Feign 双通道
  - 支持 CFD 路由

**影响**: 
- 两个独立的订单表，数据可能不一致
- 两种不同的资金冻结机制（Ledger 直接冻结 vs Redis 预扣）
- 路径B 支持 `executionMode`（CFD_DEALER / MATCH_ENGINE），路径A 完全不支持
- 强平/ADL 订单调用 `OrderServiceImpl`，但普通用户下单走 `OmsServiceImpl`

**修复建议**: 统一为单一路径，废弃 `Order` 表或 `OmsOrder` 表其中一个，统一资金冻结机制。

---

## 3. 🟠 High 级别问题（功能性缺陷）

### 3.1 OMS 下单时未设置 leverage 字段

**文件**: `oms-core/src/main/java/com/exchange/oms/service/impl/OmsServiceImpl.java:107-124`

**问题描述**:
- 创建 `OmsOrder` 时，没有调用 `order.setLeverage(request.getLeverage())`
- 后续 `OrderStateConsumer.releaseFrozenMarginForFill` 使用 `resolveLeverage(order.getLeverage())`，返回默认值 10
- 如果用户选择 20 倍杠杆，实际按 10 倍计算释放保证金，导致释放金额错误

---

### 3.2 HardRisk ReduceOnly 逻辑与 Hedge Mode 冲突

**文件**: `hard-risk-core/src/main/java/com/exchange/risk/service/impl/HardRiskServiceImpl.java:178-192`

**问题描述**:
```java
// ReduceOnly: BUY只能平空仓，SELL只能平多仓
if ((isBuy && isLongPosition) || (!isBuy && !isLongPosition)) {
    return reject(RiskRejectReason.REDUCE_ONLY_VIOLATION, ...);
}
```
- 这是 Net Mode（单向持仓）下的 ReduceOnly 逻辑
- 在 Hedge Mode 下，用户可以同时持有多头和空头。BUY 增加 LONG 持仓是合法操作（当 ReduceOnly=false 时）
- 当前逻辑在 `ReduceOnly=false` 时不会触发，但当用户持有多仓时，ReduceOnly=true 的 BUY 单应该允许（如果目的是平空仓）
- 实际上，Hedge Mode 下 ReduceOnly 应该限制的是**净持仓方向**，而不是简单地根据现有持仓 reject

---

### 3.3 TradePublisher 使用 double 转换导致精度丢失

**文件**: `match-engine-core/src/main/java/com/exchange/match/publisher/TradePublisher.java:207-217`

**问题描述**:
```java
event.put("price", Money.of(trade.getPrice().doubleValue()));
event.put("quantity", Money.of(trade.getQuantity().doubleValue()));
```
- `BigDecimal.doubleValue()` 存在精度丢失风险
- 例如 `50000.00000001` 可能变成 `50000.0`，或 `49999.99999999`
- `Money.of(double)` 内部通常是 `(long)(value * SCALE)`，double 精度问题导致错误的 long 值

**修复建议**:
```java
event.put("price", trade.getPrice().multiply(BigDecimal.valueOf(Money.SCALE)).longValue());
```

---

### 3.4 MatchEngine.normalizeFromCommand 格式判断不可靠

**文件**: `match-engine-core/src/main/java/com/exchange/match/engine/MatchEngine.java:92-98`

**问题描述**:
```java
private BigDecimal normalizeFromCommand(String raw) {
    BigDecimal value = new BigDecimal(raw);
    if (raw.indexOf('.') < 0 && value.abs().compareTo(MONEY_SCALE) >= 0) {
        return value.divide(MONEY_SCALE, 8, RoundingMode.HALF_UP);
    }
    return value;
}
```
- 判断逻辑：不含小数点且绝对值 >= 1e8 → 认为是缩放后的值
- 如果实际金额恰好是整数且 >= 1e8（如 100000000 = 1 BTC），会被错误地除以 1e8 变成 1
- 这是典型的"魔法数字"判断，不可靠

---

### 3.5 OMS 撤单时硬编码默认杠杆 10 倍

**文件**: `oms-core/src/main/java/com/exchange/oms/service/impl/OmsServiceImpl.java:251-255`

**问题描述**:
```java
BigDecimal unfreezeAmount = calculateRequiredMargin(
    order.getPrice(),
    order.getRemainingQuantity(),
    10 // 默认10倍杠杆，实际应该从订单中读取
);
```
- 撤单释放保证金时硬编码 10 倍杠杆
- 如果用户实际杠杆不是 10 倍，释放金额错误

---

## 4. 🟡 Medium 级别问题（设计/健壮性）

### 4.1 MatchEventHandler.publishOrderState NPE 风险

**文件**: `match-engine-core/src/main/java/com/exchange/match/disruptor/MatchEventHandler.java:142-167`

**问题描述**:
```java
BigDecimal originalQty = (command.getQuantity() != null)
    ? new BigDecimal(command.getQuantity()) : BigDecimal.ZERO;
```
- 撤单命令 (`ORDER_CANCEL`) 的 `quantity` 字段可能为 null
- `new BigDecimal(null)` 会抛出 NPE
- 虽然有 try-catch，但会导致 OMS 收不到撤单状态回传

---

### 4.2 LedgerServiceImpl 跨月余额计算风险

**文件**: `ledger-core/src/main/java/com/exchange/ledger/service/impl/LedgerServiceImpl.java:724-735`

**问题描述**:
- `getAvailableBalanceFromLedger` 只查询当前月份的 `ledger_entry_YYYYMM` 表
- 如果用户在月底下单，月初余额计算会漏掉上个月的记录
- 冻结/解冻操作分月存储，跨月时余额计算不准确

---

### 4.3 OMS `confirmSubmitByClientOrderId` 返回 null（未实现）

**文件**: `oms-core/src/main/java/com/exchange/oms/service/impl/OmsServiceImpl.java:217-220`

**问题描述**:
- 网关降级幂等确认接口直接返回 `null`
- 如果 OMS 服务重启或网络抖动，网关无法确认订单状态

---

### 4.4 OMS `queryOrderList` 返回 null（未实现）

**文件**: `oms-core/src/main/java/com/exchange/oms/service/impl/OmsServiceImpl.java:415-418`

**问题描述**:
- 订单列表查询接口返回 `null`，会导致 NPE

---

### 4.5 OrderBook 的 getDepthData 每次创建新 ArrayList 排序

**文件**: `match-engine-core/src/main/java/com/exchange/match/orderbook/OrderBook.java:492-541`

**问题描述**:
- 每次查询深度都要对价格键做 `new ArrayList<>(bidBook.keySet())` 和 `sort()`
- 高并发查询时 GC 压力和 CPU 开销大
- 可以考虑维护有序的 TreeMap 或缓存排序结果

---

### 4.6 Kafka 异步发送失败仅记录日志，无重试/死信队列

**文件**: 
- `oms-core/src/main/java/com/exchange/oms/publisher/OrderEventPublisher.java`
- `match-engine-core/src/main/java/com/exchange/match/publisher/TradePublisher.java`

**问题描述**:
- 所有 Kafka 发送失败仅 `log.error`，没有重试机制或死信队列
- 如果 Kafka 短暂不可用，订单事件可能丢失

---

### 4.7 HardRisk 价格偏离检查对新用户无标记价格

**文件**: `hard-risk-core/src/main/java/com/exchange/risk/service/impl/HardRiskServiceImpl.java:161-170`

**问题描述**:
- 价格偏离检查依赖 `position.getMarkPrice()`
- 新用户没有持仓时，`position == null`，跳过价格偏离检查
- 这意味着新用户的第一单不受价格偏离限制

---

### 4.8 OMS 的 `handleTradeReport` 废弃未清理

**文件**: `oms-core/src/main/java/com/exchange/oms/service/impl/OmsServiceImpl.java:421-455`

**问题描述**:
- `handleTradeReport` 方法未被任何 Kafka 消费者调用（成交更新已移至 `OrderStateConsumer`）
- 但方法仍保留在 Service 接口中，容易误导开发者

---

## 5. ✅ 链路正确性确认

以下环节代码层检查通过，逻辑正确：

| 环节 | 文件 | 结论 |
|------|------|------|
| OMS 幂等检查 | `OmsServiceImpl.submitOrder` | ✅ MD5 哈希 + 独立幂等表，请求内容变化会拒绝 |
| OMS 乐观锁更新 | `OmsOrderMapper.updateStatus` / `updateFilledQuantity` | ✅ 使用 version 字段 CAS |
| 撮合引擎 Disruptor 单线程 | `DisruptorEngine` | ✅ ProducerType.SINGLE + 单消费者，无锁 |
| OrderBook 读写锁 | `OrderBook` | ✅ writeLock 保护修改，readLock 保护查询 |
| 撮合价格优先时间优先 | `OrderBook.matchBuyOrder` / `matchSellOrder` | ✅ 正确实现 |
| Ledger 双录分录 | `LedgerServiceImpl.generateTradeEntries` | ✅ 借贷平衡（除空仓问题外） |
| Ledger 幂等性 | `LedgerServiceImpl.filterExistingEntries` + DB UK | ✅ 多重防护 |
| Snapshot 幂等性 | `AccountSnapshotServiceImpl.onTradeEntryEvent` | ✅ bizSeq 检查 |
| Position 去重 | `PositionServiceImpl.isDuplicateOrStaleTradeEvent` | ✅ tradeId + bizSeq 双重检查 |
| OMS 状态终态保护 | `OrderStateConsumer.consumeOrderState` | ✅ FILLED/CANCELED/REJECTED 不可回退 |
| OMS 成交数量防溢出 | `OrderStateConsumer` | ✅ `effectiveFilledDelta = min(delta, maxAppendable)` |

---

## 6. 修复优先级建议

| 优先级 | 问题 | 涉及文件 | 预估工作量 |
|--------|------|----------|-----------|
| P0 | 撤单保证金永不解冻 | `OmsServiceImpl` | 1h |
| P0 | 异常回滚解冻金额为0 | `OmsServiceImpl` | 1h |
| P0 | 成交释放保证金精度错误 | `OrderStateConsumer` | 2h |
| P0 | Ledger 分录不支持空仓 | `LedgerServiceImpl` | 4h |
| P0 | 统一 OMS 下单路径 | `OmsServiceImpl` / `OrderServiceImpl` | 2d |
| P1 | 下单未设置 leverage | `OmsServiceImpl` | 30min |
| P1 | TradePublisher double精度 | `TradePublisher` | 1h |
| P1 | 撤单硬编码杠杆 | `OmsServiceImpl` | 30min |
| P1 | ReduceOnly Hedge Mode | `HardRiskServiceImpl` | 4h |
| P2 | confirmSubmit 未实现 | `OmsServiceImpl` | 2h |
| P2 | queryOrderList 未实现 | `OmsServiceImpl` | 2h |
| P2 | Kafka 失败无重试 | 多个 Publisher | 1d |

---

*报告结束*
