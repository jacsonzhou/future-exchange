# CFD V3 架构定版与 AI 分批执行指令

## 1. 目标与优先级（已确认）

本执行计划按以下优先级推进：

1. 第一优先级：Dealer 定价来源改为内存 OrderBook（不走 Redis pull 热路径）。
2. 第二优先级：行情时基统一，`depth/trade/kline` 全部来自 `market-price-core` 标准输出。
3. 第三优先级：Kafka 从 `topic per symbol` 迁移为 `topic + partition + key(symbol)`。

附加目标：

1. 保持现有成交、账本、风控、强平链路功能不回退。
2. 迁移期间支持双写/双读与可回滚开关。
3. 保证 AI 并行开发可控：每批次都有明确输入、输出、门禁与回滚点。

---

## 2. V3 目标架构（最终态）

```text
Binance WS/REST
  -> binance-data-source
  -> md.raw.depth.v1 / md.raw.trade.v1 / md.raw.kline.v1
  -> market-price-core (统一时基、标准化、K线权威写入、对账)
  -> md.std.depth.v1 / md.std.trade.v1 / md.std.kline.v1 / md.std.ticker.v1
  -> public-push-core
  -> Client

Client -> Gateway -> OMS -> ex.order.command.v1 (key=symbol)
  -> cfd-dealer-core (InMemory ReferenceBookStore, VWAP)
  -> ex.trade.v1 + ex.order.state.v1
  -> ledger-core -> acc.trade.entry.v1 -> position-snapshot-core
  -> private-push-core

md.raw.depth/trade -> index-price-core -> risk.index.price.v1
  -> mark-price-core -> risk.mark.price.v1
  -> margin-mode-core -> risk.liquidation.trigger.v1
  -> liquidation-core -> risk.liquidation.completed.v1
  -> adl-core
```

---

## 3. Kafka Topic 契约（V3）

### 3.1 行情通道

1. `md.raw.depth.v1`（输入，binance-data-source 生产）。
2. `md.raw.trade.v1`（输入，binance-data-source 生产）。
3. `md.raw.kline.v1`（输入参考，binance-data-source 生产）。
4. `md.std.depth.v1`（标准输出，market-price-core 生产）。
5. `md.std.trade.v1`（标准输出，market-price-core 生产）。
6. `md.std.kline.v1`（标准输出，market-price-core 生产）。
7. `md.std.ticker.v1`（标准输出，market-price-core 生产）。
8. `md.ref.book.v1`（Dealer 参考盘口通道，market-price-core 生产）。

### 3.2 交易通道

1. `ex.order.command.v1`（OMS -> Dealer/Match）。
2. `ex.order.state.v1`（Dealer/Match -> OMS/Liquidation）。
3. `ex.trade.v1`（Dealer/Match -> Ledger/Market）。
4. `acc.trade.entry.v1`（Ledger -> Position）。

### 3.3 风控与清算通道

1. `risk.index.price.v1`（Index Price 输出）。
2. `risk.mark.price.v1`（Mark Price 输出）。
3. `risk.liquidation.trigger.v1`（Margin Mode 输出）。
4. `risk.liquidation.completed.v1`（Liquidation 输出）。

### 3.4 key 与分区策略

1. 行情与交易主链 key 使用 `symbol`，保证单 symbol 顺序。
2. 强平触发 key 使用 `userId:positionId`。
3. 分区数建议：起步 `48`，后续按活跃 symbol 与吞吐扩容。
4. 所有主题采用 `topic + partition`，不再新增 per-symbol topic。

---

## 4. 事件 Envelope（统一要求）

所有新 topic 消息统一包含：

1. `eventId`
2. `eventType`
3. `schemaVersion`
4. `source`
5. `eventTime`
6. `traceId`
7. `data`

幂等键建议：

1. K线：`source:symbol:interval:openTime`
2. 成交：`tradeId`
3. 订单状态：`orderId:status:matchSequence`
4. 强平触发：`positionId:triggerType:sequence`

---

## 5. AI 并行执行总规则

1. 每批次独立分支：`feat/v3-b{N}-{short-name}`。
2. 一个 AI 只做一个批次，不跨批次改动。
3. 每批次 PR 必须附：
4. 改动文件清单。
5. 配置差异（Nacos + application）。
6. 脚本执行结果摘要。
7. 回滚方式。
8. 严禁硬编码 URL、密码、端口。
9. 迁移批次默认保留回滚开关。

---

## 6. 分批执行指令（可直接下发给 AI）

## B0：契约冻结与门禁基线

### 目标

冻结 V3 主题和事件契约，补齐初始化脚本与门禁。

### 修改类/文件

1. `/Users/zhoufan/project/future-exchange/init_kafka.sh`
2. `/Users/zhoufan/project/future-exchange/init_kafka_new.sh`
3. `/Users/zhoufan/project/future-exchange/docs/contracts/topic-contract-v3.md`（新增）
4. `/Users/zhoufan/project/future-exchange/docs/contracts/event-envelope-v1.md`（新增）
5. `/Users/zhoufan/project/future-exchange/scripts/cfd/test_c1_schema_contract.sh`（扩展 topic 校验）

### 配置改动

1. 新增 topic 清单。
2. 保留旧 topic 不删除（迁移期）。

### 执行脚本

1. `bash scripts/cfd/test_c1_schema_contract.sh`

### 通过标准

1. 新旧 topic 共存。
2. 门禁脚本 PASS。

### 回滚

1. 回滚到旧 topic 初始化脚本版本。

---

## B1（优先级1）：Dealer 内存定价底座

### 目标

在 Dealer 建立 InMemory 参考盘口，先双轨（MEMORY+REDIS）对比。

### 修改类/文件

1. `/Users/zhoufan/project/future-exchange/cfd-dealer-core/src/main/java/com/exchange/cfddealer/service/ReferenceBookStore.java`（新增）
2. `/Users/zhoufan/project/future-exchange/cfd-dealer-core/src/main/java/com/exchange/cfddealer/consumer/ReferenceDepthConsumer.java`
3. `/Users/zhoufan/project/future-exchange/cfd-dealer-core/src/main/java/com/exchange/cfddealer/service/impl/MarketExecutionServiceImpl.java`
4. `/Users/zhoufan/project/future-exchange/cfd-dealer-core/src/main/java/com/exchange/cfddealer/service/impl/ReferencePricingServiceImpl.java`
5. `/Users/zhoufan/project/future-exchange/cfd-dealer-core/src/main/java/com/exchange/cfddealer/config/CfdDealerProperties.java`

### 配置改动

1. `/Users/zhoufan/project/future-exchange/nacos-configs/cfd-dealer-core-dev.yml`
2. 新增：
3. `cfd.dealer.pricing-mode: DUAL`
4. `cfd.dealer.reference.max-stale-ms`
5. `cfd.dealer.reference.min-depth-levels`

### 执行脚本

1. `bash scripts/cfd/test_c2_reference_book.sh`
2. `bash scripts/cfd/test_c4_market_fill.sh`
3. `bash scripts/cfd/test_c5_limit_trigger.sh`

### 通过标准

1. MARKET 成交不再依赖 Redis pull 热路径。
2. DUAL 模式下 memory/redis 价差在阈值内（日志/指标可观测）。
3. C2/C4/C5 全部 PASS。

### 回滚

1. `pricing-mode` 切回 `REDIS`。

---

## B2（优先级2）：行情时基统一（market-price-core 单一出口）

### 目标

客户端相关行情统一从 `market-price-core` 标准总线输出。

### 修改类/文件

1. `/Users/zhoufan/project/future-exchange/binance-data-source/src/main/java/com/exchange/binance/publisher/BinanceDataPublisher.java`
2. `/Users/zhoufan/project/future-exchange/market-price-core/src/main/java/com/exchange/market/consumer/ExternalMarketEventConsumer.java`
3. `/Users/zhoufan/project/future-exchange/market-price-core/src/main/java/com/exchange/market/consumer/MatchEventConsumer.java`
4. `/Users/zhoufan/project/future-exchange/market-price-core/src/main/java/com/exchange/market/publisher/MarketDataPublisher.java`
5. `/Users/zhoufan/project/future-exchange/public-push-core/src/main/java/com/exchange/push/service/KafkaConsumerManager.java`
6. `/Users/zhoufan/project/future-exchange/public-push-core/src/main/java/com/exchange/push/service/SubscriptionManager.java`
7. `/Users/zhoufan/project/future-exchange/public-push-core/src/main/java/com/exchange/push/service/MessageDispatcher.java`

### 配置改动

1. `/Users/zhoufan/project/future-exchange/nacos-configs/binance-data-source-dev.yml`
2. `/Users/zhoufan/project/future-exchange/nacos-configs/market-price-service-dev.yml`
3. `/Users/zhoufan/project/future-exchange/nacos-configs/public-push-service-dev.yml`
4. 新增：
5. `binance.datasource.publish-standard-compatible: false`
6. `market-data.output.standard-only: true`
7. `public-push.ext-channel-enabled: false`

### 执行脚本

1. `bash scripts/cfd/test_c7_push_consistency.sh`
2. `bash scripts/test_sell_fill_and_book.sh`

### 通过标准

1. `depth/trade/kline` 前端订阅全部来自标准总线。
2. 不再出现 `depth from ext + kline from standard` 混源。
3. C7 PASS。

### 回滚

1. 临时开启 `publish-standard-compatible=true` 与 `ext-channel-enabled=true`。

---

## B3（优先级3-准备）：Topic+Partition 双写改造

### 目标

执行链路改为共享 topic，先双写不切流。

### 修改类/文件

1. `/Users/zhoufan/project/future-exchange/oms-core/src/main/java/com/exchange/oms/publisher/CfdOrderCommandPublisher.java`
2. `/Users/zhoufan/project/future-exchange/cfd-dealer-core/src/main/java/com/exchange/cfddealer/producer/OrderStateProducer.java`
3. `/Users/zhoufan/project/future-exchange/cfd-dealer-core/src/main/java/com/exchange/cfddealer/producer/TradeEventProducer.java`
4. `/Users/zhoufan/project/future-exchange/ledger-core/src/main/java/com/exchange/ledger/publisher/LedgerEventPublisher.java`
5. `/Users/zhoufan/project/future-exchange/oms-core/src/main/java/com/exchange/oms/consumer/OrderStateConsumer.java`（兼容双来源）

### 配置改动

1. `/Users/zhoufan/project/future-exchange/nacos-configs/oms-core-dev.yml`
2. `/Users/zhoufan/project/future-exchange/nacos-configs/cfd-dealer-core-dev.yml`
3. 新增：
4. `execution.topic-mode: DUAL_WRITE`
5. `execution.topic.shared.order-command: ex.order.command.v1`
6. `execution.topic.shared.order-state: ex.order.state.v1`
7. `execution.topic.shared.trade: ex.trade.v1`

### 执行脚本

1. `bash scripts/cfd/test_c3_oms_route.sh`
2. `bash scripts/cfd/test_c4_market_fill.sh`
3. `bash scripts/cfd/test_c6_ledger_balance.sh`

### 通过标准

1. 新旧 topic 同时有消息，业务结果一致。
2. C3/C4/C6 PASS。

### 回滚

1. `topic-mode` 切回 `LEGACY_ONLY`。

---

## B4：Topic+Partition 切流

### 目标

消费者切到共享 topic（保留 fallback 窗口）。

### 修改类/文件

1. `/Users/zhoufan/project/future-exchange/cfd-dealer-core/src/main/java/com/exchange/cfddealer/consumer/CfdOrderCommandConsumer.java`
2. `/Users/zhoufan/project/future-exchange/oms-core/src/main/java/com/exchange/oms/consumer/OrderStateConsumer.java`
3. `/Users/zhoufan/project/future-exchange/liquidation-core/src/main/java/com/exchange/liquidation/consumer/OrderStateConsumer.java`
4. `/Users/zhoufan/project/future-exchange/ledger-core/src/main/java/com/exchange/ledger/consumer/TradeEventConsumer.java`
5. `/Users/zhoufan/project/future-exchange/position-snapshot-core` 相关 trade-entry 消费类（按现有实现对应更新）

### 配置改动

1. 所有消费者 group 与 topic 指向共享 topic。
2. 新增 `*_legacy_fallback_enabled=true`（临时）。

### 执行脚本

1. `bash scripts/cfd/run_full_chain_regression.sh`

### 通过标准

1. 全链路仅共享 topic 消费时仍 PASS。
2. 关键延迟指标不劣化。

### 回滚

1. 打开 `legacy_fallback_enabled` 并恢复旧消费者 topic。

---

## B5：K线权威存储、重启补齐、去重与对账

### 目标

保证 K 线“可恢复、可去重、可对账”。

### 修改类/文件

1. `/Users/zhoufan/project/future-exchange/sql/migrations/202603xx_add_kline_authority.sql`（新增）
2. `/Users/zhoufan/project/future-exchange/market-price-core/src/main/resources/db/schema.sql`
3. `/Users/zhoufan/project/future-exchange/market-price-core/src/main/java/com/exchange/market/repository/KlineAuthorityRepository.java`（新增）
4. `/Users/zhoufan/project/future-exchange/market-price-core/src/main/java/com/exchange/market/service/KlineAuthorityService.java`（新增）
5. `/Users/zhoufan/project/future-exchange/market-price-core/src/main/java/com/exchange/market/job/BinanceKlineBackfillJob.java`
6. `/Users/zhoufan/project/future-exchange/market-price-core/src/main/java/com/exchange/market/consumer/ExternalMarketEventConsumer.java`
7. `/Users/zhoufan/project/future-exchange/market-price-core/src/main/java/com/exchange/market/job/KlineReconcileJob.java`（新增）

### 配置改动

1. `/Users/zhoufan/project/future-exchange/nacos-configs/market-price-service-dev.yml`
2. 新增：
3. `market-data.kline.authority-enabled: true`
4. `market-data.kline.backfill-with-watermark: true`
5. `market-data.kline.reconcile.enabled: true`

### 执行脚本

1. `bash scripts/cfd/test_c7_push_consistency.sh`
2. 新增并执行：`bash scripts/cfd/test_kline_recovery_and_dedup.sh`（本批次创建）

### 通过标准

1. 服务重启后 K 线连续。
2. 重复消息不重复写入。
3. 收线后冲突进入冲突表并告警，不覆盖。

### 回滚

1. 关闭 authority 与 reconcile 开关，恢复旧查询路径。

---

## B6：风控链路幂等加固（Mark -> Liquidation -> ADL）

### 目标

消除重复触发、重复强平、重复 ADL。

### 修改类/文件

1. `/Users/zhoufan/project/future-exchange/margin-mode-core/src/main/java/com/exchange/margin/consumer/MarkPriceConsumer.java`
2. `/Users/zhoufan/project/future-exchange/margin-mode-core/src/main/java/com/exchange/margin/scheduled/LiquidationCheckTask.java`
3. `/Users/zhoufan/project/future-exchange/liquidation-core/src/main/java/com/exchange/liquidation/consumer/LiquidationTriggerConsumer.java`
4. `/Users/zhoufan/project/future-exchange/liquidation-core/src/main/java/com/exchange/liquidation/service/impl/LiquidationServiceImpl.java`
5. `/Users/zhoufan/project/future-exchange/liquidation-core/src/main/resources/db/schema.sql`（增加 dedup 唯一约束）
6. `/Users/zhoufan/project/future-exchange/adl-core/src/main/java/com/exchange/adl/consumer/LiquidationEventConsumer.java`

### 配置改动

1. `/Users/zhoufan/project/future-exchange/nacos-configs/margin-mode-core-dev.yml`
2. `/Users/zhoufan/project/future-exchange/nacos-configs/liquidation-core-dev.yml`
3. 新增 dedup 窗口配置。

### 执行脚本

1. `bash scripts/cfd/test_r6_liquidation_e2e.sh`
2. `bash scripts/cfd/test_c9_insurance_fund_contract.sh`

### 通过标准

1. 重放同一 mark/trigger 不重复创建强平单。
2. 强平完成事件重复消费不重复 ADL。

### 回滚

1. 关闭 dedup 强校验开关，保留日志告警模式。

---

## B7：收尾清理与旧链路下线

### 目标

下线遗留 per-symbol topic 路径与 ext 直推路径。

### 修改类/文件

1. 移除所有 `topic per symbol` 发布逻辑。
2. 清理 `public-push` 对 ext 业务通道的默认支持。
3. 更新文档：
4. `/Users/zhoufan/project/future-exchange/docs/architecture/CFD_V3_CUTOVER_REPORT.md`（新增）

### 配置改动

1. `topic-mode: SHARED_ONLY`
2. `legacy_fallback_enabled: false`

### 执行脚本

1. `bash scripts/cfd/run_full_chain_regression.sh`
2. `bash scripts/e2e_acceptance.sh`

### 通过标准

1. 全部门禁 PASS。
2. 无 legacy topic 消费依赖。

### 回滚

1. 回到 B4 配置（启用 fallback）。

---

## 7. AI 并行分工建议

1. AI-A：B0 + B3
2. AI-B：B1
3. AI-C：B2
4. AI-D：B5
5. AI-E：B6
6. AI-Lead：B4 + B7 + 集成回归 + 冲突合并

建议执行顺序：

1. 必须先完成 B0。
2. 并行 B1/B2/B3。
3. 再做 B4 切流。
4. 再做 B5/B6。
5. 最后 B7 收尾。

---

## 8. 每批次统一 AI 提示词模板

```text
你正在执行批次 {BATCH_ID}，仓库路径 /Users/zhoufan/project/future-exchange。
严格要求：
1) 仅修改本批次列出的类与配置，不做跨批次重构。
2) 所有新增事件遵循 docs/contracts/event-envelope-v1.md。
3) 保留回滚开关，默认开启可回退。
4) 完成后运行本批次指定脚本，并输出关键 PASS/FAIL 证据。
5) 输出必须包含：改动文件列表、配置差异、脚本结果、回滚步骤。
```

---

## 9. 最终 DoD（Definition of Done）

1. Dealer 成交路径不依赖 Redis pull。
2. 客户端行情三件套 `depth/trade/kline` 只来自 `market-price-core` 标准总线。
3. 执行链路核心 topic 全部完成 `topic + partition + key` 迁移。
4. K 线具备重启补齐、去重、冲突告警、定时对账能力。
5. 全链路回归脚本通过，且关键延迟指标未显著劣化。

