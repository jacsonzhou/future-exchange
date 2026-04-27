# 限价单全链路模拟分析报告

> 分析范围：下单、部分成交、平仓、强平/ADL 四大场景  
> 持仓模式：Net Mode（单向持仓）  
> 版本基准：`4e2918d` 及之前所有修复

---

## 一、场景1：挂单 / 撤单（未成交）

### 1.1 完整链路时序

```
Client → API Gateway → OmsController.submitOrder()
  ↓
OmsServiceImpl.submitOrder()
  ├─ 参数校验 / 幂等 (NEW, idempotency_key)
  ├─ 创建 OmsOrder(status=1 NEW)
  ├─ HardRiskServiceImpl.checkOrderRisk()        ← 同步阻塞，Fail-Close
  │   └─ ReduceOnly 检查、保证金、仓位限制等
  ├─ calculateRequiredMargin(price, qty, leverage)
  ├─ ledgerClient.freezeMargin()                 ← available → frozen
  │   └─ LedgerServiceImpl.freezeMargin():
  │       ENTRY: USER_AVAILABLE  (credit, -margin)
  │       ENTRY: USER_FROZEN     (debit,  +margin)
  ├─ order.setFreezeStatus(1)                    ← [已修复]
  ├─ orderMapper.updateById(order)
  ├─ publishOrderEvent() → Kafka: order-event-BTCUSDT
  └─ WebSocket PUSH: NEW

MatchEngine (Kafka Consumer)
  ↓
MatchController.submitOrder() → DisruptorEngine.submitOrderCommand()
  ↓
MatchEventHandler.onEvent() (单线程)
  ├─ MatchEngine.onOrder(NEW_ORDER)
  │   └─ OrderBook.addOrder()                    ← 未匹配，加入订单簿
  └─ publishOrderState() → Kafka: order-state-BTCUSDT
      { orderId, status=NEW, filledQty=0 }

OMS (Kafka Consumer)
  ↓
OrderStateConsumer.consumeOrderState()
  ├─ 查询 OmsOrder
  ├─ 更新 filledQty / status (仍为 NEW)
  ├─ 无需释放保证金 (filledDelta=0)
  └─ WebSocket PUSH: NEW (execution report)

Client 收到订单状态 NEW，订单已挂到订单簿
```

### 1.2 撤单链路

```
Client → OmsController.cancelOrder()
  ↓
OmsServiceImpl.cancelOrder()
  ├─ 校验订单状态 (NEW / PARTIALLY_FILLED)
  ├─ if freezeStatus==1:
  │   └─ ledgerClient.unfreezeMargin()           ← frozen → available
  │       ENTRY: USER_FROZEN  (credit, -margin)
  │       ENTRY: USER_AVAILABLE (debit, +margin)
  ├─ updateOrderStatus(order, 5, CANCEL_PENDING)
  ├─ publishCancelEvent() → Kafka: order-event-BTCUSDT
  └─ WebSocket PUSH: CANCEL_PENDING

MatchEngine (Kafka Consumer)
  ↓
MatchEventHandler.onEvent(CANCEL_ORDER)
  ├─ OrderBook.cancelOrder(orderId)              ← 读写锁，从订单簿移除
  ├─ publishOrderState() → Kafka: order-state-BTCUSDT
  │   { orderId, status=CANCELED, filledDelta=0 }

OMS (Kafka Consumer)
  ↓
OrderStateConsumer.consumeOrderState()
  ├─ 更新 OmsOrder status=5 CANCELED
  ├─ filledDelta=0，无需再释放保证金
  └─ WebSocket PUSH: CANCELED
```

### 1.3 场景1 验证结论

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 下单风控同步阻塞 | ✅ | HardRisk 在 freeze 前执行，Fail-Close |
| 保证金冻结 | ✅ | freezeStatus=1 已修复，确保撤单能解冻 |
| 订单簿挂入 | ✅ | OrderBook 使用读写锁，线程安全 |
| 撤单释放保证金 | ✅ | `cancelOrder` 正确调用 `unfreezeMargin` |
| 状态机一致性 | ✅ | MatchEngine 返回 CANCELED，OMS 同步更新 |

**⚠️ 边缘情况**：订单在撤单前已被部分成交（已不在订单簿中）。
`MatchEventHandler.publishOrderState` 已处理：若 `filledDelta > 0` 则返回 `PARTIALLY_FILLED`，否则 `CANCELED`。

---

## 二、场景2：部分成交 + 实时盈亏推送 + 未成交撤单

### 2.1 部分成交链路

**前提**：用户 A 挂单 BUY 10 @ 50000，用户 B 吃单 SELL 5 @ MARKET

```
MatchEngine 撮合产生 Trade (qty=5)
  ↓
TradePublisher.publishTrade()
  ├─ buildTradeEvent()
  │   └─ price/quantity 使用 BigDecimal.multiply(SCALE).setScale(0)  [已修复]
  └─ kafkaTemplate.send("trade-event", tradeEvent)

Ledger (trade-event Consumer)
  ↓
TradeEventConsumer.consumeTradeEvent()
  └─ ledgerService.applyTrade(tradeDTO)
      ├─ generateTradeEntries()
      │   ├─ 通过 PositionSnapshotClient 查询 maker/taker 净持仓 [已修复]
      │   ├─ 拆分 closeQty / openQty (Net Mode 反向超仓时)
      │   └─ 生成双录分录 (4 条 ENTRY)
      ├─ batchInsert(ledger_entry)
      └─ publishTradeEntryEvent() → Kafka: trade-entry-BTCUSDT

AccountSnapshot (trade-entry Consumer)
  ↓
AccountSnapshotServiceImpl.onTradeEntryEvent()
  ├─ 按 userId 聚合 entries
  ├─ applyEntryToSnapshot()
  │   ├─ USER_AVAILABLE     → snapshot.availableBalance
  │   ├─ USER_FROZEN        → snapshot.frozenBalance
  │   └─ USER_POSITION_MARGIN → snapshot.positionMargin
  ├─ MySQL UPDATE (乐观锁重试3次)
  ├─ Redis HSET 更新
  └─ publishAccountChangeEvent() → Kafka: private-account-change

PositionSnapshot (trade-entry Consumer)
  ↓
PositionServiceImpl.onTradeEntryEvent()
  ├─ updatePositionNetMode()
  │   ├─ 检查 duplicateKey (tradeId + userId)
  │   ├─ doUpdatePositionNetMode()
  │   │   ├─ isBuy ? POSITION_SIDE_LONG : SHORT
  │   │   ├─ 先 close 反向仓位 (若有)
  │   │   └─ 再 open/increase 同向仓位 (剩余量)
  │   └─ 更新 avgPrice / realizedPnl / unrealizedPnl / marginRatio
  ├─ 检查 liquidation threshold → publish RiskEvent (如需)
  └─ publishPositionChangeEvent() → Kafka: private-position-change

PrivatePush (position/account Consumer)
  ↓
AccountChangeConsumer.onAccountChange()
  └─ messageDispatcher.sendToUser(userId, "account", payload)

PositionChangeConsumer.onPositionChange()
  └─ messageDispatcher.sendToUser(userId, "position", payload)
  │     └─ payload 包含 unrealizedPnl / marginRatio / liquidationPrice

OMS (order-state Consumer)
  ↓
OrderStateConsumer.consumeOrderState()
  ├─ 更新 OmsOrder filledQty += 5
  ├─ 更新 status = PARTIALLY_FILLED
  ├─ releaseFrozenMarginForFill()
  │   └─ ledgerClient.unfreezeMargin(unfreezeAmount)  ← [按成交比例释放]
  │       ENTRY: USER_FROZEN    (credit, -unfreeze)
  │       ENTRY: USER_AVAILABLE (debit,  +unfreeze)
  └─ WebSocket PUSH: PARTIALLY_FILLED
```

### 2.2 实时盈亏推送链路

```
mark-price-core
  ↓
定时计算 MarkPrice (基于 index-price + funding rate)
  └─ publish MarkPriceEvent → Kafka

position-snapshot-core
  ↓
MarkPriceEventConsumer
  ├─ 遍历该 symbol 所有持仓
  ├─ 逐仓: 直接更新 unrealizedPnl, marginRatio, liquidationPrice
  ├─ 全仓: 触发 CrossMarginSnapshot 重算
  └─ publishPositionChangeEvent() → private-position-change

private-push-core
  ↓
PositionChangeConsumer
  └─ WebSocket 推送到客户端 (diff 合并，30秒或50条触发)
```

### 2.3 未成交剩余撤单

```
Client 撤单（剩余 5）
  ↓
OMS cancelOrder → MatchEngine
MatchEngine OrderBook.cancelOrder() 移除剩余 5
  └─ publishOrderState: CANCELED, filledDelta=5

OMS consumeOrderState
  ├─ status → CANCELED
  ├─ releaseFrozenMarginForFill() 释放剩余 5 对应的保证金
  └─ WebSocket PUSH: CANCELED
```

### 2.4 场景2 验证结论

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 部分成交 Trade 发布 | ✅ | TradePublisher 使用精确 BigDecimal 转换 [已修复] |
| Ledger 分录拆分 | ✅ | 反向超仓时 closeQty/openQty 正确拆分 [已修复] |
| Position Net Mode | ✅ | 先平反向仓，再开同向仓 |
| Account Snapshot 乐观锁 | ✅ | 失败重试3次，DB + Redis 双写 |
| OMS 按成交比例释放 | ✅ | `calculateRequiredMargin` 不再除 SCALE [已修复] |
| 实时盈亏推送 | ✅ | MarkPrice → Position → PrivatePush 链路完整 |
| 撤单释放剩余保证金 | ✅ | 释放未成交部分 |

---

## 三、场景3：平仓 + 资金计算正确性

### 3.1 平仓链路（Net Mode）

**前提**：用户持 LONG 10 @ 50000，保证金 100 USDT（position_margin=100）。
用户下 SELL LIMIT 10 @ 51000 平仓。

```
OMS 下单 (同场景1)
  ├─ HardRisk: ReduceOnly 检查通过 (Net Mode 下 SELL 可平 LONG)
  ├─ ledgerClient.freezeMargin():
  │   ENTRY: USER_AVAILABLE  (credit, -100)      ← 冻结保证金
  │   ENTRY: USER_FROZEN     (debit,  +100)
  └─ publish order-event

MatchEngine 撮合成交 10 @ 51000
  ├─ publish trade-event
  └─ publish order-state: FILLED

Ledger consume trade-event
  └─ applyTrade()
      ├─ PositionSnapshotClient 查询净持仓 = LONG 10
      ├─ closeQty = min(10, 10) = 10, openQty = 0
      ├─ 生成平仓分录 (SELL 平 LONG):
      │   ├─ Maker (假设用户是 Taker，对手是 Maker):
      │   │   Taker SELL 平 LONG:
      │   │     ENTRY: USER_POSITION_MARGIN (credit, -100)  ← 减少持仓保证金
      │   │     ENTRY: USER_AVAILABLE       (debit,  +100)  ← 回到可用
      │   └─ 成交盈亏由另一组分录体现 (realizedPnl)
      └─ publish trade-entry

AccountSnapshot consume trade-entry
  ├─ position_margin -= 100
  ├─ available_balance += 100
  └─ publish private-account-change

PositionSnapshot consume trade-entry
  ├─ doUpdatePositionNetMode(SELL, qty=10)
  │   ├─ oppositeSide = SHORT, sameSide = LONG
  │   ├─ close LONG 10 → 持仓归零
  │   ├─ realizedPnl = (51000 - 50000) × 10 = +1000 USDT
  │   └─ 持仓快照删除或 qty=0
  └─ publish private-position-change

OMS consume order-state (FILLED)
  ├─ status → FILLED
  ├─ releaseFrozenMarginForFill()
  │   └─ ledgerClient.unfreezeMargin(100)
  │       ENTRY: USER_FROZEN    (credit, -100)
  │       ENTRY: USER_AVAILABLE (debit,  +100)
  └─ WebSocket PUSH: FILLED
```

### 3.2 🔴 严重问题：OMS 冻结 与 Ledger 成交 存在双重记账

#### 问题描述

在当前的架构中，**OMS 冻结/释放** 与 **Ledger 成交记账** 是两套独立的资金流转，但作用于同一账户余额，导致**资金重复增减**。

#### 资金变动推演（以平仓为例）

| 步骤 | 操作 | USER_AVAILABLE | USER_FROZEN | USER_POSITION_MARGIN | 说明 |
|------|------|---------------|-------------|---------------------|------|
| 初始 | - | 1000 | 0 | 100 | 开仓后状态 |
| 1 | OMS freezeMargin(100) | **900** | **100** | 100 | 下单冻结 |
| 2 | Ledger 平仓分录 | **1000** | 100 | **0** | position_margin → available |
| 3 | OMS unfreezeMargin(100) | **1100** | **0** | 0 | frozen → available |
| **净结果** | | **+200** | 0 | -100 | ❌ **可用余额被重复增加！** |

#### 正确的资金模型应为二选一

**方案 A：OMS 管理冻结，Ledger 负责成交后从 frozen 转移**

| 步骤 | USER_AVAILABLE | USER_FROZEN | USER_POSITION_MARGIN |
|------|---------------|-------------|---------------------|
| 初始 | 1000 | 0 | 0 |
| OMS freeze | 900 | 100 | 0 |
| Ledger 开仓 (from frozen) | 900 | **0** | **100** |
| Ledger 平仓 (to available) | **1000** | 0 | **0** |
| 净结果 | 0 | 0 | 0 | ✅ |

**方案 B：OMS 不冻结，Ledger 直接管理 available ↔ position_margin**

| 步骤 | USER_AVAILABLE | USER_FROZEN | USER_POSITION_MARGIN |
|------|---------------|-------------|---------------------|
| 初始 | 1000 | 0 | 0 |
| Ledger 开仓 | 900 | 0 | 100 |
| Ledger 平仓 | 1000 | 0 | 0 |
| 净结果 | 0 | 0 | 0 | ✅ |

#### 当前代码的实际情况

查看 `LedgerServiceImpl.generateTradeEntries()`（开仓场景）：

```java
// 开仓 Maker BUY
entries.add(buildEntry(..., LedgerEntryType.USER_AVAILABLE,    credit, margin));  // available 减少
entries.add(buildEntry(..., LedgerEntryType.USER_POSITION_MARGIN, debit, margin)); // position_margin 增加
```

这表示 Ledger 开仓时直接从 `USER_AVAILABLE` 扣除，而非从 `USER_FROZEN` 转移。

而 OMS 在下单时也冻结了同样的保证金：
```java
// OmsServiceImpl
ledgerClient.freezeMargin(freezeRequest);  // available → frozen
```

因此，**同一笔资金在下单时被扣了两次**（一次 OMS 冻结，一次 Ledger 开仓），然后 OMS 释放只还了一次。

对于**开仓**场景，净效果恰好正确（因为冻结和 Ledger 扣款方向一致）：
- available: -100(冻结) -100(Ledger开仓) +100(OMS释放) = -100
- position_margin: +100

但对于**平仓**场景，净效果就是错误的（double credit）：
- available: +100(Ledger平仓) +100(OMS释放) = +200

#### 影响评估

- **开仓场景**：可用余额被多扣 100，然后释放时只还 100，最终少 100。表现为"余额不足"假象，实际资金被冻结但不可用。
- **平仓场景**：可用余额被多增加 100，导致**资金凭空产生**，严重会计错误。
- **部分成交**：按比例放大上述问题。

> **结论：Ledger 的 `generateTradeEntries` 在 Net Mode 下应从 `USER_FROZEN` 而非 `USER_AVAILABLE` 转移资金，或者 OMS 应取消冻结机制，由 Ledger 统一管控。**

### 3.3 场景3 验证结论

| 检查项 | 状态 | 说明 |
|--------|------|------|
| ReduceOnly 平仓 | ✅ | HardRisk 已修复 Net Mode 逻辑 |
| Ledger 平仓分录 | ⚠️ | 分录逻辑正确，但**资金来源错误** |
| Position 平仓归零 | ✅ | Net Mode 先平反向仓 |
| Realized PnL 计算 | ✅ | (exitPrice - entryPrice) × qty |
| 资金计算正确性 | ❌ | **存在双重记账，平仓时可用余额被重复增加** |

---

## 四、场景4：强平 / ADL 减仓链路

### 4.1 强平触发链路

```
mark-price-core / margin-mode-core
  ↓
定时扫描所有持仓保证金率
  ├─ 逐仓: marginRatio = isolatedMargin / positionValue
  ├─ 全仓: crossSnapshot = calculateCrossSnapshot()
  │         marginRatio = marginBalance / totalPositionValue
  └─ if marginRatio < maintenanceMarginRate:
        publish LiquidationTriggerEvent → Kafka

liquidation-core
  ↓
LiquidationServiceImpl.processLiquidation(event)
  ├─ 三重幂等检查 (liquidationId / trigger triple / Redis setIfAbsent)
  ├─ createExecution() → DB (status=PENDING)
  ├─ createOrder()
  │   └─ omsClient.createOrder()
  │       { type=MARKET, side=closeSide, reduceOnly=true,
  │         source=LIQUIDATION, executionMode=CFD_DEALER }
  ├─ execution.status=SUBMITTED, orderId=xxx
  └─ orderMonitorService.startMonitoring(liquidationId, orderId)

OMS / MatchEngine (标准下单链路)
  ↓
强平订单进入撮合引擎，以 MARKET 单立即成交
  └─ publish order-state + trade-event (标准链路)

liquidation-core (订单监控回调)
  ↓
orderMonitorService 检测到订单 FILLED
  └─ LiquidationServiceImpl.processFilledLiquidation()
      ├─ 幂等检查 (COMPLETED 跳过)
      ├─ calculatePnL()
      │   ├─ realizedPnl = (exitPrice - entryPrice) × qty
      │   └─ bankruptLoss = max(0, bankruptcyPrice相关亏损)
      ├─ 处理部分成交累计 (partialPnl + lastPnl)
      ├─ if bankruptLoss > 0:
      │   └─ insuranceFundService.applyInsuranceCover()
      │       → 保险基金赔付 (Ledger 记账: insurance_fund → user_available)
      ├─ insuranceFundService.injectLiquidationSurplus()
      │   → 强平盈余注入保险基金
      ├─ execution.status=COMPLETED
      └─ publish LiquidationCompletedEvent → Kafka
```

### 4.2 ADL 触发链路

```
adl-core
  ↓
AdlServiceImpl.onLiquidationCompleted(event)
  ├─ getInsuranceFundBalance() → 硬编码 1000 USDT ⚠️
  ├─ if insuranceFund >= bankruptLoss:
  │     保险基金全额赔付，流程结束
  └─ else:
        remainingLoss = bankruptLoss - insuranceFund
        executeAdl(symbol, oppositeSide, remainingLoss, ...)
          ├─ adlRankingMapper.selectBySymbolAndSide() → 候选列表
          │   └─ 排序规则: 盈利高 + 杠杆高 优先
          ├─ for candidate in candidates:
          │     adlQty = min(candidate.qty, requiredQty)
          │     log.info("Executing ADL: userId={}, qty={}")  ← ⚠️ 仅打印，无实际操作
          └─ requiredQty -= adlQty
```

### 4.3 场景4 验证结论

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 强平订单创建 | ✅ | MARKET + ReduceOnly，通过 OMS 标准链路 |
| 强平幂等性 | ✅ | 三重检查 (DB ID / trigger triple / Redis) |
| 盈亏计算 | ✅ | 支持部分成交累计 |
| 保险基金赔付 | ✅ | 有赔付逻辑，穿仓损失计算完整 |
| 强平盈余回收 | ✅ | `injectLiquidationSurplus` 回收盈余 |
| ADL 排名计算 | ⚠️ | 有候选人查询和排序逻辑 |
| **ADL 实际减仓** | ❌ | **仅打印日志，未实际创建减仓订单** |
| **保险基金余额** | ❌ | **硬编码 1000 USDT，未查真实余额** |
| 强平监控 | ✅ | `orderMonitorService` 异步监控订单成交 |

---

## 五、汇总：链路断点与风险矩阵

### 5.1 致命风险 (P0)

| # | 问题 | 影响 | 文件 | 建议修复 |
|---|------|------|------|---------|
| 1 | **Ledger 分录与 OMS 冻结双重记账** | 平仓时可用余额凭空增加；开仓时可用余额被重复扣除 | `LedgerServiceImpl.generateTradeEntries()` | **方案A**: Ledger 开仓分录改为 `USER_FROZEN → USER_POSITION_MARGIN`，平仓分录改为 `USER_POSITION_MARGIN → USER_FROZEN`，OMS 释放 frozen 时再 `USER_FROZEN → USER_AVAILABLE`。 **方案B**: 取消 OMS freeze/unfreeze，由 Ledger 统一管控可用/持仓保证金流转。 |

### 5.2 高风险 (P1)

| # | 问题 | 影响 | 文件 | 建议修复 |
|---|------|------|------|---------|
| 2 | **ADL 仅打印日志未执行** | 穿仓时无法真正分摊损失 | `AdlServiceImpl.executeAdl()` | 实现 ADL 减仓：查询候选人 → 创建强制减仓订单 → OMS 下单 → 成交后更新候选人仓位 |
| 3 | **保险基金余额硬编码** | ADL 触发判断错误，可能该触发时不触发 | `AdlServiceImpl.getInsuranceFundBalance()` | 从 `ledger-core` 或 `insurance_fund` 表查询真实余额 |

### 5.3 中风险 (P2)

| # | 问题 | 影响 | 文件 | 建议修复 |
|---|------|------|------|---------|
| 4 | `PositionServiceImpl` 乐观锁冲突时 Kafka 重试 | 同一 trade-entry 可能被重复处理，导致仓位重复累加 | `PositionServiceImpl.onTradeEntryEvent()` | 使用唯一索引 (userId + symbol + tradeId) 保证幂等，或在重试前检查是否已处理 |
| 5 | `AccountSnapshotServiceImpl` 批量聚合后乐观锁失败 | 重试时可能导致部分 entry 重复应用 | `AccountSnapshotServiceImpl.applyEntryToSnapshot()` | 引入 `bizSeq` 作为处理流水号，重试时跳过已处理序列 |
| 6 | 全仓保证金率计算依赖同步 Feign | 全仓快照重算时大量 Feign 调用可能超时 | `MarginModeServiceImpl.calculateCrossSnapshot()` | 改为异步批量查询或缓存 |

### 5.4 已修复问题（供参考）

| # | 问题 | 修复内容 | Commit |
|---|------|---------|--------|
| 7 | freezeStatus 未设置导致撤单失败 | `submitOrder` 成功后设置 `freezeStatus=1` | 之前 |
| 8 | 异常回滚金额错误 | `rollbackLedgerFreeze` 接受 `amount` 参数 | 之前 |
| 9 | 保证金释放精度错误 | `calculateRequiredMargin` 不再除 SCALE | 之前 |
| 10 | 下单未透传 leverage | `order.setLeverage(request.getLeverage())` | 之前 |
| 11 | 撤单硬编码 leverage=10 | 改为 `order.getLeverage()` | 之前 |
| 12 | TradePublisher double 精度丢失 | 改用 BigDecimal 精确转换 | 之前 |
| 13 | ReduceOnly Net Mode 逻辑错误 | BUY 平 SHORT / SELL 平 LONG | 之前 |
| 14 | Ledger 反向超仓未拆分 | 新增 `PositionSnapshotClient` 查询净持仓并拆分 close/open | `4e2918d` |

---

## 六、Net Mode 资金流转总图

```
                        ┌─────────────────────────────────────┐
                        │            Client 下单               │
                        └──────────────┬──────────────────────┘
                                       │
                    ┌──────────────────▼──────────────────┐
                    │  OMS: calculateRequiredMargin()      │
                    │  OMS: ledgerClient.freezeMargin()    │
                    │      USER_AVAILABLE  ──► USER_FROZEN  │  ← ⚠️ 冻结1
                    └──────────┬─────────────────────────────┘
                               │
              ┌────────────────▼────────────────┐
              │   MatchEngine 撮合成交            │
              │   publish trade-event            │
              └──────────────┬──────────────────┘
                             │
          ┌──────────────────▼──────────────────┐
          │  Ledger: applyTrade()                │
          │  generateTradeEntries()              │
          │                                      │
          │  开仓 (BUY/LONG):                    │
          │    USER_AVAILABLE ──► USER_POS_MARGIN│  ← ⚠️ 冻结2 (双重扣款)
          │                                      │
          │  平仓 (SELL/LONG):                   │
          │    USER_POS_MARGIN ──► USER_AVAILABLE│  ← ⚠️ 释放1
          │                                      │
          │  publish trade-entry                 │
          └──────────────┬──────────────────────┘
                         │
     ┌───────────────────┴─────────────────────┐
     │                                         │
     ▼                                         ▼
┌─────────────┐                    ┌─────────────────────┐
│  Position   │                    │   Account Snapshot  │
│  Snapshot   │                    │                     │
│  (update)   │                    │  apply entries      │
└──────┬──────┘                    │  DB + Redis         │
       │                           └─────────────────────┘
       ▼
┌─────────────────────┐
│  OMS: consume       │
│  order-state        │
│  releaseFrozenMargin│
│  USER_FROZEN ──►    │  ← ⚠️ 释放2 (平仓时重复)
│  USER_AVAILABLE     │
└─────────────────────┘
```

---

## 七、修复优先级建议

1. **立即修复 (P0)**：调整 Ledger 分录资金来源，或取消 OMS 冻结机制。这是**资金安全**的根本问题。
2. **本周修复 (P1)**：完成 ADL 实际执行逻辑；保险基金余额改为真实查询。
3. **下周修复 (P2)**：Position/Account Snapshot 的幂等性加强（唯一索引 + bizSeq）。
4. **持续优化**：全仓快照计算异步化，减少 Feign 阻塞。
