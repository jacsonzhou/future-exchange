# Liquidation Service 产品需求文档 (PRD)

> 版本: v1.0  
> 日期: 2024年  
> 对标: Binance / OKX / Bybit 合约强平系统

---

## 1. 产品概述

### 1.1 产品背景

在合约交易系统中，当用户仓位保证金率低于维持保证金率时，必须执行强制平仓（Liquidation）以保护交易所和其他用户。强平服务是风险控制的最后一道防线，直接影响交易所的资金安全和用户体验。

### 1.2 产品目标

| 目标 | 描述 | 优先级 |
|-----|------|-------|
| 资金安全 | 及时强平，防止穿仓损失扩大 | P0 |
| 低延迟 | 从触发到订单提交 < 100ms | P0 |
| 可观测 | 完整的强平链路追踪和审计 | P0 |
| 公平性 | 强平价格接近市场价格，减少用户损失 | P1 |
| 系统稳定 | 极端行情下仍能正常工作 | P0 |

### 1.3 对标分析

#### Binance
- **强平机制**: 阶梯强平，优先减少保证金需求
- **订单类型**: 市价单 + 限价保护
- **ADL触发**: 强平后保险基金不足时触发
- **性能**: 强平延迟 < 50ms

#### OKX
- **强平机制**: 全仓/逐仓分别处理
- **订单类型**: 市价强平单
- **价格保护**: 强平价与标记价格偏差限制
- **性能**: 支持每秒 1000+ 强平

#### Bybit
- **强平机制**: 双价格机制（标记价格 + 最新价格）
- **保险基金**: 自动赔付穿仓损失
- **ADL**: 盈利最高的用户优先减仓
- **用户体验**: 强平前推送预警通知

---

## 2. 功能需求

### 2.1 核心功能模块

```
┌─────────────────────────────────────────────────────────────────┐
│                    Liquidation Service (端口 8088)               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐ │
│  │  Event Consumer │  │  Risk Validator │  │  Order Creator  │ │
│  │  (Kafka)        │  │  (二次校验)      │  │  (强平订单)      │ │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘ │
│           │                    │                    │          │
│           └────────────────────┼────────────────────┘          │
│                                ▼                               │
│                    ┌─────────────────────┐                     │
│                    │  Execution Engine   │                     │
│                    │  (执行引擎)          │                     │
│                    └──────────┬──────────┘                     │
│                               │                                │
│           ┌───────────────────┼───────────────────┐           │
│           ▼                   ▼                   ▼           │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐│
│  │  Order Monitor  │  │  PnL Calculator │  │  Event Producer ││
│  │  (订单监控)      │  │  (盈亏计算)      │  │  (Kafka)       ││
│  └─────────────────┘  └─────────────────┘  └─────────────────┘│
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 功能清单

#### 2.2.1 强平事件消费 (LIQ-001)

**需求描述**: 消费 Margin-Mode-Service 发布的强平触发事件

**输入**:
```json
{
  "userId": 12345,
  "positionId": 67890,
  "symbol": "BTCUSDT",
  "marginMode": "ISOLATED",
  "triggerType": "MARGIN_RATIO",
  "marginRatio": 500,
  "liquidationThreshold": 1000,
  "markPrice": 4500000000000,
  "liquidationPrice": 4600000000000,
  "bankruptcyPrice": 4400000000000,
  "positionSide": 1,
  "positionQty": 100000000,
  "entryPrice": 5000000000000,
  "currentMargin": 500000000000,
  "maintenanceMargin": 100000000,
  "unrealizedPnl": -50000000000,
  "leverage": 10,
  "priority": 2,
  "timestamp": 1704067200000,
  "sequence": 1
}
```

**处理逻辑**:
1. 幂等性检查 (基于 positionId + timestamp)
2. 去重窗口: 5分钟内同一仓位不重复强平
3. 并发控制: 同一用户串行处理

**验收标准**:
- [ ] 能正确消费 Kafka 事件
- [ ] 幂等性保证 (重复事件不处理)
- [ ] 消费延迟 < 10ms (P99)

---

#### 2.2.2 强平订单创建 (LIQ-002)

**需求描述**: 根据持仓信息创建强平订单

**订单参数**:
```java
public class LiquidationOrderRequest {
    private Long userId;              // 用户ID
    private String symbol;            // 交易对
    private String side;              // BUY/SELL (平仓方向)
    private String orderType;         // MARKET (市价单)
    private Long quantity;            // 数量 (全仓强平)
    private Boolean reduceOnly;       // true (只减仓)
    private String orderSource;       // "LIQUIDATION"
    private Long liquidationPrice;    // 强平价格 (用于记录)
    private Long bankruptcyPrice;     // 破产价格 (用于记录)
    private String positionId;        // 关联仓位ID
    private String triggerType;       // 触发类型
}
```

**业务规则**:
1. **全仓强平**: 市价单，数量 = 持仓数量
2. **价格保护**: 如果标记价格与最新价格偏差 > 5%，使用限价单 (标记价格 ± 1%)
3. **分仓强平**: 如果仓位 > 100 BTC，分多笔订单 (防止冲击市场)
4. **订单来源**: 必须标记为 "LIQUIDATION"，便于OMS识别

**验收标准**:
- [ ] 市价单在 100ms 内提交到 OMS
- [ ] 大仓位自动拆分
- [ ] 价格偏离时自动切换为限价单

---

#### 2.2.3 订单执行监控 (LIQ-003)

**需求描述**: 监控强平订单的执行状态

**监控周期**: 每 100ms 查询一次订单状态

**状态流转**:
```
PENDING → SUBMITTED → PARTIALLY_FILLED → FILLED
   ↓           ↓              ↓
FAILED    CANCELLED      EXPIRED
```

**处理逻辑**:
1. **完全成交**: 计算实际盈亏，发布完成事件
2. **部分成交**: 继续监控剩余数量
3. **订单失败**: 重试 (最多3次)，失败后人工介入
4. **超时处理**: 30秒未成交，取消并重新提交

**验收标准**:
- [ ] 实时监控订单状态
- [ ] 失败自动重试 (指数退避)
- [ ] 超时自动处理

---

#### 2.2.4 盈亏计算 (LIQ-004)

**需求描述**: 计算强平后的实际盈亏

**计算公式**:
```
// 已实现盈亏
Realized PnL = (Exit Price - Entry Price) × Quantity × Direction

// 穿仓损失 (如果为负)
Bankrupt Loss = min(Realized PnL + Initial Margin, 0)

// 保险基金赔付
Insurance Cover = min(|Bankrupt Loss|, Insurance Fund Balance)

// 剩余穿仓 (需要ADL)
Remaining Loss = |Bankrupt Loss| - Insurance Cover
```

**数据精度**:
- 价格: 8位小数 (long存储)
- 数量: 8位小数 (long存储)
- 盈亏: 8位小数 (long存储)

**验收标准**:
- [ ] 盈亏计算准确 (与Ledger一致)
- [ ] 穿仓损失识别正确
- [ ] 数据精度无丢失

---

#### 2.2.5 强平完成事件 (LIQ-005)

**需求描述**: 发布强平完成事件，供ADL服务消费

**输出事件**:
```json
{
  "eventType": "LIQUIDATION_COMPLETED",
  "liquidationId": "LIQ_1704067200123_12345",
  "userId": 12345,
  "positionId": 67890,
  "symbol": "BTCUSDT",
  "side": "LONG",
  "marginMode": "ISOLATED",
  "isBankrupt": true,
  "bankruptPrice": 4400000000000,
  "bankruptQty": 100000000,
  "bankruptLoss": 50000000000,
  "insuranceCover": 30000000000,
  "remainingLoss": 20000000000,
  "adlRequired": true,
  "executedPrice": 4450000000000,
  "executedQty": 100000000,
  "realizedPnl": -55000000000,
  "timestamp": 1704067201000,
  "sequence": 1001
}
```

**事件属性**:
| 字段 | 说明 | 示例 |
|-----|------|------|
| isBankrupt | 是否穿仓 | true |
| bankruptLoss | 穿仓损失 | 500 USDT |
| insuranceCover | 保险基金赔付 | 300 USDT |
| adlRequired | 是否需要ADL | true (如果 remainingLoss > 0) |

**验收标准**:
- [ ] 事件格式符合规范
- [ ] 成功发布到 Kafka
- [ ] 包含完整的盈亏信息

---

#### 2.2.6 保险基金交互 (LIQ-006)

**需求描述**: 与保险基金服务交互，赔付穿仓损失

**交互流程**:
```
1. 计算穿仓损失
2. 查询保险基金余额
3. 申请赔付 (expense)
4. 记录赔付明细
```

**幂等性**: 使用 liquidationId 作为 bizSeq，防止重复赔付

**验收标准**:
- [ ] 正确调用保险基金服务
- [ ] 幂等性保证
- [ ] 赔付记录可追溯

---

## 3. 非功能需求

### 3.1 性能要求

| 指标 | 目标 | 峰值 |
|-----|------|------|
| 事件消费延迟 | < 10ms (P99) | < 50ms |
| 订单创建延迟 | < 50ms (P99) | < 100ms |
| 订单监控周期 | 100ms | - |
| 吞吐量 | 1000 TPS | 5000 TPS |
| 端到端延迟 | < 200ms (P99) | < 500ms |

### 3.2 可靠性要求

1. **高可用**: 多实例部署，自动故障转移
2. **数据一致性**: 强平记录与订单记录一致 (最终一致性)
3. **幂等性**: 同一仓位5分钟内只强平一次
4. **降级策略**: OMS不可用时，缓存事件，恢复后重试

### 3.3 监控告警

| 告警项 | 阈值 | 级别 |
|-------|------|------|
| 强平队列堆积 | > 100 | P1 |
| 强平失败率 | > 1% | P0 |
| 订单提交延迟 | > 100ms | P1 |
| 穿仓损失突增 | > 10万 USDT/小时 | P0 |
| 服务不可用 | - | P0 |

---

## 4. 数据模型

### 4.1 强平执行表 (t_liquidation_execution)

```sql
CREATE TABLE IF NOT EXISTS `t_liquidation_execution` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `liquidation_id` VARCHAR(64) NOT NULL COMMENT '强平ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `position_id` BIGINT NOT NULL COMMENT '仓位ID',
    `symbol` VARCHAR(32) NOT NULL COMMENT '交易对',
    `margin_mode` VARCHAR(16) NOT NULL COMMENT '保证金模式: ISOLATED/CROSS',
    `position_side` TINYINT NOT NULL COMMENT '仓位方向: 1=多, 2=空',
    
    -- 触发信息
    `trigger_type` VARCHAR(32) NOT NULL COMMENT '触发类型: MARGIN_RATIO/MARK_PRICE',
    `trigger_price` BIGINT NOT NULL COMMENT '触发价格',
    `margin_ratio` BIGINT COMMENT '触发时保证金率(万分比)',
    
    -- 订单信息
    `order_id` BIGINT COMMENT 'OMS订单ID',
    `order_type` VARCHAR(16) NOT NULL COMMENT '订单类型: MARKET/LIMIT',
    `side` VARCHAR(8) NOT NULL COMMENT '订单方向: BUY/SELL',
    `quantity` BIGINT NOT NULL COMMENT '订单数量',
    `executed_price` BIGINT COMMENT '成交价格',
    `executed_qty` BIGINT COMMENT '成交数量',
    
    -- 盈亏计算
    `entry_price` BIGINT NOT NULL COMMENT '开仓均价',
    `bankruptcy_price` BIGINT NOT NULL COMMENT '破产价格',
    `realized_pnl` BIGINT COMMENT '已实现盈亏',
    `bankrupt_loss` BIGINT COMMENT '穿仓损失',
    
    -- 保险基金
    `insurance_cover` BIGINT DEFAULT 0 COMMENT '保险基金赔付',
    `remaining_loss` BIGINT DEFAULT 0 COMMENT '剩余穿仓损失',
    `adl_required` TINYINT DEFAULT 0 COMMENT '是否需要ADL: 0=否, 1=是',
    
    -- 状态
    `status` VARCHAR(32) NOT NULL COMMENT '状态: PENDING/SUBMITTED/PARTIALLY_FILLED/FILLED/FAILED',
    `retry_count` INT DEFAULT 0 COMMENT '重试次数',
    `error_msg` VARCHAR(512) COMMENT '错误信息',
    
    -- 时间戳
    `triggered_at` BIGINT NOT NULL COMMENT '触发时间',
    `submitted_at` BIGINT COMMENT '提交时间',
    `filled_at` BIGINT COMMENT '成交时间',
    `created_at` BIGINT NOT NULL COMMENT '创建时间',
    `updated_at` BIGINT NOT NULL COMMENT '更新时间',
    
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_liquidation_id` (`liquidation_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_position_id` (`position_id`),
    KEY `idx_symbol_status` (`symbol`, `status`),
    KEY `idx_triggered_at` (`triggered_at`),
    KEY `idx_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='强平执行记录表';
```

### 4.2 强平订单关联表 (t_liquidation_order)

```sql
CREATE TABLE IF NOT EXISTS `t_liquidation_order` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `liquidation_id` VARCHAR(64) NOT NULL COMMENT '强平ID',
    `order_id` BIGINT NOT NULL COMMENT 'OMS订单ID',
    `symbol` VARCHAR(32) NOT NULL COMMENT '交易对',
    `side` VARCHAR(8) NOT NULL COMMENT '方向',
    `quantity` BIGINT NOT NULL COMMENT '数量',
    `executed_qty` BIGINT DEFAULT 0 COMMENT '已成交数量',
    `status` VARCHAR(32) NOT NULL COMMENT '状态',
    `created_at` BIGINT NOT NULL COMMENT '创建时间',
    `updated_at` BIGINT NOT NULL COMMENT '更新时间',
    
    PRIMARY KEY (`id`),
    KEY `idx_liquidation_id` (`liquidation_id`),
    KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='强平订单关联表';
```

---

## 5. 接口设计

### 5.1 内部接口

#### 5.1.1 创建强平订单

```java
// 请求
POST /internal/liquidation/order/create
Content-Type: application/json

{
    "userId": 12345,
    "positionId": 67890,
    "symbol": "BTCUSDT",
    "marginMode": "ISOLATED",
    "positionSide": "LONG",
    "quantity": 100000000,
    "liquidationPrice": 4600000000000,
    "bankruptcyPrice": 4400000000000,
    "triggerType": "MARGIN_RATIO"
}

// 响应
{
    "code": 0,
    "message": "success",
    "data": {
        "liquidationId": "LIQ_1704067200123_12345",
        "orderId": 987654321,
        "status": "SUBMITTED"
    }
}
```

#### 5.1.2 查询强平记录

```java
GET /internal/liquidation/record?userId=12345&positionId=67890

// 响应
{
    "code": 0,
    "data": {
        "liquidationId": "LIQ_1704067200123_12345",
        "status": "FILLED",
        "executedPrice": 4450000000000,
        "realizedPnl": -55000000000,
        "bankruptLoss": 50000000000,
        "adlRequired": true
    }
}
```

### 5.2 管理接口

#### 5.2.1 手动触发强平

```java
POST /admin/liquidation/trigger
{
    "userId": 12345,
    "positionId": 67890,
    "reason": "风险管理-手动强平"
}
```

#### 5.2.2 查询强平统计

```java
GET /admin/liquidation/stats?startTime=xxx&endTime=xxx

// 响应
{
    "totalLiquidations": 150,
    "totalBankruptLoss": 500000000000,
    "insuranceCover": 350000000000,
    "adlTriggered": 20,
    "avgExecutionTime": 120
}
```

---

## 6. 风险与应对

| 风险 | 影响 | 应对措施 |
|-----|------|---------|
| OMS 不可用 | 无法创建订单 | 缓存事件，重试3次，告警 |
| 订单一直未成交 | 穿仓损失扩大 | 30秒取消重发，限价保护 |
| 极端行情大量强平 | 系统过载 | 队列削峰，分级处理 |
| 价格剧烈波动 | 强平价格偏离 | 价格保护机制，限价单 |
| 重复强平 | 用户损失扩大 | 幂等性检查，5分钟窗口 |

---

## 7. 附录

### 7.1 Kafka Topic

| Topic | 类型 | 说明 |
|-------|------|------|
| liquidation-trigger-topic | 消费 | 强平触发事件 |
| liquidation-completed-topic | 生产 | 强平完成事件 |
| liquidation-order-status-topic | 消费 | 订单状态变更 |

### 7.2 错误码

| 错误码 | 说明 |
|-------|------|
| 80001 | 强平事件重复 |
| 80002 | 仓位不存在 |
| 80003 | 创建订单失败 |
| 80004 | 保险基金不足 |
| 80005 | 超过重试次数 |

---

**文档结束**
