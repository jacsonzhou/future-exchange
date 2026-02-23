# 资金费率结算系统 (Funding Rate Settlement) - P0 需求文档

## 1. 功能概述

### 1.1 什么是资金费率
资金费率是永续合约特有的机制，用于锚定合约价格与现货价格。通过多空双方定期交换资金费用，使合约价格回归现货指数价格。

### 1.2 核心概念
| 术语 | 说明 |
|------|------|
| 标记价格 (Mark Price) | 用于计算未实现盈亏和强平价格的合理价格，基于指数价格计算 |
| 指数价格 (Index Price) | 综合多家交易所现货价格计算的市场公允价格 |
| 资金费率 (Funding Rate) | 决定资金费用大小和方向的利率 |
| 资金费用 (Funding Fee) | 持仓用户实际支付或收取的费用 |

### 1.3 资金费率计算公式
```
资金费率 (F) = 溢价指数 (P) + 利率差 (I)

其中:
- 利率差 I = 0.01% (固定，每8小时)
- 溢价指数 P = (合约标记价格 - 指数价格) / 指数价格

最终资金费率: clamp(F, -0.75%, +0.75%)
```

### 1.4 资金费用计算
```
资金费用 = 持仓名义价值 × 资金费率
持仓名义价值 = 持仓数量 × 标记价格

方向:
- 资金费率为正: 多头支付空头
- 资金费率为负: 空头支付多头
```

## 2. 业务需求

### 2.1 结算周期
- **结算频率**: 每8小时一次（00:00, 08:00, 16:00 UTC）
- **结算时刻**: 在整点时刻执行
- **结算范围**: 所有持仓用户（包括全仓和逐仓）

### 2.2 资金费率计算流程
1. **数据收集** (T-1分钟)
   - 获取各symbol的标记价格
   - 获取各symbol的指数价格
   - 获取所有持仓用户数据

2. **费率计算** (T时刻)
   - 计算溢价指数
   - 计算资金费率
   - 费率范围限制: [-0.75%, +0.75%]

3. **费用结算** (T时刻)
   - 逐用户逐symbol计算资金费用
   - 更新用户余额
   - 生成ledger分录
   - 发布结算事件

### 2.3 结算优先级
1. 先计算资金费率并公示
2. 再执行资金费用结算
3. 最后更新持仓成本价

## 3. 系统架构

### 3.1 模块划分
```
funding-rate-core (端口: 8088)
├── 资金费率计算服务
├── 资金费用结算服务
├── 历史费率查询服务
├── 预估费率计算服务
└── 定时任务调度
```

### 3.2 与其他模块关系
```
funding-rate-core
    ↓ 调用
index-price-core (获取指数价格)
    ↓ 调用
position-snapshot-core (获取持仓数据)
    ↓ 发布事件
ledger-core (记账)
    ↓ 更新
snapshot-account-core (更新余额)
```

## 4. 数据模型

### 4.1 资金费率配置表 (t_funding_rate_config)
```sql
CREATE TABLE t_funding_rate_config (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol          VARCHAR(32) NOT NULL COMMENT '交易对',
    settlement_interval INT DEFAULT 28800 COMMENT '结算间隔(秒)，默认8小时',
    max_rate        BIGINT NOT NULL COMMENT '最大资金费率(如750000=0.75%)',
    min_rate        BIGINT NOT NULL COMMENT '最小资金费率(如-750000=-0.75%)',
    interest_rate   BIGINT DEFAULT 1000 COMMENT '利率差(如0.01%=1000)',
    status          TINYINT DEFAULT 1 COMMENT '状态: 1启用 0禁用',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol (symbol)
) ENGINE=InnoDB COMMENT='资金费率配置表';
```

### 4.2 资金费率历史表 (t_funding_rate_history)
```sql
CREATE TABLE t_funding_rate_history (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol          VARCHAR(32) NOT NULL COMMENT '交易对',
    funding_time    BIGINT NOT NULL COMMENT '结算时间戳(毫秒)',
    funding_rate    BIGINT NOT NULL COMMENT '资金费率(如50000=0.005%)',
    mark_price      BIGINT NOT NULL COMMENT '标记价格',
    index_price     BIGINT NOT NULL COMMENT '指数价格',
    premium_index   BIGINT NOT NULL COMMENT '溢价指数',
    total_long_qty  BIGINT NOT NULL COMMENT '多头总持仓量',
    total_short_qty BIGINT NOT NULL COMMENT '空头总持仓量',
    settlement_amount BIGINT COMMENT '本次结算总金额',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol_time (symbol, funding_time),
    KEY idx_funding_time (funding_time)
) ENGINE=InnoDB COMMENT='资金费率历史表';
```

### 4.3 用户资金费用明细表 (t_user_funding_fee)
```sql
CREATE TABLE t_user_funding_fee (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL COMMENT '用户ID',
    symbol          VARCHAR(32) NOT NULL COMMENT '交易对',
    funding_time    BIGINT NOT NULL COMMENT '结算时间戳(毫秒)',
    side            VARCHAR(8) NOT NULL COMMENT '持仓方向: LONG/SHORT',
    position_qty    BIGINT NOT NULL COMMENT '持仓数量',
    mark_price      BIGINT NOT NULL COMMENT '标记价格',
    funding_rate    BIGINT NOT NULL COMMENT '资金费率',
    funding_fee     BIGINT NOT NULL COMMENT '资金费用(正=支付,负=收取)',
    margin_mode     VARCHAR(16) NOT NULL COMMENT '保证金模式: ISOLATED/CROSS',
    status          VARCHAR(16) DEFAULT 'SETTLED' COMMENT '状态',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_symbol_time (user_id, symbol, funding_time),
    KEY idx_funding_time (funding_time),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB COMMENT='用户资金费用明细表';
```

### 4.4 预估资金费率实时表 (t_funding_rate_estimate)
```sql
CREATE TABLE t_funding_rate_estimate (
    symbol          VARCHAR(32) PRIMARY KEY COMMENT '交易对',
    next_funding_time BIGINT NOT NULL COMMENT '下次结算时间',
    estimated_rate  BIGINT NOT NULL COMMENT '预估资金费率',
    mark_price      BIGINT NOT NULL COMMENT '当前标记价格',
    index_price     BIGINT NOT NULL COMMENT '当前指数价格',
    premium_index   BIGINT NOT NULL COMMENT '当前溢价指数',
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_next_funding_time (next_funding_time)
) ENGINE=InnoDB COMMENT='预估资金费率实时表';
```

## 5. API 接口设计

### 5.1 查询资金费率历史
```
GET /api/v1/funding-rate/history?symbol=BTCUSDT&limit=100

Response:
{
    "code": 0,
    "data": [
        {
            "symbol": "BTCUSDT",
            "fundingTime": 1704067200000,
            "fundingRate": "0.0001",      // 0.01%
            "markPrice": "50000.50",
            "indexPrice": "50000.00"
        }
    ]
}
```

### 5.2 查询预估资金费率
```
GET /api/v1/funding-rate/estimate?symbol=BTCUSDT

Response:
{
    "code": 0,
    "data": {
        "symbol": "BTCUSDT",
        "estimatedRate": "0.0001",
        "nextFundingTime": 1704096000000,
        "markPrice": "50000.50",
        "indexPrice": "50000.00"
    }
}
```

### 5.3 查询用户资金费用记录
```
GET /api/v1/funding-rate/user-fees?symbol=BTCUSDT&startTime=xxx&endTime=xxx

Response:
{
    "code": 0,
    "data": [
        {
            "symbol": "BTCUSDT",
            "fundingTime": 1704067200000,
            "side": "LONG",
            "positionQty": "1.5",
            "fundingRate": "0.0001",
            "fundingFee": "-7.5",     // 负数表示收到资金费
            "marginMode": "CROSS"
        }
    ]
}
```

### 5.4 查询所有symbol当前资金费率（批量）
```
GET /api/v1/funding-rate/current

Response:
{
    "code": 0,
    "data": [
        {
            "symbol": "BTCUSDT",
            "fundingRate": "0.0001",
            "nextFundingTime": 1704096000000
        },
        {
            "symbol": "ETHUSDT",
            "fundingRate": "-0.00005",
            "nextFundingTime": 1704096000000
        }
    ]
}
```

## 6. 核心流程

### 6.1 资金费率结算时序图
```
定时任务触发
    │
    ▼
┌─────────────┐
│ 计算资金费率 │ ← 获取指数价格、标记价格
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ 保存费率历史 │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ 获取所有持仓 │ ← 从position-snapshot获取
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ 逐用户结算   │ ← 计算资金费用
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ Ledger记账  │ ← 生成资金费用分录
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ 发布结算事件 │ ← Kafka通知各模块
└─────────────┘
```

### 6.2 Ledger 分录设计

#### 场景1: 多头支付资金费
```
用户A持有 1 BTC 多单，资金费率 0.01%，标记价格 $50,000
资金费用 = 1 × 50000 × 0.01% = $5

分录:
1. 借: 用户A可用保证金    5 USDT
   贷: 用户B可用保证金    5 USDT  (空头收到)
   
   BizType: FUNDING_RATE
```

#### 场景2: 空头支付资金费
```
用户B持有 1 BTC 空单，资金费率 -0.01%，标记价格 $50,000
资金费用 = 1 × 50000 × (-0.01%) = -$5 (用户B支付5)

分录:
1. 借: 用户B可用保证金    5 USDT
   贷: 用户A可用保证金    5 USDT  (多头收到)
   
   BizType: FUNDING_RATE
```

## 7. Kafka Topic 设计

| Topic | 用途 | 生产者 | 消费者 |
|-------|------|--------|--------|
| funding-rate-calc | 资金费率计算完成事件 | funding-rate-core | oms-core, position-snapshot-core |
| funding-settlement | 资金费用结算事件 | funding-rate-core | ledger-core, snapshot-account-core |
| index-price-update | 指数价格更新 | index-price-core | funding-rate-core |

## 8. 异常处理

### 8.1 结算失败处理
- 单个用户结算失败: 记录失败日志，继续处理其他用户
- 全部结算失败: 触发告警，人工介入
- 结算数据不一致: 对账任务检测并补偿

### 8.2 重试机制
- 指数价格获取失败: 重试3次，使用缓存数据
- Ledger记账失败: 进入重试队列，最大重试5次
- 数据库写入失败: 事务回滚，记录失败原因

## 9. 性能要求

| 指标 | 目标值 |
|------|--------|
| 单次结算完成时间 | < 30秒 |
| 支持同时结算用户数 | > 100万 |
| 资金费率计算延迟 | < 100ms |
| 查询接口响应时间 | < 50ms |

## 10. 监控告警

### 10.1 关键监控指标
- 资金费率计算成功率
- 结算完成延迟
- 异常结算用户数
- 资金费用总金额

### 10.2 告警规则
- 结算延迟超过60秒: P0告警
- 结算失败率超过1%: P0告警
- 资金费率超过阈值: P1告警
