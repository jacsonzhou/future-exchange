# CFD 模式实施任务单 V1

> 文档定位：基于当前代码实现的“现状审计 + 整改任务单”，不是纯方案草案。  
> 审计时间：2026-03-01  
> 审计范围：`api-gateway / oms-core / cfd-dealer-core / binance-data-source / ledger-core / position-snapshot-core / snapshot-account-core / private-push-core / margin-mode-core / liquidation-core / adl-core`

---

## 1. 结论总览（直接回答问题）

1. 当前 CFD 用户下单主链路已是：
   `api-gateway -> oms-core -> cfd-dealer-core -> (order-state-{symbol}, trade-event-{symbol}) -> oms-core/ledger-core -> snapshot -> private-push`。
2. 架构方向整体合理：
   - OMS 路由层已支持 `CFD_DEALER` / `MATCH_ENGINE` 双模式。
   - Dealer 与 Ledger / Snapshot / Push 解耦，符合事件驱动闭环。
3. 下单成交与 PNL 推送主闭环“基本完备”：
   - 成交后可进入 `ledger -> trade-entry -> position/account snapshot -> private push`。
4. 强平链路“部分完备，未完全 CFD 化”：
   - 强平/ADL 内部下单仍硬编码走 `MATCH_ENGINE`，与 CFD 用户单路径不一致。
   - 强平重试/人工强平部分逻辑仍有 TODO。

---

## 2. 当前链路（现状图）

### 2.1 用户下单链路（CFD 模式）

```text
Client
  -> API Gateway (/api/order/create)
  -> OMS (/api/v1/oms/order/submit)
  -> resolve execution mode = CFD_DEALER
  -> Kafka: cfd-order-command-{symbol}
  -> CFD Dealer
      - MARKET: 参考盘口即时成交
      - LIMIT: 立即成交或入 WORKING，定时触发
  -> Kafka: order-state-{symbol} + trade-event-{symbol}
  -> OMS 消费 order-state 更新订单并推送 private-order-state
  -> Ledger 消费 trade-event.* 记账并发布 trade-entry-{symbol}
  -> Position/Snapshot 消费 trade-entry 与 mark-price-update
  -> private-position-change + private-account-change
  -> Private Push WebSocket
```

### 2.2 非 CFD 或内部订单链路（仍存在）

```text
Client/Internal
  -> OMS
  -> Kafka: order-event-{symbol}
  -> Match Engine
  -> Kafka: order-state-{symbol} + trade-event
  -> 后续账本/快照/推送同主链
```

### 2.3 强平链路（当前实现）

```text
mark-price-update
  -> margin-mode-core 计算保证金率
  -> Kafka: liquidation-trigger-topic
  -> liquidation-core
  -> Feign: oms-core /internal/order/createLiquidation
  -> OMS 内部 OrderServiceImpl 强制 executionMode = MATCH_ENGINE
  -> Match Engine
  -> order-state / trade-event
  -> liquidation-core 监听状态并后处理
```

### 2.4 你问的关键跳转点（api-gateway -> oms-core -> ???）

1. 普通用户单（CFD 配置下）是：`api-gateway -> oms-core -> cfd-dealer-core`。  
2. 强平/ADL 内部单目前是：`liquidation/adl -> oms-core internal -> match-engine-core`。

---

## 3. 架构合理性评估

| 维度 | 结论 | 说明 |
|---|---|---|
| CFD 主链分层 | 合理 | OMS 负责接单与路由，Dealer 负责参考盘口执行，Ledger 负责资金真相，Snapshot/Push 解耦 |
| 同步/异步边界 | 基本合理 | 下单入口同步，成交后异步事件驱动 |
| 账本真相源 | 合理 | `trade-event.* -> ledger -> trade-entry-*` 已形成唯一记账入口 |
| 推送体系 | 基本合理 | 订单状态、仓位、账户分别走独立 topic |
| 强平与用户单一致性 | 不合理（关键缺口） | 用户单可走 CFD，但强平/ADL 内单硬编码 `MATCH_ENGINE` |
| Topic 治理 | 有风险 | `trade-event` 与 `trade-event-{symbol}` 并存，规则需要收敛 |
| 多交易对扩展 | 有风险 | OMS/强平的 order-state 消费 topic 列表是硬编码 |

---

## 4. 完备性矩阵（下单/成交/PNL/强平）

| 检查项 | 状态 | 结论 |
|---|---|---|
| 用户下单路由（CFD） | ✅ 已实现 | OMS 已按 symbol/mode 路由到 `cfd-order-command-*` |
| MARKET 即时成交 | ✅ 已实现 | Dealer 基于参考盘口 VWAP 执行并回传状态/成交 |
| LIMIT WORKING/触发成交/撤单 | ✅ 已实现 | WORKING 落库 + 定时触发 + `CFD_CANCEL` |
| 成交到账本闭环 | ✅ 已实现 | Ledger 消费 `trade-event.*`，并校验 CFD 对手方字段 |
| PNL 计算与推送闭环 | ✅ 基本完备 | `mark-price -> position -> account -> private push` 链路存在 |
| 订单执行报告推送 | ✅ 已实现 | OMS 消费 `order-state-*` 后推送 `private-order-state` |
| 强平下单执行 | ⚠️ 部分完备 | 触发流程完整，但下单路径仍固定 Match，不是 CFD 一致路径 |
| 强平异常恢复/重试 | ⚠️ 部分完备 | 监控与状态机有实现，超时重试/手动强平仍有 TODO |
| ADL 完整闭环 | ⚠️ 部分完备 | 有消费与服务实现，但与 CFD 路由一致性及联调验收仍不足 |

---

## 5. 关键问题清单（P0/P1/P2）

## P0（必须优先）

### P0-1 强平/ADL 内部下单未 CFD 化（与用户单分叉）

1. 现状：`createLiquidationOrder` / `createAdlOrder` 内将 `executionMode` 写死为 `MATCH_ENGINE`。  
2. 影响：CFD 模式下用户单与强平单走两套撮合路径，风险逻辑、延迟特征、成交来源不一致。  
3. 目标：强平/ADL 与用户单共用同一 execution 路由策略（按 symbol/mode 决策）。

### P0-2 参考盘口标准快照写入依赖 API 访问路径

1. 现状：`cfd:reference:book:{symbol}` 的标准快照写入在 `ReferenceBookService.getReferenceBook()` 路径。  
2. 影响：若线上无持续调用该 API，Dealer/OMS 读取到 stale/missing 参考簿会拒单或异常。  
3. 目标：建立“持续写入”路径（消费深度流或定时构建），而非依赖手工/API 触发。

### P0-3 `trade-event` 命名规范不统一

1. 现状：Match 默认 `trade-event`，CFD Dealer 默认 `trade-event-{symbol}`。Ledger 通过 `trade-event.*` 兼容。  
2. 影响：Topic 治理、监控与容量规划复杂，排障认知成本高。  
3. 目标：统一规范并提供兼容迁移窗口（单写或双写过渡）。

## P1（应尽快完成）

### P1-1 OMS/强平 order-state 消费交易对硬编码

1. 现状：`order-state-BTCUSDT/ETHUSDT/XRPUSDT` 固定列表。  
2. 影响：新增交易对需改代码发布，扩展性差。  
3. 目标：改为配置化 topicPattern 或 symbol 列表配置。

### P1-2 强平重试与人工强平闭环未收口

1. 现状：超时重试、失败重试、manual liquidation 仍有 TODO/未实现。  
2. 影响：故障恢复依赖人工介入，影响风控可用性。  
3. 目标：补齐自动重试策略与人工兜底流程，并具备幂等。

### P1-3 OMS 双服务栈并存导致路由策略分裂

1. 现状：`OmsServiceImpl`（外部 CFD 路由）与 `OrderServiceImpl`（内部偏 Match）并存。  
2. 影响：同类订单在不同入口逻辑不一致，后续维护风险大。  
3. 目标：提炼统一下单路由能力，内部/外部复用同一策略。

## P2（优化项）

### P2-1 指标与告警体系补强

1. 增加 CFD 专项指标：参考簿陈旧度、拒单原因、工作单触发延迟、强平执行耗时。  
2. 建立 topic 级 lag 与失败率告警。

---

## 6. CFD 模式整改任务单（可执行）

| 任务ID | 优先级 | 目标 | 主要改动点 | 验收方式 | 验收标准 |
|---|---|---|---|---|---|
| T1 | P0 | 强平/ADL 路由统一到 CFD 策略 | OMS internal 下单改为复用 `CfdRouteProperties`，按 mode 路由到 Dealer 或 Match | 新增 `scripts/cfd/test_c8_liquidation_route.sh` | CFD symbol 下强平单进入 `cfd-order-command-*`，非 CFD symbol 仍可走 match |
| T2 | P0 | 参考盘口持续供给 | 在 `binance-data-source` 增加持续写入 `cfd:reference:book:{symbol}` 的后台链路 | 复用 `test_c2_reference_book.sh` + 压测采样 | 30 分钟无 API 调用情况下，参考簿仍持续更新且 `stalenessMs` 可控 |
| T3 | P0 | 统一 trade-event topic 规范 | 明确单一规范并改 producer/consumer 配置，保留短期兼容 | 回放 + 实盘联调 | 监控面仅保留一套规范，Ledger/下游无丢消息 |
| T4 | P1 | 消费交易对配置化 | OMS 与 liquidation 的 order-state 消费改为配置驱动 | 新增 symbol 联调 | 新增交易对不改代码即可消费订单状态 |
| T5 | P1 | 强平重试与人工流程闭环 | 完成 `OrderMonitorServiceImpl` 重试 TODO + `manualLiquidation` 实现 | 新增 `scripts/cfd/test_c9_liquidation_retry.sh` | 超时单自动重试成功，手动强平可闭环并幂等 |
| T6 | P1 | 统一 OMS 内外下单策略 | 收敛 `OmsServiceImpl` 与 `OrderServiceImpl` 路由能力 | 回归测试 + 架构评审 | 用户单/内部单在相同 mode 下行为一致 |
| T7 | P2 | 可观测性补强 | 增加指标、日志字段、告警模板 | 演练报告 | 可在 5 分钟内定位“拒单/无成交/无推送”故障归因 |

---

## 7. 验收脚本基线

### 7.1 已有脚本（当前仓库）

1. `scripts/cfd/test_c1_schema_contract.sh`
2. `scripts/cfd/test_c2_reference_book.sh`
3. `scripts/cfd/test_c3_oms_route.sh`
4. `scripts/cfd/test_c4_market_fill.sh`
5. `scripts/cfd/test_c5_limit_trigger.sh`
6. `scripts/cfd/test_c6_ledger_balance.sh`
7. `scripts/cfd/test_c7_push_consistency.sh`

### 7.2 建议新增脚本

1. `scripts/cfd/test_c8_liquidation_route.sh`：验证强平单在 CFD symbol 下的真实路由。
2. `scripts/cfd/test_c9_liquidation_retry.sh`：验证超时重试、失败重试、人工强平兜底。
3. `scripts/cfd/test_c10_topic_convergence.sh`：验证 trade-event topic 收敛后的兼容性。

---

## 8. 风险与回滚策略

1. 强平路由改造风险：若 Dealer 异常会影响风险处置时效。  
   回滚：保留 per-symbol 开关，快速切回 `MATCH_ENGINE`。
2. topic 收敛风险：切换窗口可能导致消费者漏订阅。  
   回滚：双写期 + 消费端双订阅 + lag 对账。
3. 参考簿持续化风险：若写入链路抖动可能导致误拒单。  
   回滚：保留陈旧保护阈值和降级策略（拒单优先于盲目成交）。

---

## 9. 现状证据索引（代码定位）

1. Gateway 下单路由到 OMS：`nacos-configs/api-gateway-dev.yml`
2. OMS CFD 路由与命令发布：
   - `oms-core/src/main/java/com/exchange/oms/service/impl/OmsServiceImpl.java`
   - `oms-core/src/main/java/com/exchange/oms/config/CfdRouteProperties.java`
   - `oms-core/src/main/java/com/exchange/oms/publisher/CfdOrderCommandPublisher.java`
3. Dealer 消费/执行/回传：
   - `cfd-dealer-core/src/main/java/com/exchange/cfddealer/consumer/CfdOrderCommandConsumer.java`
   - `cfd-dealer-core/src/main/java/com/exchange/cfddealer/service/impl/MarketExecutionServiceImpl.java`
   - `cfd-dealer-core/src/main/java/com/exchange/cfddealer/service/impl/LimitWorkingOrderServiceImpl.java`
   - `cfd-dealer-core/src/main/java/com/exchange/cfddealer/producer/TradeEventProducer.java`
4. Ledger 与下游闭环：
   - `ledger-core/src/main/java/com/exchange/ledger/consumer/TradeEventConsumer.java`
   - `ledger-core/src/main/java/com/exchange/ledger/service/impl/LedgerServiceImpl.java`
   - `position-snapshot-core/src/main/java/com/exchange/position/consumer/TradeEntryEventConsumer.java`
   - `position-snapshot-core/src/main/java/com/exchange/position/consumer/MarkPriceEventConsumer.java`
   - `snapshot-account-core/src/main/java/com/exchange/snapshot/consumer/PositionChangeEventConsumer.java`
   - `snapshot-account-core/src/main/java/com/exchange/snapshot/publisher/AccountChangePublisher.java`
5. 强平链路与当前缺口：
   - `liquidation-core/src/main/java/com/exchange/liquidation/client/OmsClient.java`
   - `oms-core/src/main/java/com/exchange/oms/controller/OrderInternalController.java`
   - `oms-core/src/main/java/com/exchange/oms/service/impl/OrderServiceImpl.java`

---

> 当前判定：CFD 主交易链路已跑通，但“强平路径与用户路径一致性”仍是 V1 关闭前的首要阻塞项。
