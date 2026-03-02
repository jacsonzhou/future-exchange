# CFD 新增币对操作手册 V1（Nacos 优先）

> 目标：新增一个 CFD 币对时，尽量只改配置，不改代码。
> 
> 适用链路：`api-gateway -> oms-core -> cfd-dealer-core -> order-state/trade-event -> ledger -> snapshot -> private-push`

---

## 1. 结论

1. 新增币对放在 Nacos 管理是更好的方案。
2. 理由：配置集中、可审计、可灰度、可快速回滚，避免代码硬编码导致重复发版。
3. 目标状态应该是“新增币对 = 改 Nacos + 创建 Topic + 重启/刷新服务 + 回归验证”。

---

## 2. 配置真相源与职责

### 2.1 推荐单一真相源

建议把币对白名单统一维护在 Nacos（建议新增独立 DataId，如 `cfd-symbols-dev.yml`），并由各服务读取该列表。

示例：

```yaml
cfd:
  symbols:
    - BTCUSDT
    - ETHUSDT
    - SOLUSDT
    - BNBUSDT
    - XRPUSDT
    - ADAUSDT
```

### 2.2 当前版本需要修改的 Nacos 配置点

| DataId | 关键配置 | 是否必改 | 说明 |
|---|---|---|---|
| `binance-data-source-dev.yml` | `binance.datasource.symbols` | 是 | 决定外部盘口/成交/ticker订阅 |
| `cfd-dealer-core-dev.yml` | `cfd.dealer.symbols` | 是 | 决定 WORKING 单触发扫描范围 |
| `oms-core-dev.yml` | `oms.execution.default-mode / symbol-mode` | 视情况 | 若 `default-mode=CFD_DEALER` 可不单独加；否则需为新币对加 `CFD_DEALER` |
| `api-gateway-dev.yml` | `gateway.rate-limit.per-symbol` | 建议 | 新币对限流保护，避免热点冲击 |

---

## 3. 新增币对标准操作流程（以 `ADAUSDT` 为例）

### 3.1 步骤 A：修改 Nacos

1. 在 `binance-data-source-dev.yml` 增加：

```yaml
binance:
  datasource:
    symbols:
      - BTCUSDT
      - ETHUSDT
      - SOLUSDT
      - BNBUSDT
      - XRPUSDT
      - ADAUSDT
```

2. 在 `cfd-dealer-core-dev.yml` 增加：

```yaml
cfd:
  dealer:
    symbols: [BTCUSDT, ETHUSDT, SOLUSDT, BNBUSDT, XRPUSDT, ADAUSDT]
```

3. `oms-core-dev.yml` 二选一：

- 方案 1（推荐）：保持 `default-mode: CFD_DEALER`，无需新增映射。
- 方案 2：若默认不是 CFD，则补充：

```yaml
oms:
  execution:
    symbol-mode:
      ADAUSDT: CFD_DEALER
```

4. 在 `api-gateway-dev.yml` 增加限流（建议）：

```yaml
gateway:
  rate-limit:
    per-symbol:
      ADAUSDT: 120
```

### 3.2 步骤 B：创建 Kafka Topic

建议显式创建，避免依赖 broker 自动建 Topic。

必备 Topic（CFD 模式）：

1. `cfd-order-command-ADAUSDT`
2. `order-state-ADAUSDT`
3. `trade-event-ADAUSDT`
4. `trade-entry-ADAUSDT`

示例命令：

```bash
SYMBOL=ADAUSDT
for t in cfd-order-command-$SYMBOL order-state-$SYMBOL trade-event-$SYMBOL trade-entry-$SYMBOL; do
  kafka-topics --create --if-not-exists \
    --bootstrap-server localhost:9092 \
    --topic "$t" --partitions 1 --replication-factor 1
done
```

说明：

1. `binance-data-source` 已支持按 `symbols` 动态生成外部行情 Topic（`market.ext.binance.*`）。
2. 若 Kafka ACL 禁止应用自动建 Topic，需在运维侧提前建好外部行情 Topic。

### 3.3 步骤 C：重启/刷新服务

发布 Nacos 后建议重启以下服务（最稳妥）：

1. `binance-data-source`
2. `cfd-dealer-core`
3. `oms-core`
4. `api-gateway`
5. `liquidation-core`（若涉及其消费配置）

---

## 4. 回归验证清单

### 4.1 行情与盘口

1. 查询新币对深度：

```bash
curl -s "http://localhost:8082/api/binance/depth/ADAUSDT?limit=20" | jq .
```

验收：返回 bids/asks 且更新时间持续推进。

### 4.2 下单与订单状态

1. 提交 `ADAUSDT` 限价单。
2. 查询 OMS 订单列表确认状态从 `NEW/PENDING` 进入 `WORKING/FILLED/CANCELED`。

验收：`order-state-ADAUSDT` 有消费，OMS 状态可推进，不出现“下单成功但状态不更新”。

### 4.3 成交到账本到推送

1. 校验 `trade-event-ADAUSDT` 有消息。
2. 校验 `trade-entry-ADAUSDT` 有消息。
3. 校验私有推送收到账户/持仓变化。

验收：资金、持仓、UPNL 和私有推送一致。

---

## 5. 回滚方案

1. 从 `binance.datasource.symbols` 与 `cfd.dealer.symbols` 移除新币对。
2. OMS 路由从 `symbol-mode` 移除新币对（若配置过）。
3. 保留 Topic 不删除，只停止生产消费（避免误删历史）。
4. 重启相关服务并复测老币对。

---

## 6. 当前可扩展性状态（已收敛）

已完成以下收敛，新增币对不再需要改 Java 代码：

1. `oms-core` 的 `OrderStateConsumer` 已改为 `topicPattern`（默认 `order-state-.*`，可由 Nacos 配置覆盖）。
2. `liquidation-core` 的 `OrderStateConsumer` 已改为 `topicPattern`（默认 `order-state-.*`，可由 Nacos 配置覆盖）。
3. `position-snapshot-core` 的 `TradeEntryEventConsumer` 默认“不过滤 symbol”（消费全部 `trade-entry-*`）；如需收敛范围，可通过 `position.kafka.trade-entry-enforce-whitelist=true` + `trade-entry-symbols` 启用白名单。

当前推荐默认值：

```yaml
position:
  kafka:
    trade-entry-enforce-whitelist: false
    trade-entry-symbols: ""
```

---

## 7. 运维建议（最佳实践）

1. 在 Nacos 新增独立 DataId：`cfd-symbols-dev.yml`，统一维护币对列表。
2. 各服务通过配置引用同一份列表，避免多处手工同步。
3. 增加上线前自动校验脚本：校验“symbol 列表、topic 存在、consumer 已订阅、关键接口可用”。
4. 把新增币对纳入回归脚本：下单、撤单、成交、PNL、强平全链路。
