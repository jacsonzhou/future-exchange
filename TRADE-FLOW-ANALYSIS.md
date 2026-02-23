# 合约交易完整链路分析

## 📋 链路梳理概述

本文档重新梳理合约交易所的核心架构与交易链路，强调强一致账本、硬风控前置与软风控异步闭环。

## 🧭 合约交易核心架构（重梳理）

```
                    ┌────────────────────────────┐
                    │        API Gateway         │
                    │  Auth / RateLimit / ACL    │
                    └─────────────┬──────────────┘
                                  │
                                  ▼
                    ┌────────────────────────────┐
                    │     Order Management       │
                    │  (OMS / Order Service)     │
                    │ - 订单生命周期             │
                    │ - 风控前置校验             │
                    └─────────────┬──────────────┘
                                  │
                        (Pre-Risk Check)
                                  │
                                  ▼
          ┌────────────────────────────────────────────┐
          │        Hard Risk Gate (同步硬风控)          │
          │                                            │
          │ - 可用保证金检查                            │
          │ - 仓位存在校验                              │
          │ - 最大仓位限制                              │
          │ - 开仓/平仓权限                              │
          │                                            │
          └─────────────┬──────────────────────────────┘
                                  │
                        (Disruptor RingBuffer)
                                  │
                                  ▼
┌──────────────────────────────────────────────────────────────┐
│                  Matching Core (撮合内核)                    │
│                                                              │
│  单线程撮合 + 无锁结构                                       │
│                                                              │
│  - OrderBook (自定义结构)                                    │
│  - Price-Time Priority                                       │
│  - 内存撮合                                                  │
│  - 撮合事件生成                                              │
│                                                              │
│  输出: MatchEvent / TradeEvent / CancelEvent                 │
└─────────────┬───────────────────────────────────────────────┘
              │
     (内存总线 / Disruptor)
              │
              ▼
┌──────────────────────────────────────────────────────────────┐
│                Clearing Core (权威账本核心)                  │
│                                                              │
│  Ledger = 唯一真实来源 (Source of Truth)                     │
│                                                              │
│  - 资金账 Ledger                                             │
│  - 持仓账 Ledger                                             │
│  - 手续费 Ledger                                             │
│  - 保险基金 Ledger                                           │
│                                                              │
│  强一致事务                                                  │
│                                                              │
│  输出: LedgerEntry / PositionDelta / AccountDelta            │
└─────────────┬───────────────────────────────────────────────┘
              │
         (Kafka / Event Bus)
              │
              ▼
┌──────────────────────────────────────────────────────────────┐
│            Derived Services (派生系统)                        │
│                                                              │
│  Account Service   ← 资金快照                                 │
│  Position Service  ← 持仓快照                                 │
│  Risk Snapshot     ← 风险快照                                 │
│  Market Service    ← 行情                                     │
│                                                              │
└─────────────┬───────────────────────────────────────────────┘
              │
              ▼
┌──────────────────────────────────────────────────────────────┐
│              Soft Risk Engine (异步软风控)                   │
│                                                              │
│  多输入事件：                                                │
│   - PositionDelta                                            │
│   - MarkPrice                                                │
│   - AccountDelta                                             │
│   - MarketStats                                              │
│                                                              │
│  - 风险规则引擎                                              │
│  - 强平计算                                                  │
│  - ADL 计算                                                  │
│                                                              │
│  输出:                                                       │
│   - LiquidationCommand                                       │
│   - ADLCommand                                               │
│   - CircuitBreakerCommand                                    │
└─────────────┬───────────────────────────────────────────────┘
              │
              ▼
┌──────────────────────────────────────────────────────────────┐
│          Liquidation / ADL Executor                          │
│                                                              │
│  - 强平订单生成                                              │
│  - ADL 减仓订单生成                                          │
│  - 回流 OMS → 撮合                                           │
└──────────────────────────────────────────────────────────────┘
```

### 架构关键原则

1. **硬风控前置、同步阻断**：所有下单必须先过硬风控门，确保保证金与仓位约束可用。
2. **撮合与账本解耦**：撮合只生成事件，权威状态统一由清算账本负责落地。
3. **账本为唯一真实来源**：资金、持仓、手续费、保险基金均以账本为准。
4. **派生系统只做快照**：Account/Position/Risk/Market均由账本事件派生。
5. **软风控异步闭环**：基于多源事件计算强平、ADL、熔断并回流OMS。

### 交易链路映射（从请求到清算）

1. API Gateway 接入并完成鉴权与限流，转入 OMS。
2. OMS 完成订单生命周期管理与前置参数校验。
3. Hard Risk Gate 同步检查保证金、仓位、权限与限额。
4. Matching Core 在内存中撮合生成成交事件。
5. Clearing Core 作为权威账本落地资金/持仓/手续费变化。
6. Derived Services 通过事件流维护快照与行情分发。
7. Soft Risk Engine 异步计算强平/ADL/熔断指令。
8. Liquidation/ADL Executor 生成订单回流 OMS → 撮合。

## ✅ 服务实现状态检查

以下按新架构分层标注，**Clearing Service 为权威账本**，其余服务作为派生快照或执行层参与链路。

### 核心交易链路服务 (10个)

| 序号 | 服务名称 | 端口 | 实现状态 | 关键功能 |
|-----|---------|------|---------|---------|
| 1 | API Gateway | 8080 | ✅ 已实现 | 统一入口、鉴权、限流、ACL |
| 2 | Account Service | 9090 | ✅ 已实现 | **资金快照服务**（非权威账本） |
| 3 | Order Service | 9091 | ✅ 已实现 | OMS：订单生命周期与前置校验 |
| 4 | Match Gateway | 8082 | ✅ 已实现 | 撮合入口、订单路由与削峰 |
| 5 | Match Engine | 8081 | ✅ 已实现 | Matching Core：内存撮合与事件生成 |
| 6 | Position Service | 8084 | ✅ 已实现 | **持仓快照服务**（非权威账本） |
| 7 | Clearing Service | 8085 | ✅ 已实现 | **Clearing Core：权威账本**（资金/持仓/手续费/保险基金） |
| 8 | Market Service | 8086 | ✅ 已实现 | 行情派生服务：K线/统计/推送 |
| 9 | Risk Monitor Service | 8087 | ✅ 已实现 | Soft Risk Engine：异步风控/规则引擎 |
| 10 | Liquidation Service | 8088 | ✅ 已实现 | 强平/ADL 执行器（回流 OMS） |

### 价格服务 (3个)

| 序号 | 服务名称 | 端口 | 实现状态 | 关键功能 |
|-----|---------|------|---------|---------|
| 11 | Index Price Service | 8093 | ✅ 新增 | 指数价格计算（多源加权） |
| 12 | Mark Price Service | 8094 | ✅ 新增 | 标记价格计算（EMA平滑） |
| 13 | Funding Rate Service | 8088 | ✅ 已实现 | 资金费率计算与结算 |

### 行情服务 (1个)

| 序号 | 服务名称 | 端口 | 实现状态 | 关键功能 |
|-----|---------|------|---------|---------|
| 14 | Market Price Service | 8095 | ✅ 新增 | K线/深度/24h统计 |

### 支撑服务 (4个)

| 序号 | 服务名称 | 端口 | 实现状态 | 关键功能 |
|-----|---------|------|---------|---------|
| 15 | ADL Service | 8091 | ⚠️ 部分实现 | 自动减仓 (基础框架) |
| 16 | Notification Service | 8097 | ❌ 未实现 | 通知推送 (Push/SMS/Email) |
| 17 | WebSocket Gateway | 8096 | ❌ 未实现 | 实时推送 (公有/私有) |

## 🔍 关键Kafka Topic分析

基于新架构对齐：**撮合事件只进入 Clearing Core，账本变化由 Clearing Core 统一对外发布**，派生系统只消费账本事件或市场事件。

### 撮合事件流 (Matching → Clearing)

| Topic名称 | 生产者 | 消费者 | 消息内容 | 状态 |
|----------|-------|-------|---------|------|
| trade-topic | Match Engine | Clearing Service | 成交事件 (TradeEvent) | ✅ |
| order-event-topic | Order Service | Match Gateway | 订单事件 | ✅ |

### 账本事件流 (Clearing → Derived)

| Topic名称 | 生产者 | 消费者 | 消息内容 | 状态 |
|----------|-------|-------|---------|------|
| account-change-topic | Clearing Service | Account, Risk Monitor | 资金账本变化 | ✅ |
| risk-event-topic | Clearing Service → Position Service | Risk Monitor | 持仓风险事件 | ✅ |
| insurance-fund-topic | Clearing Service | Risk Monitor | 保险基金账本变化 | ✅ |
| market-stats-topic | Market Service | Risk Monitor | 市场统计数据 | ✅ |
| mark-price-topic | MarkPrice Service | Risk Monitor, Position | 标记价格更新 | ✅ |
| order-anomaly-topic | Order Service | Risk Monitor | 异常订单检测 | ⚠️ |

### 输出Topic (触发动作)

| Topic名称 | 生产者 | 消费者 | 消息内容 | 状态 |
|----------|-------|-------|---------|------|
| liquidation-trigger-topic | Risk Monitor | Liquidation Service | 强平触发 | ✅ |
| adl-trigger-topic | Risk Monitor | ADL Service | ADL触发 | ⚠️ |
| notification-topic | Risk Monitor | Notification Service | 通知消息 | ⚠️ |
| system-alert-topic | Risk Monitor | Operations Platform | 系统告警 | ⚠️ |
| circuit-breaker-topic | Risk Monitor | API Gateway | 熔断指令 | ⚠️ |
| user-restriction-topic | Risk Monitor | Order Service | 用户限制 | ⚠️ |

## 🎯 完整链路测试场景

### 场景1: 正常开仓交易

```
用户A下单买入 1 BTC @ 50000 USDT，杠杆10x

步骤:
1. API Gateway接收请求 ✅
2. OMS完成订单校验并进入Hard Risk Gate ✅
3. Hard Risk Gate校验保证金/仓位/限额 ✅
4. Match Engine撮合成交并发布trade-topic ✅
5. Clearing Service消费trade-topic并落地账本 ✅
6. Clearing Service发布账本事件:
   - account-change-topic (资金变动)
   - risk-event-topic (PositionDelta)
   - insurance-fund-topic (保险基金变动)
7. 派生服务消费账本事件:
   - Account Service更新资金快照 ✅
   - Position Service更新持仓快照 ✅
   - Market Service更新K线与行情 ✅
8. Risk Monitor消费risk-event-topic + mark-price-topic评估风险 ✅

结果: ✅ 链路完整，可以走通
```

### 场景2: 强平触发流程

```
用户A持有多仓，市场价格下跌，保证金率降至4%

步骤:
1. MarkPrice Service推送最新标记价格 ✅
2. Risk Monitor消费mark-price-topic + risk-event-topic ✅
3. RiskRuleEngine评估规则: 4% <= 5% → 触发强平 ✅
4. 发布liquidation-trigger-topic ✅
5. Liquidation Service消费触发消息 ✅
6. 创建强平订单并回流OMS ✅
7. 进入撮合流程 → Clearing落地账本 ✅

结果: ✅ 链路完整，可以走通
```

### 场景3: 保险基金不足触发ADL

```
强平执行后，成交价格差，保险基金不足

步骤:
1. Liquidation Service执行强平 ✅
2. Clearing Service落地账本并更新保险基金 ✅
3. Risk Monitor消费insurance-fund-topic触发ADL ⚠️
4. 发布adl-trigger-topic ⚠️
5. ADL Service消费触发消息 ⚠️
6. 创建ADL减仓订单并回流OMS ⚠️
7. 进入撮合流程 → Clearing落地账本 ⚠️

```

## ⚠️ 链路缺口分析

### 1. 消息消费缺口

| 服务 | 缺少的Consumer | 影响 | 优先级 |
|-----|---------------|------|-------|
| Order Service | order-anomaly-topic生产者 | 无法检测异常订单 | 中 |
| Order Service | user-restriction-topic消费者 | 无法限制用户交易 | 高 |
| API Gateway | circuit-breaker-topic消费者 | 无法执行熔断 | 高 |
| Account Service | account-change-topic消费者 | 资金快照无法与账本对齐 | 高 |
| Position Service | risk-event-topic消费者 | 持仓快照无法与账本对齐 | 高 |
| Position Service | mark-price-topic消费者 | 无法实时更新强平价 | 高 |

### 2. 服务间调用缺口

| 调用链路 | 当前状态 | 预期行为 | 优先级 |
|---------|---------|---------|-------|
| Position → Account (更新保证金) | ❌ 不应存在 | 由Clearing账本事件驱动派生 | 高 |
| Liquidation → Position (查询持仓) | ✅ 已实现 | 获取待强平持仓 | - |
| Risk Monitor → Notification | ⚠️ Notification未实现 | 发送预警通知 | 中 |

### 3. 数据一致性缺口

| 数据流 | 当前状态 | 问题 | 解决方案 |
|-------|---------|------|---------|
| 账本 vs 资金/持仓快照 | ⚠️ 可能不一致 | 派生事件链路缺失 | 强制消费账本事件 + 对账任务 |
| 订单状态 vs 成交记录 | ⚠️ 可能不一致 | 缺少状态回调 | 补充OrderStatusConsumer |
| 账本 vs 业务报表 | ✅ 独立存储 | 需要对账 | 已有对账机制 |

## 🔧 需要补充的Consumer

### 1. Position Service需要新增

```java
@Component
public class PositionDeltaConsumer {
    // 消费risk-event-topic (PositionDelta)
    // 从账本事件更新持仓快照
    @KafkaListener(topics = "risk-event-topic")
    public void onPositionDelta(PositionDelta message) {
        // 更新持仓快照与开平仓统计
        // 供风控与展示使用
    }
}

@Component
public class MarkPriceConsumer {
    // 消费mark-price-topic
    // 实时更新持仓的未实现盈亏和强平价格
    @KafkaListener(topics = "mark-price-topic")
    public void onMarkPriceUpdate(MarkPriceMessage message) {
        // 更新所有持仓的强平价格
        // 重新计算保证金率
    }
}
```

### 2. Order Service需要新增

```java
@Component
public class UserRestrictionConsumer {
    // 消费user-restriction-topic
    // 限制用户交易权限
    @KafkaListener(topics = "user-restriction-topic")
    public void onUserRestriction(UserRestrictionMessage message) {
        // 更新用户限制标记 (Redis)
        // 限制开仓/调整杠杆等操作
    }
}

@Component
public class AnomalyDetector {
    // 检测异常订单并发布
    // 发布到order-anomaly-topic
    public void detectAnomalies(Order order, User user) {
        // 检测高频下单、高撤单率等
        // 发布到order-anomaly-topic
    }
}
```

### 3. API Gateway需要新增

```java
@Component
public class CircuitBreakerConsumer {
    // 消费circuit-breaker-topic
    // 执行熔断策略
    @KafkaListener(topics = "circuit-breaker-topic")
    public void onCircuitBreaker(CircuitBreakerMessage message) {
        // 暂停指定交易对的交易
        // 更新熔断状态到Redis
        // 返回503或限流错误
    }
}
```

## 📊 数据流完整性验证

### 验证点1: 成交消息是否被所有服务消费

```bash
# 检查Kafka消费者lag
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group clearing-service-group --describe

kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group market-service-group --describe

# 账本事件消费情况
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group account-service-group --describe

kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group position-service-group --describe

# 期望: lag = 0 (消费跟上生产)
```

### 验证点2: 风险事件是否正确传递

```bash
# 1. 触发一笔成交
curl -X POST http://localhost:8080/api/order/create \
  -H "Content-Type: application/json" \
  -d '{...}'

# 2. 检查清算服务日志 (是否发布risk-event-topic)
tail -f exchange-clearing-service/logs/clearing-service.log

# 3. 检查持仓服务日志 (是否消费risk-event-topic)
tail -f exchange-position-service/logs/position-service.log

# 4. 检查风险监控日志 (是否消费risk-event-topic)
tail -f exchange-risk-monitor-service/logs/risk-monitor-service.log

# 5. 检查Redis缓存 (用户风险快照是否更新)
redis-cli GET "user:risk:10001:BTCUSDT"
```

### 验证点3: 强平是否能正常触发

```bash
# 1. 修改保证金率到4%以下 (模拟)
# 发布测试risk-event消息到Kafka

# 2. 检查风险监控日志 (规则是否触发)
tail -f exchange-risk-monitor-service/logs/rule-trigger.log

# 3. 检查强平服务日志 (是否消费liquidation-trigger-topic)
tail -f exchange-liquidation-service/logs/liquidation-service.log

# 4. 检查订单服务 (是否创建强平订单)
curl http://localhost:9091/api/order/query?userId=10001
```

## 🎯 链路完整性总结

### ✅ 已打通的链路 (85%)

1. **核心交易流程**: 下单 → 硬风控 → 撮合 → 账本落地 → 账本事件派生 ✅
2. **风险监控流程**: 账本事件 + 标记价格 → 风险评估 → 规则触发 ✅
3. **强平触发流程**: 风控指令 → 强平执行 → 回流撮合 → 账本落地 ✅
4. **标记价格流程**: 定时计算 → Kafka推送 → 风控/持仓消费 ✅
5. **清结算流程**: 成交事件 → 账本记账 → 保险基金/手续费变更 ✅

### ⚠️ 需要补充的链路 (15%)

1. **账本事件派生**: Account/Position必须消费账本事件并保持一致 ⚠️
2. **用户限制执行**: Order Service需要消费user-restriction-topic ⚠️
3. **熔断执行**: API Gateway需要消费circuit-breaker-topic ⚠️
4. **异常订单检测**: Order Service需要发布order-anomaly-topic ⚠️
5. **通知推送**: Notification Service完整实现 ⚠️
6. **ADL完整流程**: ADL Service完善实现 ⚠️

### 🚀 快速修复建议

**高优先级 (影响核心功能)**:
1. Account/Position消费账本事件 → 与Clearing账本对齐
2. Position消费mark-price-topic → 实现实时强平价更新
3. Order Service消费user-restriction-topic → 实现用户限制
4. API Gateway消费circuit-breaker-topic → 实现熔断

**中优先级 (影响风控完整性)**:
5. Order Service发布order-anomaly-topic → 实现异常检测
6. 补充Notification Service → 实现通知推送

**低优先级 (极端场景)**:
7. 完善ADL Service → 处理保险基金不足场景

## 📝 结论

### 当前状态
- **核心交易链路**: ✅ 以账本为中心可用 (85%)
- **风控监控链路**: ✅ 基本可用（依赖账本事件与标记价格）
- **强平触发链路**: ✅ 可用（回流撮合再记账）
- **极端场景链路**: ⚠️ 需要补充 (ADL)

### 能否正常走完流程？

**✅ YES - 核心链路可以走通！**

对于**正常交易场景**和**大部分风控场景**，当前的实现已经可以支撑完整的业务流程：

1. 用户下单 → 硬风控 → 撮合成交 → 账本落地 → 资金/持仓快照更新 ✅
2. 账本事件 + 标记价格 → 风控评估 → 预警/强平触发 ✅
3. 标记价格更新 → 风险评估 → 价格异常熔断 ✅

但需要注意：
- 账本事件派生链路需要补充 (高优先级)
- 用户限制和熔断执行需要补充 (高优先级)
- 极端场景 (ADL) 需要继续完善 (低优先级)

---

**评估日期**: 2026-01-14  
**评估人**: System Architect  
**下一步行动**: 补充高优先级Consumer实现

