# ADL自动减仓系统 (Auto-Deleveraging) - P0 需求文档

## 1. 功能概述

### 1.1 什么是ADL
ADL (Auto-Deleveraging) 自动减仓是当保险基金不足以覆盖穿仓损失时，系统按照特定优先级自动平仓盈利用户的仓位，以分摊穿仓损失的机制。

### 1.2 ADL触发条件
1. 用户被强平后仓位无法完全成交
2. 剩余仓位产生穿仓损失
3. 保险基金余额不足以覆盖该损失
4. 触发ADL机制

### 1.3 ADL优先级计算
ADL按照用户的**盈利比例**和**有效杠杆**进行排序：
```
ADL优先级 = f(盈利比例, 有效杠杆, 持仓时间)

盈利越高、杠杆越大的用户，越先被选中ADL
```

### 1.4 ADL排序指标
| 指标 | 说明 | 影响 |
|------|------|------|
| 盈利比例 | 未实现盈亏 / 保证金 | 盈利越高优先级越高 |
| 有效杠杆 | 持仓价值 / 保证金 | 杠杆越高优先级越高 |
| 持仓时间 | 开仓至今的时间 | 通常新仓位优先 |

## 2. 业务需求

### 2.1 ADL触发流程
```
用户强平
    │
    ▼
┌─────────────────┐
│ 强平单部分成交  │
│ 剩余仓位穿仓    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 计算穿仓损失    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 保险基金充足?   │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
   是         否
    │         │
    ▼         ▼
┌─────────┐ ┌─────────┐
│ 保险基金 │ │ 触发ADL │
│ 赔付    │ │ 机制    │
└─────────┘ └────┬────┘
                 │
                 ▼
          ┌─────────────┐
          │ 选择ADL对手 │
          │ 方用户      │
          └──────┬──────┘
                 │
                 ▼
          ┌─────────────┐
          │ 执行ADL减仓 │
          └─────────────┘
```

### 2.2 ADL对手方选择
1. **选择方向相反的盈利仓位**
   - 被强平的是多头，则选择空头盈利用户
   - 被强平的是空头，则选择多头盈利用户

2. **按优先级排序**
   - 计算每个候选用户的ADL得分
   - 按得分从高到低排序
   - 选择排名靠前的用户

3. **ADL得分计算**
```
盈利比例 = 未实现盈亏 / 仓位保证金
ADL得分 = 盈利比例 × 有效杠杆系数

有效杠杆系数 = min(实际杠杆 / 10, 1)
```

### 2.3 ADL执行规则
1. **执行价格**: 使用穿仓用户的破产价格
2. **执行数量**: 最小(穿仓剩余数量, ADL用户持仓数量)
3. **费用**: ADL不收取手续费
4. **通知**: ADL完成后通知受影响用户
5. **限制**: 单次ADL最多影响N个用户（防止大面积影响）

### 2.4 保险基金机制
```
保险基金来源:
1. 强平剩余保证金注入
2. 平台补贴
3. 部分交易手续费

保险基金用途:
1. 优先覆盖穿仓损失
2. 只有在保险基金不足时才触发ADL
```

## 3. 系统架构

### 3.1 模块划分
```
adl-core (端口: 8091)
├── ADL触发检测服务
├── ADL优先级计算服务
├── ADL执行服务
├── 保险基金管理服务
└── ADL历史记录服务
```

### 3.2 与其他模块关系
```
adl-core
    ← 监听
liquidation-core (强平事件)
    ↓ 调用
position-snapshot-core (获取持仓)
    ↓ 更新
ledger-core (ADL记账)
    ↓ 通知
notification-core (用户通知)
```

## 4. 数据模型

### 4.1 ADL排名队列表 (t_adl_ranking_queue)
```sql
CREATE TABLE t_adl_ranking_queue (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    position_id         BIGINT NOT NULL COMMENT '仓位ID',
    
    -- 仓位信息
    side                VARCHAR(8) NOT NULL COMMENT '方向: LONG/SHORT',
    qty                 BIGINT NOT NULL COMMENT '持仓数量',
    entry_price         BIGINT NOT NULL COMMENT '开仓均价',
    position_value      BIGINT NOT NULL COMMENT '持仓价值',
    
    -- 排名指标
    margin              BIGINT NOT NULL COMMENT '仓位保证金',
    unrealized_pnl      BIGINT NOT NULL COMMENT '未实现盈亏',
    pnl_ratio           BIGINT NOT NULL COMMENT '盈亏比例(如1000=10%)',
    effective_leverage  INT NOT NULL COMMENT '有效杠杆',
    adl_score           BIGINT NOT NULL COMMENT 'ADL得分',
    adl_rank            INT NOT NULL COMMENT 'ADL排名',
    
    -- 状态
    status              VARCHAR(16) DEFAULT 'ACTIVE' COMMENT '状态',
    -- ACTIVE: 在队列中
    -- EXECUTED: 已被ADL
    -- REMOVED: 已移除
    
    calc_time           BIGINT NOT NULL COMMENT '计算时间',
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_position (position_id),
    KEY idx_symbol_side (symbol, side),
    KEY idx_adl_score (symbol, side, adl_score DESC),
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='ADL排名队列表';
```

### 4.2 ADL执行记录表 (t_adl_execution)
```sql
CREATE TABLE t_adl_execution (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    adl_id              BIGINT NOT NULL COMMENT 'ADL执行ID',
    
    -- 被ADL用户(盈利方)
    target_user_id      BIGINT NOT NULL COMMENT '被ADL用户ID',
    target_position_id  BIGINT NOT NULL COMMENT '被ADL仓位ID',
    target_side         VARCHAR(8) NOT NULL COMMENT '被ADL方向',
    
    -- 触发ADL的穿仓用户
    source_user_id      BIGINT NOT NULL COMMENT '穿仓用户ID',
    source_liquidation_id BIGINT NOT NULL COMMENT '强平记录ID',
    
    -- 交易对
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- ADL执行详情
    adl_price           BIGINT NOT NULL COMMENT 'ADL执行价格(破产价)',
    adl_qty             BIGINT NOT NULL COMMENT 'ADL数量',
    adl_amount          BIGINT NOT NULL COMMENT 'ADL金额',
    
    -- 盈亏计算
    target_pnl          BIGINT NOT NULL COMMENT '被ADL用户盈亏',
    source_bankrupt_loss BIGINT NOT NULL COMMENT '穿仓用户破产损失',
    insurance_cover     BIGINT COMMENT '保险基金赔付金额',
    
    -- 状态
    status              VARCHAR(16) DEFAULT 'COMPLETED' COMMENT '状态',
    
    executed_at         BIGINT NOT NULL COMMENT '执行时间',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_adl_id (adl_id),
    KEY idx_target_user (target_user_id),
    KEY idx_source_user (source_user_id),
    KEY idx_symbol (symbol),
    KEY idx_executed_at (executed_at)
) ENGINE=InnoDB COMMENT='ADL执行记录表';
```

### 4.3 保险基金表 (t_insurance_fund)
```sql
CREATE TABLE t_insurance_fund (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol              VARCHAR(32) COMMENT '交易对(全局则为空)',
    currency            VARCHAR(16) NOT NULL COMMENT '币种',
    
    -- 余额
    balance             BIGINT NOT NULL COMMENT '当前余额',
    total_income        BIGINT NOT NULL COMMENT '累计收入',
    total_expense       BIGINT NOT NULL COMMENT '累计支出',
    
    -- 统计
    cover_count         INT DEFAULT 0 COMMENT '赔付次数',
    adl_trigger_count   INT DEFAULT 0 COMMENT '触发ADL次数',
    
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_symbol_currency (symbol, currency)
) ENGINE=InnoDB COMMENT='保险基金表';
```

### 4.4 保险基金流水表 (t_insurance_fund_log)
```sql
CREATE TABLE t_insurance_fund_log (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol              VARCHAR(32) COMMENT '交易对',
    currency            VARCHAR(16) NOT NULL COMMENT '币种',
    
    -- 变动信息
    change_type         VARCHAR(32) NOT NULL COMMENT '变动类型',
    -- LIQUIDATION_SURPLUS: 强平盈余注入
    -- PLATFORM_SUBSIDY: 平台补贴
    -- FEE_CONTRIBUTION: 手续费贡献
    -- COVER_BANKRUPT: 赔付穿仓
    -- ADL_COMPENSATION: ADL补偿
    
    amount              BIGINT NOT NULL COMMENT '变动金额(正=收入,负=支出)',
    balance_before      BIGINT NOT NULL COMMENT '变动前余额',
    balance_after       BIGINT NOT NULL COMMENT '变动后余额',
    
    -- 关联信息
    ref_id              BIGINT COMMENT '关联ID(强平ID等)',
    ref_type            VARCHAR(32) COMMENT '关联类型',
    description         VARCHAR(512) COMMENT '说明',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    KEY idx_symbol (symbol),
    KEY idx_type (change_type),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB COMMENT='保险基金流水表';
```

### 4.5 穿仓记录表 (t_bankruptcy_record)
```sql
CREATE TABLE t_bankruptcy_record (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    liquidation_id      BIGINT NOT NULL COMMENT '强平记录ID',
    user_id             BIGINT NOT NULL COMMENT '穿仓用户ID',
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 穿仓信息
    side                VARCHAR(8) NOT NULL COMMENT '方向',
    bankrupt_price      BIGINT NOT NULL COMMENT '破产价格',
    bankrupt_qty        BIGINT NOT NULL COMMENT '穿仓数量',
    bankrupt_loss       BIGINT NOT NULL COMMENT '穿仓损失金额',
    
    -- 处理状态
    handle_type         VARCHAR(16) COMMENT '处理方式',
    -- INSURANCE: 保险基金赔付
    -- ADL: ADL处理
    -- PENDING: 待处理
    
    insurance_cover     BIGINT COMMENT '保险基金赔付金额',
    adl_cover           BIGINT COMMENT 'ADL分摊金额',
    
    status              VARCHAR(16) DEFAULT 'PENDING' COMMENT '状态',
    -- PENDING: 待处理
    -- PROCESSING: 处理中
    -- COMPLETED: 已完成
    -- FAILED: 失败
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMP COMMENT '完成时间',
    
    UNIQUE KEY uk_liquidation (liquidation_id),
    KEY idx_user_id (user_id),
    KEY idx_symbol (symbol),
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='穿仓记录表';
```

## 5. API 接口设计

### 5.1 查询ADL排名
```
GET /api/v1/adl/ranking?symbol=BTCUSDT&side=SHORT

Response:
{
    "code": 0,
    "data": {
        "symbol": "BTCUSDT",
        "side": "SHORT",
        "updateTime": 1704067200000,
        "rankings": [
            {
                "rank": 1,
                "userId": 10001,
                "positionId": 50001,
                "qty": "10.0",
                "pnlRatio": "25%",
                "effectiveLeverage": 50,
                "adlScore": 12500
            }
        ],
        "myRank": 5,                        // 当前用户排名
        "adlZone": true                     // 是否在ADL危险区(前20%)
    }
}
```

### 5.2 查询ADL历史
```
GET /api/v1/adl/history?symbol=BTCUSDT&limit=100

Response:
{
    "code": 0,
    "data": [
        {
            "adlId": 10001,
            "symbol": "BTCUSDT",
            "time": 1704067200000,
            "adlPrice": "48000",
            "adlQty": "5.0",
            "affectedUsers": 3
        }
    ]
}
```

### 5.3 查询保险基金余额
```
GET /api/v1/adl/insurance-fund?symbol=BTCUSDT

Response:
{
    "code": 0,
    "data": {
        "symbol": "BTCUSDT",
        "currency": "USDT",
        "balance": "1000000",
        "totalIncome": "5000000",
        "totalExpense": "4000000",
        "coverCount": 150,
        "adlTriggerCount": 5
    }
}
```

### 5.4 查询用户ADL记录
```
GET /api/v1/adl/user-records?startTime=xxx&endTime=xxx

Response:
{
    "code": 0,
    "data": [
        {
            "adlId": 10001,
            "symbol": "BTCUSDT",
            "side": "LONG",
            "adlPrice": "48000",
            "adlQty": "1.0",
            "pnl": "-500",
            "time": 1704067200000
        }
    ]
}
```

### 5.5 内部接口：获取ADL候选人
```
POST /internal/adl/candidates

Request:
{
    "symbol": "BTCUSDT",
    "oppositeSide": "SHORT",            // ADL对手方向
    "requiredQty": "5.0",               // 需要的数量
    "limit": 10                         // 最多返回人数
}

Response:
{
    "code": 0,
    "data": [
        {
            "userId": 10001,
            "positionId": 50001,
            "qty": "10.0",
            "adlScore": 12500
        }
    ]
}
```

## 6. 核心流程

### 6.1 ADL触发检测流程
```
监听强平事件
    │
    ▼
┌─────────────────┐
│ 检查强平结果    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 是否有剩余仓位? │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
   有         无
    │         │
    ▼         ▼
┌─────────┐ ┌─────────┐
│ 计算    │ │ 结束    │
│ 穿仓损失│ │        │
└────┬────┘ └─────────┘
     │
     ▼
┌─────────────────┐
│ 保险基金充足?   │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
   否         是
    │         │
    ▼         ▼
┌─────────┐ ┌─────────┐
│ 触发ADL │ │ 保险基金│
│ 流程    │ │ 赔付    │
└────┬────┘ └─────────┘
     │
     ▼
┌─────────────────┐
│ 创建穿仓记录    │
└─────────────────┘
```

### 6.2 ADL执行流程
```
ADL触发
    │
    ▼
┌─────────────────┐
│ 计算ADL排名队列 │
│ (对手方向)      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 选择ADL候选人   │
│ (按排名从高到低)│
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 循环执行ADL     │
│ 直到穿仓损失    │
│ 被完全分摊      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 每个ADL执行:    │
│ 1. 平仓目标仓位 │
│ 2. 更新持仓     │
│ 3. Ledger记账   │
│ 4. 通知用户     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 更新穿仓记录    │
│ 为已完成        │
└─────────────────┘
```

### 6.3 ADL排名更新流程
```
定时触发(每5秒) / 价格变动触发
    │
    ▼
┌─────────────────┐
│ 获取所有盈利    │
│ 持仓          │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 计算每个持仓的  │
│ ADL得分       │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 按得分排序      │
│ 生成排名      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 更新排名队列    │
│ 缓存到Redis    │
└─────────────────┘
```

## 7. Ledger 分录设计

### 7.1 ADL减仓（被ADL用户）
```
假设:
- 用户A持有 1 BTC 空单，开仓价 $50,000
- 当前价格 $48,000 (破产价)
- ADL执行价 $48,000
- 该仓位盈利 $2,000

分录:
1. 借: 用户A持仓      1 BTC
      贷: 用户A保证金   48,000 USDT
      贷: 用户A已实现盈亏 2,000 USDT
      
   BizType: ADL
```

### 7.2 穿仓损失分摊（穿仓用户）
```
假设:
- 用户B穿仓损失 $1,000
- 保险基金赔付 $600
- ADL分摊 $400

分录:
1. 借: 保险基金      600 USDT
      贷: 穿仓损失    600 USDT
      
   BizType: INSURANCE_FUND

2. 借: ADL分摊      400 USDT
      贷: 穿仓损失    400 USDT
      
   BizType: ADL
```

## 8. 风控要求

### 8.1 ADL保护机制
1. **最大ADL比例**: 单次ADL最多减仓用户50%仓位
2. **用户保护期**: 新开仓1小时内降低ADL优先级
3. **VIP保护**: VIP用户降低ADL优先级
4. **ADL频率限制**: 同一用户1小时内最多被ADL 3次

### 8.2 保险基金管理
1. **最低余额告警**: 保险基金低于阈值时触发告警
2. **自动补充**: 可设置自动从平台收入补充
3. **使用审计**: 每次使用需记录详细原因

## 9. 性能要求

| 指标 | 目标值 |
|------|--------|
| ADL排名计算延迟 | < 1秒 |
| ADL执行延迟 | < 3秒 |
| 排名队列更新频率 | 每5秒 |
| 单次ADL最大用户数 | 50人 |
| 查询接口响应时间 | < 50ms |

## 10. 通知机制

### 10.1 被ADL用户通知
```
通知内容:
- 您的仓位被执行自动减仓(ADL)
- 交易对: BTCUSDT
- 减仓数量: 1.0 BTC
- 执行价格: $48,000
- 盈亏变化: +$2,000 (已实现)
- 剩余仓位: 0.5 BTC
- 原因: 对手方用户穿仓，保险基金不足
```

### 10.2 穿仓用户通知
```
通知内容:
- 您的仓位已穿仓
- 交易对: BTCUSDT
- 穿仓损失: $1,000
- 处理方式: ADL分摊
- 影响用户数: 3人
- 账户状态: 已清零
```
