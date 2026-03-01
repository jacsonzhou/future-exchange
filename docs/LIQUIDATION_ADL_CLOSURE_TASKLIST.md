# 真闭环改造可执行任务单（Binance -> Index -> Mark -> Margin -> Liquidation -> ADL -> Position/Account Push）

## 1. 收敛目标

目标是让强平链路从真实行情源到 ADL 与账户推送形成单链闭环，且每一段可独立验收、可追溯：

1. Binance 外部行情可稳定驱动 `index-price-update`。
2. `index-price-update` 可稳定驱动 `mark-price-update`。
3. `mark-price-update` 可触发保证金检查并产出 `liquidation-trigger-topic`。
4. `liquidation-trigger-topic` 可驱动 `liquidation-core` 创建强平单并完成状态回写。
5. 强平完成事件可驱动 `adl-core` 决策并执行 ADL（必要时）。
6. ADL/强平结果可最终反映到持仓和账户推送，不依赖轮询。

---

## 1.1 启动规范（强制）

本任务单所有批次执行前，启动方式必须统一为：**低内存 `java -jar` 直启 + 服务独立日志落盘**。

### 基线命令

```bash
# 1) 先构建可运行 Jar（一次即可，代码变更后需重编）
mvn clean package -DskipTests

# 2) 统一低内存 JVM 参数（如需更小内存可继续下调）
export JAVA_LOW_MEM_OPTS="-Xms128m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

# 3) 启动闭环所需服务（由 servicectl 统一以 java -jar 直启）
./scripts/servicectl.sh restart \
  match-engine-core ledger-core oms-core \
  snapshot-account-core position-snapshot-core \
  market-price-core index-price-core mark-price-core \
  margin-mode-core liquidation-core adl-core \
  public-push-core private-push-core api-gateway user-core \
  binance-data-source
```

### 日志与进程落盘

1. 服务日志：`logs/servicectl/{service}.log`（每个服务独立文件）。
2. PID 文件：`logs/servicectl/pids/{service}.pid`。
3. 排障优先使用：

```bash
./scripts/servicectl.sh status all
tail -n 200 logs/servicectl/liquidation-core.log
tail -n 200 logs/servicectl/adl-core.log
```

---

## 2. 批次拆分总览

| 批次 | 目标 | 关键输入Topic | 关键输出Topic | Gate |
|---|---|---|---|---|
| B1 | 行情真实源连通 | `market.ext.binance.depth.*` `market.ext.binance.trade.*` | `index-price-update` | 指数事件可追溯到 Binance |
| B2 | 标记价到强平触发 | `mark-price-update` | `liquidation-trigger-topic` | 强平触发事件字段完整 |
| B3 | 强平下单闭环 | `liquidation-trigger-topic` | `order-event-*` `order-state-*` | liquidation -> OMS -> 回写闭环 |
| B4 | 强平完成事件闭环 | `order-state-*` | `liquidation-completed-topic` | completed 事件可消费 |
| B5 | ADL 决策与执行闭环 | `liquidation-completed-topic` | `adl-trigger-topic` `adl-executed-topic` | ADL 触发/执行可追溯 |
| B6 | 持仓/账户联动闭环 | `mark-price-update` `adl-executed-topic` | `private-position-change` `private-account-change` | UPNL 与账户汇总一致 |
| B7 | 严格闭环验收 | 全链路 | PASS/FAIL 报告 | 严格验收通过 |

---

## 3. 详细任务单

## B1 Binance -> Index -> Mark

### 目标

确保真实行情源进入指数与标记链路，不走 mock/随机回退。

### 代码与配置

1. `index-price-core` 消费外部行情：
   - `index-price-core/src/main/java/com/exchange/index/consumer/ExternalMarketDataConsumer.java`
   - `index-price-core/src/main/java/com/exchange/index/service/support/ExternalMarketStateStore.java`
2. `mark-price-core` 仅消费 `index-price-update`：
   - `mark-price-core/src/main/java/com/exchange/markprice/consumer/IndexPriceConsumer.java`
3. Nacos 配置：
   - `nacos-configs/index-price-service-dev.yml`
   - `nacos-configs/mark-price-service-dev.yml`

### 验证命令

```bash
docker exec kafka-1 kafka-console-consumer --bootstrap-server localhost:9092 --topic index-price-update --timeout-ms 10000 --max-messages 3
docker exec kafka-1 kafka-console-consumer --bootstrap-server localhost:9092 --topic mark-price-update --timeout-ms 10000 --max-messages 3
```

### PASS/FAIL

1. PASS：`index-price-update` 与 `mark-price-update` 均可看到 `sourceTopic/sourceOffset` 等可追溯字段。
2. FAIL：指数或标记事件缺失、来源不可追溯、存在随机价。

---

## B2 Mark -> Margin -> Liquidation Trigger

### 目标

`margin-mode-core` 必须直接消费 `mark-price-update`，并稳定产出 `liquidation-trigger-topic`。

### 代码与配置

1. 消费器契约统一：
   - `margin-mode-core/src/main/java/com/exchange/margin/consumer/MarkPriceConsumer.java`
   - `margin-mode-core/src/main/java/com/exchange/margin/dto/MarkPriceEvent.java`
2. Nacos topic 收敛：
   - `nacos-configs/margin-mode-core-dev.yml`
   - `margin.kafka.mark-price-topic=mark-price-update`

### 验证命令

```bash
docker exec kafka-1 kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group margin-mode-service
docker exec kafka-1 kafka-run-class kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic liquidation-trigger-topic --time -1
```

### PASS/FAIL

1. PASS：`margin-mode-service` 订阅 `mark-price-update`，且触发后 `liquidation-trigger-topic` offset 增长。
2. FAIL：仍订阅旧 topic、消息转换失败、无触发输出。

---

## B3 Liquidation 受理与 OMS 下单回写

### 目标

`liquidation-core` 对触发事件幂等受理，并能成功调用 OMS 内部下单。

### 代码与配置

1. 消费与处理：
   - `liquidation-core/src/main/java/com/exchange/liquidation/consumer/LiquidationTriggerConsumer.java`
   - `liquidation-core/src/main/java/com/exchange/liquidation/service/impl/LiquidationServiceImpl.java`
2. OMS 内部接口契约：
   - `liquidation-core/src/main/java/com/exchange/liquidation/dto/CreateOrderRequest.java`
   - `oms-core/src/main/java/com/exchange/oms/controller/OrderInternalController.java`

### 验证命令

```bash
docker exec kafka-1 kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group liquidation-service-group
rg -n "Received liquidation trigger|create liquidation order|OMS create liquidation order failed" logs/servicectl/liquidation-core.log | tail -n 50
```

### PASS/FAIL

1. PASS：触发事件可消费，强平单可创建，失败可回写 `FAILED`。
2. FAIL：触发事件积压、OMS 报错、失败状态未落库。

---

## B4 强平完成事件

### 目标

强平成交后必须产出 `liquidation-completed-topic`，并携带 ADL 决策所需字段。

### 代码与配置

1. 完成事件发布：
   - `liquidation-core/src/main/java/com/exchange/liquidation/producer/LiquidationEventProducer.java`
   - `liquidation-core/src/main/java/com/exchange/liquidation/dto/LiquidationCompletedEvent.java`
2. Topic 配置：
   - `nacos-configs/liquidation-core-dev.yml`
   - `kafka.topic.liquidation-completed=liquidation-completed-topic`

### 验证命令

```bash
docker exec kafka-1 kafka-console-consumer --bootstrap-server localhost:9092 --topic liquidation-completed-topic --timeout-ms 10000 --max-messages 3
```

### PASS/FAIL

1. PASS：事件包含 `liquidationId,userId,symbol,isBankrupt,bankruptLoss,adlRequired`。
2. FAIL：无事件或关键字段缺失。

---

## B5 ADL 消费与执行

### 目标

`adl-core` 可消费强平完成事件，并在 `adlRequired=true` 时触发 ADL 执行。

### 代码与配置

1. 事件契约与消费：
   - `adl-core/src/main/java/com/exchange/adl/event/LiquidationCompletedEvent.java`
   - `adl-core/src/main/java/com/exchange/adl/consumer/LiquidationEventConsumer.java`
2. 服务主实现与执行：
   - `adl-core/src/main/java/com/exchange/adl/service/AdlService.java`
   - `adl-core/src/main/java/com/exchange/adl/service/impl/AdlServiceImplIntegrated.java`
3. 端口与消费组：
   - `nacos-configs/adl-core-dev.yml`

### 验证命令

```bash
./scripts/servicectl.sh status adl-core
docker exec kafka-1 kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group adl-service-group
docker exec kafka-1 kafka-console-consumer --bootstrap-server localhost:9092 --topic adl-trigger-topic --timeout-ms 10000 --max-messages 3
```

### PASS/FAIL

1. PASS：`adl-service-group` 在线，且可产出 `adl-trigger-topic` / `adl-executed-topic`。
2. FAIL：ADL 启不来、消费不到 `liquidation-completed-topic`、无执行事件。

---

## B6 持仓/账户推送收敛

### 目标

强平与 ADL 后，持仓与账户推送状态一致，不靠轮询。

### 代码与配置

1. 持仓更新与推送：
   - `position-snapshot-core/src/main/java/com/exchange/position/service/impl/PositionServiceImpl.java`
   - `position-snapshot-core/src/main/java/com/exchange/position/publisher/PositionChangePublisher.java`
2. 账户汇总与推送：
   - `snapshot-account-core/src/main/java/com/exchange/snapshot/service/impl/AccountSnapshotServiceImpl.java`
   - `snapshot-account-core/src/main/java/com/exchange/snapshot/publisher/AccountChangePublisher.java`

### 验证命令

```bash
docker exec kafka-1 kafka-console-consumer --bootstrap-server localhost:9092 --topic private-position-change --timeout-ms 10000 --max-messages 5
docker exec kafka-1 kafka-console-consumer --bootstrap-server localhost:9092 --topic private-account-change --timeout-ms 10000 --max-messages 5
```

### PASS/FAIL

1. PASS：`account.unrealizedPnl == Σ(position.unrealizedPnl)`，且推送实时变化。
2. FAIL：持仓与账户不一致、推送缺失或重复异常。

---

## B7 严格闭环验收

### 目标

形成最终可复现 PASS/FAIL 报告。

### 执行命令

```bash
JAVA_LOW_MEM_OPTS="-Xms128m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8" \
bash scripts/e2e_acceptance.sh --auto-start --with-liquidation --strict-liquidation
python3 scripts/test_position_up_mark_push.py
```

### 验收门槛（全部满足）

1. `Binance -> index -> mark -> margin -> liquidation -> adl` 全段连通。
2. 强平失败可回写、强平成功可发布完成事件。
3. ADL 消费与执行链路可观测。
4. 持仓/账户 PNL 推送一致。
5. 输出报告落盘：`test-reports/acceptance-suite-*.md/.json`。

### FAIL 归因模板

1. 失败点服务：
2. 失败Topic：
3. 失败字段：
4. 重现命令：
5. 修复提交：

---

## 4. 批次推进记录模板

| 批次 | 负责人 | 开始时间 | 结束时间 | 状态(PASS/FAIL) | 证据 |
|---|---|---|---|---|---|
| B1 |  |  |  |  |  |
| B2 |  |  |  |  |  |
| B3 |  |  |  |  |  |
| B4 |  |  |  |  |  |
| B5 |  |  |  |  |  |
| B6 |  |  |  |  |  |
| B7 |  |  |  |  |  |
