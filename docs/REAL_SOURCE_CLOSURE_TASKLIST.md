# 真闭环改造可执行任务单（Binance -> Index -> Mark -> Position/Account -> Private Push）

## 1. 收敛目标

目标是把行情估值链路统一成单一真实源，并保证以下闭环同时成立：

1. Binance 行情变动可驱动 `index-price-update`。
2. `index-price-update` 可驱动 `mark-price-update`，无随机/假价回退。
3. `mark-price-update` 可驱动持仓 UPNL 变更并推送 `private-position-change`。
4. `private-position-change` 可驱动账户总未实现盈亏联动并推送 `private-account-change`。
5. 前端持仓/PNL 由私有推送驱动，不依赖持仓 PNL 轮询。

注意：持仓成本（`size/entryPrice`）仍由 `trade-entry-{symbol}` 维护，不能被行情链路替代。

---

## 2. 批次拆分总览

| 批次 | 目标 | 关键输入Topic | 关键输出Topic | Gate |
|---|---|---|---|---|
| B0 | 基线冻结与可观测性 | 现网链路 | 基线报告 | 有基线数据 |
| B1 | Index 接入真实 Binance 行情 | `market.ext.binance.depth.*` `market.ext.binance.trade.*` | 内部缓存状态 | Index 可见真实源状态 |
| B2 | Index 事件标准化输出 | B1 缓存状态 | `index-price-update` | 事件字段完整可追溯 |
| B3 | Mark 去随机化与确定性计算 | `index-price-update` | `mark-price-update` | 无 mock/随机回退 |
| B4 | Position 估值闭环 | `mark-price-update` + `trade-entry-*` | `private-position-change` | 持仓 UPNL 正确推送 |
| B5 | Account 总UPNL联动 | `private-position-change` | `private-account-change` | 账户总UPNL正确 |
| B6 | Private Push + 前端接入收敛 | `private-position-change` `private-account-change` | WS `/ws/private` | 前端零轮询持仓PNL |
| B7 | 严格闭环验收 | 全链路 | PASS/FAIL 报告 | 严格验收通过 |

---

## 3. 详细任务单

## B0 基线冻结与观测

### 目标

在改造前固化基线，避免“改完不知道变好还是变坏”。

### 改动项

1. 新增基线脚本（建议）：
   - `scripts/baseline_real_source_closure.sh`（收集 topic lag、关键日志、接口快照）。
2. 报告输出路径：
   - `test-reports/baseline-closure-<timestamp>.md`

### 执行命令

```bash
./scripts/servicectl.sh status
python3 scripts/e2e_acceptance_suite.py --skip-pnl-push
python3 scripts/test_position_up_mark_push.py
```

### PASS/FAIL

1. PASS：有完整基线报告（接口返回、关键Topic、服务健康）。
2. FAIL：无法形成基线报告，或关键链路状态缺失。

---

## B1 Index 接入真实 Binance 行情输入

### 目标

`index-price-core` 不再使用 mock 抓价，改为消费 Binance 外部行情 Topic。

### 代码改动

1. 新增/改造消费者：
   - `index-price-core/src/main/java/com/exchange/index/consumer/ExternalMarketDataConsumer.java`（新增）
2. 新增状态缓存：
   - `index-price-core/src/main/java/com/exchange/index/service/support/ExternalMarketStateStore.java`（新增）
3. 下线 mock 路径：
   - `index-price-core/src/main/java/com/exchange/index/component/ExternalPriceFetcher.java`
   - `index-price-core/src/main/java/com/exchange/index/service/impl/IndexPriceServiceImpl.java`

### 字段与数据契约

1. 输入字段最小集：
   - depth: `s/E/U/u/b/a/source`
   - trade: `s/E/t/p/q/source`
2. 状态缓存最小集：
   - `bestBid` `bestAsk` `lastTradePrice` `sourceEventTime` `sourceTopic` `sourceOffset`

### 配置改动（Nacos）

1. `nacos-configs/index-price-service-dev.yml`
   - 新增 `index-price.input.depth-topic-pattern=market\\.ext\\.binance\\.depth\\..*`
   - 新增 `index-price.input.trade-topic-pattern=market\\.ext\\.binance\\.trade\\..*`
   - 新增 `index-price.calculation.source=binance_kafka`

### 验证命令

```bash
./scripts/servicectl.sh restart binance-data-source index-price-core
curl -s http://127.0.0.1:8105/api/binance/orderbook/BTCUSDT?depth=5
```

### PASS/FAIL

1. PASS：Index 内部状态可观测到来自 `market.ext.binance.*` 的最新值，且随 Binance 行情变化。
2. FAIL：Index 仍依赖 mock 或缓存回退值。

---

## B2 Index 标准事件输出

### 目标

`index-price-update` 事件可追溯、可幂等。

### 代码改动

1. 事件模型：
   - `index-price-core/src/main/java/com/exchange/index/event/IndexPriceUpdateEvent.java`
2. 发布器：
   - `index-price-core/src/main/java/com/exchange/index/producer/IndexPriceProducer.java`
3. 计算服务：
   - `index-price-core/src/main/java/com/exchange/index/service/impl/IndexPriceServiceImpl.java`

### 输出字段（新增）

1. `indexPriceId`（建议：`symbol-sourceEventTime-sourceOffset`）
2. `source`（固定 `binance`）
3. `sourceEventTime`
4. `sourceTopic`
5. `sourceOffset`
6. `bestBid` `bestAsk` `lastTradePrice`（至少其一用于追溯）

### Topic

1. 输出：`index-price-update`
2. Key：`symbol`

### 验证命令

```bash
docker exec kafka-1 kafka-console-consumer --bootstrap-server localhost:9092 --topic index-price-update --from-beginning --max-messages 3
```

### PASS/FAIL

1. PASS：事件字段齐全，连续两条消息 `indexPriceId` 不重复，且能追溯到源Topic与时间。
2. FAIL：字段缺失或事件无法追溯。

---

## B3 Mark 确定性计算（去随机/去假价）

### 目标

`mark-price-core` 仅基于 index 事件计算，不允许随机溢价或固定回退价。

### 代码改动

1. 核心服务：
   - `mark-price-core/src/main/java/com/exchange/markprice/service/impl/MarkPriceServiceImpl.java`
2. 消费器：
   - `mark-price-core/src/main/java/com/exchange/markprice/consumer/IndexPriceConsumer.java`
3. 事件模型：
   - `mark-price-core/src/main/java/com/exchange/markprice/event/MarkPriceUpdateEvent.java`

### 必做项

1. `onIndexPriceUpdate(symbol, indexPrice)` 必须使用入参，不二次读错误类型缓存。
2. 去除 `Math.random()` 路径。
3. 去除固定假价 fallback。
4. 标记价事件增加 `markPriceId` 和 `indexPriceId` 关联。

### Topic

1. 输入：`index-price-update`
2. 输出：`mark-price-update`

### 验证命令

```bash
./scripts/servicectl.sh restart mark-price-core
docker exec kafka-1 kafka-console-consumer --bootstrap-server localhost:9092 --topic mark-price-update --from-beginning --max-messages 5
```

### PASS/FAIL

1. PASS：`mark-price-update` 仅跟随 `index-price-update`，无随机跳变。
2. FAIL：仍出现 mock/随机行为。

---

## B4 Position 估值闭环

### 目标

`mark-price-update -> position UPNL` 稳定生效并推送，且与 `trade-entry-*` 的仓位成本协同。

### 代码改动

1. 消费器：
   - `position-snapshot-core/src/main/java/com/exchange/position/consumer/MarkPriceEventConsumer.java`
2. 服务：
   - `position-snapshot-core/src/main/java/com/exchange/position/service/impl/PositionServiceImpl.java`
3. 批量更新器：
   - `position-snapshot-core/src/main/java/com/exchange/position/service/support/MarkPriceBatchUpdater.java`
4. 推送发布器：
   - `position-snapshot-core/src/main/java/com/exchange/position/publisher/PositionChangePublisher.java`

### 字段要求

1. 计算字段：
   - `unrealizedPnl` `marginRatio` `liquidationPrice`
2. 事件字段：
   - `position.markPrice` `position.unrealizedPnl` `position.marginRatio` `position.liquidationPrice`
3. 幂等字段：
   - `markPriceId`（防重复）

### Topic

1. 输入：`mark-price-update` + `trade-entry-*`
2. 输出：`private-position-change`

### 验证脚本

```bash
python3 scripts/test_position_up_mark_push.py
```

### PASS/FAIL

1. PASS：注入 mark 事件后，WS `position.up` 明确变化且公式正确。
2. FAIL：未收到推送，或 `up` 不变/计算错误。

---

## B5 Account 总未实现盈亏联动

### 目标

账户总 UPNL 与持仓 UPNL 联动一致，`equity` 同步变化。

### 代码改动

1. 消费器：
   - `snapshot-account-core/src/main/java/com/exchange/snapshot/consumer/PositionChangeEventConsumer.java`
2. 服务：
   - `snapshot-account-core/src/main/java/com/exchange/snapshot/service/impl/AccountSnapshotServiceImpl.java`
3. Mapper：
   - `snapshot-account-core/src/main/java/com/exchange/snapshot/mapper/AccountSnapshotMapper.java`
4. 发布器：
   - `snapshot-account-core/src/main/java/com/exchange/snapshot/publisher/AccountChangePublisher.java`

### 必做项

1. 按 `userId + symbol + side` 维护子仓位 UPNL。
2. 汇总后更新 `account_snapshot.unrealized_pnl/equity`。
3. 乐观锁冲突重试必须保留。
4. Redis 异常时提供 DB 回补汇总路径（避免总UPNL错误）。

### Topic

1. 输入：`private-position-change`
2. 输出：`private-account-change`

### 验证命令

```bash
curl -sS http://127.0.0.1:8085/api/v1/account/query?userId=<USER_ID>
docker exec kafka-1 kafka-console-consumer --bootstrap-server localhost:9092 --topic private-account-change --from-beginning --max-messages 3
```

### PASS/FAIL

1. PASS：`account.unrealizedPnl == Σ(position.unrealizedPnl)`，`equity` 同步变动。
2. FAIL：账户总UPNL不等于持仓汇总。

---

## B6 Private Push 与前端收敛

### 目标

持仓和账户均由私有推送驱动，前端持仓PNL不轮询。

### 代码改动

1. 私有推送消费：
   - `private-push-core/src/main/java/com/exchange/privatepush/consumer/PositionChangeConsumer.java`
   - `private-push-core/src/main/java/com/exchange/privatepush/consumer/AccountChangeConsumer.java`
2. WS 分发：
   - `private-push-core/src/main/java/com/exchange/privatepush/service/MessageDispatcher.java`
3. 前端处理：
   - `trading-test.html`（`handlePrivateMessage` 增加 `stream=account` 直连UI更新）

### 必做项

1. `position` 流包含 `mp/up`，不丢变更字段。
2. `account` 流可直接更新余额和总权益，不仅依赖 `refreshBalance()`。
3. 持仓PNL不新增轮询器。

### Topic 与通道

1. 输入：`private-position-change` `private-account-change`
2. 输出：WS `/ws/private`，`stream=position` 和 `stream=account`

### 验证命令

```bash
./scripts/servicectl.sh restart private-push-core api-gateway
```

### PASS/FAIL

1. PASS：前端在不调用 `/api/v1/position/list` 轮询情况下，持仓UPNL实时变化。
2. FAIL：必须依赖轮询才能看到PNL变化。

---

## B7 严格闭环验收

### 目标

给出最终 PASS/FAIL，形成可复现证据。

### 执行命令

```bash
bash scripts/e2e_acceptance.sh --auto-start --with-liquidation --strict-liquidation
python3 scripts/test_position_up_mark_push.py
```

### 验收门槛（必须同时满足）

1. 下单/撤单/撮合/持仓/PNL 推送全链路通过。
2. 强平链路通过（含触发与结果）。
3. `position.up` 与公式一致，`account.unrealizedPnl` 与持仓汇总一致。
4. 报告输出：
   - `test-reports/acceptance-suite-*.md`
   - `test-reports/acceptance-suite-*.json`

### FAIL 归因模板

1. 失败点服务：
2. 失败Topic：
3. 失败字段：
4. 重现命令：
5. 修复提交：

---

## 4. Nacos 配置收敛清单

需要重点收敛以下 Data ID（已存在）：

1. `binance-data-source-dev.yml`
2. `index-price-service-dev.yml`
3. `mark-price-service-dev.yml`
4. `position-snapshot-core-dev.yml`
5. `snapshot-account-core-dev.yml`
6. `private-push-core-dev.yml`
7. `api-gateway-dev.yml`

校验要点：

1. `index-price-service` 必须有外部行情输入 topic pattern 配置。
2. `mark-price-service` 输入 topic 固定 `index-price-update`。
3. `position-snapshot-core` 必须消费 `trade-entry-*` 与 `mark-price-update`。
4. `snapshot-account-core` 必须消费 `private-position-change` 并输出 `private-account-change`。
5. `private-push-core` 必须订阅 `private-position-change` 与 `private-account-change`。

---

## 5. 批次推进记录模板

| 批次 | 负责人 | 开始时间 | 结束时间 | 状态(PASS/FAIL) | 证据链接 |
|---|---|---|---|---|---|
| B0 |  |  |  |  |  |
| B1 |  |  |  |  |  |
| B2 |  |  |  |  |  |
| B3 |  |  |  |  |  |
| B4 |  |  |  |  |  |
| B5 |  |  |  |  |  |
| B6 |  |  |  |  |  |
| B7 |  |  |  |  |  |

