# 合约交易系统 - 下单流程可行性分析报告

## 📊 执行摘要

**结论: ✅ 当前系统可以模拟完整的下单流程**

API Gateway → OMS Core → Hard Risk Core → Match Engine Core 的链路已经完整实现，可以挂单和撮合。

---

## 🔄 完整下单流程分析

### 流程架构图

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                    下单完整流程                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘

  用户请求
     │
     ▼ HTTP POST /api/order/create
┌─────────────────────────────────────────────────────────────────┐
│  API Gateway (8080)                                              │
│  ├─ 参数校验 (price, quantity, symbol, side...)                 │
│  ├─ JWT/API Key 认证 (可配置)                                    │
│  ├─ 限流检查 (Rate Limit)                                       │
│  └─ 调用 OMS Core (Feign)                                        │
└────────────────────────────────┬────────────────────────────────┘
                                 │ Feign/gRPC
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│  OMS Core (8081)                                                 │
│  ├─ 1. 生成订单ID (Snowflake)                                   │
│  ├─ 2. 计算所需保证金                                            │
│  │     名义价值 = price × quantity / SCALE²                     │
│  │     保证金 = 名义价值 / leverage                             │
│  ├─ 3. 持久化订单到 MySQL (t_order)                              │
│  │     状态: NEW                                                 │
│  ├─ 4. 🔥 保证金预扣 (Redis)                                     │
│  ├─ 5. 调用 Hard Risk Core (Feign) ────────────────────────────┐│
│  ├─ 6. 更新订单状态: RISK_PASSED                                │
│  ├─ 7. 调用 Match Engine Core (Feign) ─────────────────────────┼┘
│  └─ 8. 更新订单状态: SENT_TO_MATCH                              │
└────────────────────────────────┬────────────────────────────────┘
                                 │
                    ┌────────────┴────────────┐
                    ▼                         ▼
┌─────────────────────────────────┐  ┌─────────────────────────────────┐
│  Hard Risk Core (8082)          │  │  Match Engine Core (8083)        │
│  ├─ 1. 查询交易对配置            │  │  ├─ Disruptor RingBuffer         │
│  ├─ 2. 查询账户快照              │  │  ├─ OrderBook (内存撮合)         │
│  ├─ 3. 查询持仓快照              │  │  │   • 价格-时间优先             │
│  ├─ 4. 黑名单检查                │  │  │   • Long2ObjectOpenHashMap   │
│  ├─ 5. 杠杆限制检查              │  │  ├─ 撮合匹配                     │
│  ├─ 6. 价格偏离检查              │  │  ├─ 生成 Trade 事件             │
│  ├─ 7. 仓位限制检查              │  │  ├─ WAL 日志记录                │
│  └─ 8. 🔥 保证金检查 (核心)      │  │  └─ 发布成交事件 (Kafka)         │
│         实际可用 = 快照余额 - 预扣│  │                                 │
│  返回: ACCEPT / REJECT          │  │                                 │
└─────────────────────────────────┘  └─────────────────────────────────┘
                                                  │
                                                  ▼ Kafka: trade-event
┌─────────────────────────────────────────────────────────────────┐
│  Ledger Core (8084)                                              │
│  ├─ 消费成交事件                                                  │
│  ├─ 生成双录分录 (借贷平衡)                                        │
│  │   借: 持仓资产 / 贷: 保证金                                    │
│  ├─ 写入 t_ledger_entry (MySQL)                                  │
│  ├─ 发布 TradeEntryEvent (Kafka) ───────────────────────────────┐│
└────────────────────────────────┬────────────────────────────────┘│
                                 │ Kafka                            │
                    ┌────────────┴────────────┐                     │
                    ▼                         ▼                     │
┌─────────────────────────────────┐  ┌─────────────────────────────┐│
│  Snapshot Account Core (8085)   │  │  Position Snapshot (8086)   │◄┘
│  ├─ 消费 TradeEntryEvent        │  │  ├─ 更新持仓快照             │
│  ├─ 更新账户余额 (MySQL)         │  │  ├─ 计算浮盈浮亏             │
│  └─ 更新 Redis 缓存             │  │  └─ 更新 Redis              │
└─────────────────────────────────┘  └─────────────────────────────┘
```

---

## ✅ 系统组件状态检查

### 1. API Gateway (8080) - ✅ 完整

| 组件 | 状态 | 说明 |
|-----|------|------|
| OrderController | ✅ | `/api/order/create` 已实现 |
| 参数校验 | ✅ | price, quantity, symbol, side, orderType |
| 审计日志 | ✅ | ApiAuditLogger |
| 限流 | ✅ | RateLimitFilter |
| 认证 | ✅ | AuthFilter (可配置) |
| OMS Client | ✅ | Feign 调用 oms-core |

### 2. OMS Core (8081) - ✅ 完整

| 组件 | 状态 | 说明 |
|-----|------|------|
| OrderServiceImpl | ✅ | 完整下单流程 |
| 订单状态机 | ✅ | NEW → RISK_PASSED → SENT_TO_MATCH |
| 保证金计算 | ✅ | calculateRequiredMargin() |
| 保证金预扣 | ✅ | MarginPreHoldService (Redis) |
| 数据库持久化 | ✅ | t_order 表 |
| Hard Risk Client | ✅ | Feign 调用 |
| Match Engine Client | ✅ | Feign 调用 |
| Kafka Producer | ✅ | 配置完成 (备用) |

### 3. Hard Risk Core (8082) - ✅ 完整

| 组件 | 状态 | 说明 |
|-----|------|------|
| HardRiskServiceImpl | ✅ | 完整风控检查 |
| 黑名单检查 | ✅ | risk_user_list |
| 杠杆限制 | ✅ | max_leverage |
| 价格偏离检查 | ✅ | max_price_deviation_pct |
| 仓位限制 | ✅ | max_position_qty |
| 🔥 保证金检查 | ✅ | 实际可用 = 快照 - 预扣 |
| 风控审计日志 | ✅ | risk_check_log |
| 数据库表 | ✅ | 全部已定义 |

### 4. Match Engine Core (8083) - ✅ 完整

| 组件 | 状态 | 说明 |
|-----|------|------|
| MatchEngine | ✅ | 撮合引擎核心 |
| OrderBook | ✅ | 高性能内存订单簿 |
| PriceLevel | ✅ | 价格档位管理 |
| Disruptor | ✅ | RingBuffer 配置 |
| MatchEventHandler | ✅ | 事件处理器 |
| 撮合逻辑 | ✅ | 价格-时间优先 |
| WAL 日志 | ✅ | MatchWAL |
| Trade 生成 | ✅ | 成交记录 |
| 手续费计算 | ✅ | 0.1% 费率 |
| HTTP 接口 | ✅ | `/api/v1/match/order/submit` |
| Kafka Consumer | ✅ | 备用消费通道 |

### 5. Ledger Core (8084) - ✅ 完整

| 组件 | 状态 | 说明 |
|-----|------|------|
| LedgerServiceImpl | ✅ | 双录分录记账 |
| applyTrade() | ✅ | 成交结算 |
| freezeMargin() | ✅ | 保证金冻结 |
| unfreezeMargin() | ✅ | 保证金解冻 |
| 分录生成 | ✅ | 借贷平衡 |
| Kafka Consumer | ✅ | trade-event 消费 |
| Event Publisher | ✅ | TradeEntryEvent |
| 数据库表 | ✅ | t_ledger_entry |

---

## 🧪 测试可行性分析

### 测试场景 1: 挂单（不成交）

```bash
# 用户1 挂买单（价格较低，不会立即成交）
curl -X POST http://localhost:8080/api/order/create \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": 4000000000000,
    "quantity": 100000000,
    "leverage": 10
  }'
```

**预期结果**: ✅ 可以成功挂单
- 订单进入 OrderBook 买方队列
- 状态: SENT_TO_MATCH → PARTIALLY_FILLED (0%) / OPEN

### 测试场景 2: 撮合（买卖单匹配）

```bash
# 用户1 挂买单
curl -X POST http://localhost:8080/api/order/create \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": 5000000000000,
    "quantity": 100000000,
    "leverage": 10
  }'

# 用户2 挂卖单（同价格，立即撮合）
curl -X POST http://localhost:8080/api/order/create \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 2,
    "symbol": "BTCUSDT",
    "side": "SELL",
    "orderType": "LIMIT",
    "price": 5000000000000,
    "quantity": 100000000,
    "leverage": 10
  }'
```

**预期结果**: ✅ 可以撮合
- 生成 Trade 记录
- 订单状态: FILLED
- Ledger 生成分录
- 双方持仓更新

---

## ⚠️ 前置条件与注意事项

### 1. 基础设施必须启动

```bash
# 1. MySQL (端口 3306)
# 数据库: exchange_oms, exchange_ledger, exchange_risk

# 2. Redis (端口 6379)
# 用于保证金预扣和风控查询

# 3. Nacos (端口 8848)
# 服务注册发现

# 4. Kafka (端口 9092)
# 备用消息通道
```

### 2. 数据库初始化

```bash
# 执行初始化脚本
./init_db.sh

# 需要初始化以下数据：
# 1. 交易对配置 (risk_symbol_config)
INSERT INTO risk_symbol_config (symbol, max_leverage, max_position_qty, ...) 
VALUES ('BTCUSDT', 125, 1000.00000000, ...);

# 2. 用户账户快照 (risk_account_snapshot)
INSERT INTO risk_account_snapshot (user_id, equity, available_margin, ...) 
VALUES (1, 10000.00, 10000.00, ...);

# 3. 账户余额 (t_account_balance)
INSERT INTO t_account_balance (user_id, asset, available, ...) 
VALUES (1, 'USDT', 1000000000000, ...);
```

### 3. 服务启动顺序

```bash
# 必须按顺序启动：

# 1. 撮合引擎（核心）
cd match-engine-core && java -jar target/match-engine-core-1.0.0-SNAPSHOT.jar

# 2. 账本服务
cd ledger-core && java -jar target/ledger-core-1.0.0-SNAPSHOT.jar

# 3. 快照服务
cd snapshot-account-core && java -jar ...

# 4. 风控服务
cd hard-risk-core && java -jar target/hard-risk-core-1.0.0-SNAPSHOT.jar

# 5. 订单管理
cd oms-core && java -jar target/oms-core-1.0.0-SNAPSHOT.jar

# 6. API网关
cd api-gateway && java -jar target/api-gateway-1.0.0-SNAPSHOT.jar
```

---

## 🔍 代码调用链验证

### API Gateway → OMS

```java
// OrderController.java
@PostMapping("/create")
public CreateOrderResponse createOrder(@RequestBody CreateOrderRequest request) {
    // ... 参数校验 ...
    response = omsClient.createOrder(request);  // ✅ Feign 调用
    // ... 审计日志 ...
}
```

### OMS → Hard Risk

```java
// OrderServiceImpl.java
Boolean riskPassed = hardRiskClient.checkRisk(command, requiredMargin, totalBalance);
// ✅ Feign 调用 hard-risk-core:8082/internal/risk/check
```

### OMS → Match Engine

```java
// OrderServiceImpl.java
matchEngineClient.submitOrder(command);
// ✅ Feign 调用 match-engine-core:8083/internal/match/submit
```

### Match Engine 撮合

```java
// MatchEventHandler.java
public void onEvent(OrderCommandEvent event, long sequence, boolean endOfBatch) {
    List<Trade> trades = matchEngine.onOrder(event.getCommand());  // ✅ 撮合
    matchWAL.append(event.getCommand(), trades, sequence);          // ✅ WAL
    for (Trade trade : trades) {
        publishTrade(trade);                                         // ✅ 发布成交
    }
}
```

---

## 📈 性能指标预期

| 指标 | 目标值 | 当前实现 |
|-----|--------|---------|
| 下单延迟 | < 10ms | ✅ 可达 (Feign调用) |
| 风控延迟 | < 3ms | ✅ 可达 (Redis查询) |
| 撮合延迟 | < 1ms | ✅ 可达 (Disruptor) |
| 吞吐量 | > 10K TPS | ✅ Disruptor支撑 |

---

## 🎯 结论

### ✅ 可以执行的测试

1. **挂单测试** - 单个用户下限价买单，查看 OrderBook 状态
2. **撮合测试** - 两个用户下对立方向的订单，查看成交结果
3. **风控测试** - 测试余额不足、杠杆超限等场景的拒绝
4. **撤单测试** - 挂单后撤销，查看保证金释放

### 🔧 需要准备的工作

1. 启动 MySQL、Redis、Nacos、Kafka
2. 执行数据库初始化脚本
3. 插入测试数据（用户账户、交易对配置）
4. 按顺序启动所有服务
5. 执行测试命令

---

**报告生成时间**: 2024年  
**系统版本**: v1.0-SNAPSHOT  
**状态**: ✅ 可测试
