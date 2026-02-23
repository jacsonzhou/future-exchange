# 全仓/逐仓模式系统 (Cross/Isolated Margin) - P0 需求文档

## 1. 功能概述

### 1.1 什么是保证金模式
保证金模式决定了仓位使用保证金的方式，以及爆仓后的损失范围。

### 1.2 模式对比
| 特性 | 全仓模式 (Cross Margin) | 逐仓模式 (Isolated Margin) |
|------|-------------------------|---------------------------|
| 保证金来源 | 账户全部可用保证金 | 分配给该仓位的独立保证金 |
| 爆仓影响 | 账户所有仓位 | 仅该仓位 |
| 强平风险 | 相对较低（保证金池共享） | 相对较高（独立保证金） |
| 灵活性 | 高 | 低 |
| 适用场景 | 专业交易者、多仓位管理 | 风险控制、单仓位投机 |

### 1.3 核心概念
- **钱包余额**: 账户总资金
- **可用保证金**: 可用来开新仓或追加保证金的资金
- **已用保证金**: 已被仓位占用的保证金
- **维持保证金**: 维持仓位所需的最低保证金
- **保证金率**: 保证金 / 持仓名义价值

## 2. 业务需求

### 2.1 逐仓模式
```
逐仓保证金 = 仓位初始保证金 + 追加保证金
可用保证金 = 钱包余额 - Σ(各逐仓仓位保证金) - 全仓仓位占用

爆仓条件: 逐仓保证金 <= 维持保证金
损失上限: 逐仓保证金（不会损失超过分配的保证金）
```

### 2.2 全仓模式
```
全仓保证金 = 仓位名义价值 / 杠杆倍数
账户保证金率 = (钱包余额 + 全仓未实现盈亏) / Σ(全仓仓位名义价值)

爆仓条件: 账户保证金率 <= 维持保证金率
损失风险: 可能导致账户全部资金损失
```

### 2.3 混合模式支持
- 一个账户可以同时拥有全仓仓位和逐仓仓位
- 逐仓仓位使用独立保证金
- 全仓仓位共享账户保证金池
- 不同symbol、不同方向可以设置不同模式

### 2.4 模式切换
- 允许从逐仓切换到全仓（需要满足全仓保证金要求）
- 允许从全仓切换到逐仓（需要有足够可用保证金）
- 切换时检查是否会导致立即爆仓

## 3. 系统架构

### 3.1 模块划分
```
margin-mode-core (端口: 8090)
├── 保证金模式管理服务
├── 逐仓保证金计算服务
├── 全仓保证金计算服务
├── 模式切换服务
└── 保证金率监控服务
```

### 3.2 与其他模块关系
```
margin-mode-core
    ↓ 调用
hard-risk-core (开仓前检查保证金)
    ↓ 更新
ledger-core (保证金变动记账)
    ↓ 监听
position-snapshot-core (仓位变动)
snapshot-account-core (余额变动)
```

## 4. 数据模型

### 4.1 用户保证金配置表 (t_user_margin_config)
```sql
CREATE TABLE t_user_margin_config (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 默认模式
    default_margin_mode VARCHAR(16) NOT NULL DEFAULT 'CROSS' COMMENT '默认模式: CROSS/ISOLATED',
    
    -- 全仓配置
    cross_leverage      INT DEFAULT 10 COMMENT '全仓默认杠杆',
    cross_max_leverage  INT DEFAULT 125 COMMENT '全仓最大杠杆',
    
    -- 逐仓配置
    isolated_leverage   INT DEFAULT 10 COMMENT '逐仓默认杠杆',
    isolated_max_leverage INT DEFAULT 125 COMMENT '逐仓最大杠杆',
    
    -- 风控配置
    max_position_num    INT DEFAULT 50 COMMENT '最大仓位数量',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_user_symbol (user_id, symbol),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB COMMENT='用户保证金配置表';
```

### 4.2 仓位保证金详情表 (t_position_margin_detail)
```sql
CREATE TABLE t_position_margin_detail (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    position_id         BIGINT NOT NULL COMMENT '仓位ID',
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 保证金模式
    margin_mode         VARCHAR(16) NOT NULL COMMENT '模式: CROSS/ISOLATED',
    leverage            INT NOT NULL COMMENT '当前杠杆倍数',
    
    -- 逐仓专用
    isolated_margin     BIGINT COMMENT '逐仓保证金(仅逐仓模式)',
    isolated_available  BIGINT COMMENT '逐仓可用保证金(可取出)',
    
    -- 保证金计算
    position_margin     BIGINT NOT NULL COMMENT '仓位保证金',
    maint_margin        BIGINT NOT NULL COMMENT '维持保证金',
    maint_margin_rate   BIGINT NOT NULL COMMENT '维持保证金率(如500=5%)',
    
    -- 风险指标
    margin_ratio        BIGINT COMMENT '保证金率(仅逐仓)',
    liquidation_price   BIGINT COMMENT '预估强平价',
    
    -- 全仓专用
    cross_unrealized_pnl BIGINT COMMENT '全仓未实现盈亏',
    
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_position_id (position_id),
    KEY idx_user_id (user_id),
    KEY idx_symbol (symbol)
) ENGINE=InnoDB COMMENT='仓位保证金详情表';
```

### 4.3 保证金变动流水表 (t_margin_change_log)
```sql
CREATE TABLE t_margin_change_log (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    position_id         BIGINT COMMENT '仓位ID',
    
    -- 变动信息
    change_type         VARCHAR(32) NOT NULL COMMENT '变动类型',
    -- OPEN: 开仓增加
    -- ADD: 追加保证金
    -- REMOVE: 减少保证金
    -- MODE_CHANGE: 模式切换
    -- LIQUIDATION: 强平扣除
    
    margin_mode         VARCHAR(16) NOT NULL COMMENT '模式: CROSS/ISOLATED',
    amount              BIGINT NOT NULL COMMENT '变动金额(正=增加,负=减少)',
    before_amount       BIGINT NOT NULL COMMENT '变动前金额',
    after_amount        BIGINT NOT NULL COMMENT '变动后金额',
    
    -- 关联信息
    biz_id              BIGINT COMMENT '业务ID(订单ID等)',
    biz_type            VARCHAR(32) COMMENT '业务类型',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    KEY idx_user_id (user_id),
    KEY idx_position_id (position_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB COMMENT='保证金变动流水表';
```

### 4.4 全仓账户风险快照表 (t_cross_margin_snapshot)
```sql
CREATE TABLE t_cross_margin_snapshot (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    
    -- 账户资金
    wallet_balance      BIGINT NOT NULL COMMENT '钱包余额',
    available_balance   BIGINT NOT NULL COMMENT '可用余额',
    
    -- 全仓仓位汇总
    total_position_value BIGINT NOT NULL COMMENT '全仓仓位总价值',
    total_unrealized_pnl BIGINT NOT NULL COMMENT '全仓总未实现盈亏',
    total_maint_margin  BIGINT NOT NULL COMMENT '全仓总维持保证金',
    
    -- 风险指标
    margin_balance      BIGINT NOT NULL COMMENT '保证金余额(钱包+未实现盈亏)',
    margin_ratio        BIGINT NOT NULL COMMENT '保证金率',
    available_margin    BIGINT NOT NULL COMMENT '可用保证金',
    
    -- 风控状态
    risk_level          VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '风险等级',
    -- NORMAL: 正常
    -- WARNING: 预警(保证金率<150%维持率)
    -- DANGER: 危险(保证金率<120%维持率)
    -- LIQUIDATION: 强平中
    
    snapshot_time       BIGINT NOT NULL COMMENT '快照时间',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_user_time (user_id, snapshot_time),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB COMMENT='全仓账户风险快照表';
```

## 5. API 接口设计

### 5.1 切换保证金模式
```
POST /api/v1/margin/switch-mode

Request:
{
    "userId": 10001,
    "symbol": "BTCUSDT",
    "positionId": 50001,                // 可选，不传则修改默认设置
    "marginMode": "ISOLATED",           // CROSS/ISOLATED
    "leverage": 20                      // 切换后的杠杆
}

Response:
{
    "code": 0,
    "data": {
        "success": true,
        "positionId": 50001,
        "oldMode": "CROSS",
        "newMode": "ISOLATED",
        "marginTransferred": "1000"     // 转入逐仓的保证金
    }
}
```

### 5.2 修改杠杆倍数
```
POST /api/v1/margin/change-leverage

Request:
{
    "userId": 10001,
    "symbol": "BTCUSDT",
    "marginMode": "ISOLATED",
    "leverage": 50
}

Response:
{
    "code": 0,
    "data": {
        "maxLeverage": 125,
        "currentLeverage": 50,
        "newPositionMargin": "200",     // 新仓位保证金
        "newLiquidationPrice": "49000"  // 新强平价
    }
}
```

### 5.3 追加逐仓保证金
```
POST /api/v1/margin/add-isolated

Request:
{
    "userId": 10001,
    "symbol": "BTCUSDT",
    "positionId": 50001,
    "amount": "500"
}

Response:
{
    "code": 0,
    "data": {
        "oldMargin": "1000",
        "newMargin": "1500",
        "newLiquidationPrice": "48000"
    }
}
```

### 5.4 减少逐仓保证金
```
POST /api/v1/margin/remove-isolated

Request:
{
    "userId": 10001,
    "symbol": "BTCUSDT",
    "positionId": 50001,
    "amount": "300"
}

Response:
{
    "code": 0,
    "data": {
        "oldMargin": "1500",
        "newMargin": "1200",
        "newLiquidationPrice": "48500",
        "withdrawnAmount": "300"
    }
}
```

### 5.5 查询保证金配置
```
GET /api/v1/margin/config?symbol=BTCUSDT

Response:
{
    "code": 0,
    "data": {
        "symbol": "BTCUSDT",
        "defaultMode": "CROSS",
        "cross": {
            "currentLeverage": 10,
            "maxLeverage": 125
        },
        "isolated": {
            "currentLeverage": 20,
            "maxLeverage": 125
        }
    }
}
```

### 5.6 查询仓位保证金详情
```
GET /api/v1/margin/position-detail?positionId=50001

Response:
{
    "code": 0,
    "data": {
        "positionId": 50001,
        "symbol": "BTCUSDT",
        "marginMode": "ISOLATED",
        "leverage": 20,
        "isolatedMargin": "1500",
        "positionMargin": "500",
        "maintMargin": "250",
        "marginRatio": "300%",
        "liquidationPrice": "48000",
        "maxRemoveMargin": "1000"      // 最大可取出保证金
    }
}
```

### 5.7 查询全仓账户风险
```
GET /api/v1/margin/cross-risk

Response:
{
    "code": 0,
    "data": {
        "walletBalance": "10000",
        "availableBalance": "5000",
        "totalPositionValue": "25000",
        "totalUnrealizedPnl": "500",
        "marginBalance": "10500",
        "marginRatio": "42%",
        "availableMargin": "3000",
        "riskLevel": "NORMAL",
        "positions": [
            {
                "symbol": "BTCUSDT",
                "side": "LONG",
                "positionValue": "15000",
                "unrealizedPnl": "300"
            }
        ]
    }
}
```

## 6. 核心流程

### 6.1 开仓时保证金处理
```
接收开仓请求
    │
    ▼
┌─────────────────┐
│ 检查保证金模式  │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
 逐仓模式   全仓模式
    │         │
    ▼         ▼
┌─────────┐ ┌─────────┐
│ 计算初始 │ │ 计算占用 │
│ 保证金  │ │ 保证金  │
└────┬────┘ └────┬────┘
     │           │
     ▼           ▼
┌─────────────────┐
│ 检查可用保证金  │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
  足够      不足
    │         │
    ▼         ▼
┌─────────┐ ┌─────────┐
│ 冻结保证 │ │ 拒绝    │
│ 金      │ │ 开仓    │
└────┬────┘ └─────────┘
     │
     ▼
┌─────────┐
│ 创建仓位 │
└─────────┘
```

### 6.2 模式切换流程
```
切换请求
    │
    ▼
┌─────────────────┐
│ 检查当前仓位状态 │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ CROSS→ISOLATED  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 计算需要转入的  │
│ 逐仓保证金      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 检查可用保证金  │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
  足够      不足
    │         │
    ▼         ▼
┌─────────┐ ┌─────────┐
│ 执行切换 │ │ 提示    │
│        │ │ 保证金  │
└────┬────┘ │ 不足    │
     │      └─────────┘
     ▼
┌─────────┐
│ 更新仓位 │
│ 模式    │
└─────────┘
```

### 6.3 逐仓追加保证金
```
追加请求
    │
    ▼
┌─────────────────┐
│ 验证仓位存在    │
│ 且为逐仓模式    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 检查可用保证金  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 从可用余额扣除  │
│ 转入逐仓保证金  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 更新仓位保证金  │
│ 重新计算强平价  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Ledger记账      │
└─────────────────┘
```

## 7. Ledger 分录设计

### 7.1 逐仓开仓
```
借: 逐仓保证金    1000 USDT
   贷: 可用余额    1000 USDT
   
   BizType: ISOLATED_MARGIN_OPEN
```

### 7.2 追加逐仓保证金
```
借: 逐仓保证金    500 USDT
   贷: 可用余额    500 USDT
   
   BizType: ISOLATED_MARGIN_ADD
```

### 7.3 取出逐仓保证金
```
借: 可用余额    300 USDT
   贷: 逐仓保证金    300 USDT
   
   BizType: ISOLATED_MARGIN_REMOVE
```

### 7.4 全仓转逐仓
```
借: 逐仓保证金    1000 USDT
   贷: 可用余额    1000 USDT
   
   BizType: MARGIN_MODE_SWITCH
```

## 8. 强平价格计算

### 8.1 逐仓强平价公式
```
多单强平价 = 开仓均价 × (1 + 初始保证金率 - 维持保证金率)
          = 开仓均价 × (1 + 1/杠杆 - 维持保证金率)

空单强平价 = 开仓均价 × (1 - 初始保证金率 + 维持保证金率)
          = 开仓均价 × (1 - 1/杠杆 + 维持保证金率)

示例:
- 多单开仓价: $50,000
- 杠杆: 20倍
- 维持保证金率: 0.5%
- 强平价 = 50000 × (1 + 0.05 - 0.005) = 50000 × 1.045 = $52,250
```

### 8.2 全仓强平价公式（复杂多仓位）
```
账户保证金率 = (钱包余额 + 全仓未实现盈亏) / 全仓仓位总价值

爆仓条件: 账户保证金率 <= 全仓维持保证金率

强平价计算需要考虑所有全仓仓位的综合影响
```

## 9. 风控要求

### 9.1 逐仓风控
1. 最小逐仓保证金限制（不能低于初始保证金）
2. 最大杠杆限制（symbol级别）
3. 强平价安全距离检查

### 9.2 全仓风控
1. 账户级别最大仓位限制
2. 风险等级监控
3. 自动减仓(ADL)准备

## 10. 性能要求

| 指标 | 目标值 |
|------|--------|
| 模式切换延迟 | < 100ms |
| 保证金计算延迟 | < 50ms |
| 强平价计算延迟 | < 30ms |
| 查询接口响应时间 | < 30ms |

## 11. 🔥 实际交易场景补充需求

### 11.1 资金费率对保证金的影响

**全仓模式：**
```
资金费率支付：从钱包余额扣除 → 影响账户保证金率
资金费率收入：增加钱包余额 → 增加可用保证金

计算公式：
资金费率 = 持仓名义价值 × 资金费率 × 方向系数
方向系数：多头支付为-1，空头收取为+1（正费率时）
```

**逐仓模式：**
```
资金费率支付：从逐仓保证金扣除 → 可能导致接近强平
资金费率收入：增加逐仓保证金 → 远离强平价

⚠️ 重要：资金费率可能导致逐仓仓位被强平（保证金不足）
```

**Ledger记账：**
```
# 全仓支付资金费率
借: 资金费用支出    100 USDT
   贷: 钱包余额    100 USDT
   BizType: FUNDING_FEE_CROSS

# 逐仓支付资金费率
借: 资金费用支出    50 USDT
   贷: 逐仓保证金  50 USDT
   BizType: FUNDING_FEE_ISOLATED
```

### 11.2 未实现盈亏的处理

**全仓模式 - 未实现盈亏实时影响保证金：**
```
保证金余额 = 钱包余额 + 全仓未实现盈亏总和

未实现盈亏计算：
多头：(标记价格 - 开仓均价) × 持仓数量
空头：(开仓均价 - 标记价格) × 持仓数量

⚠️ 关键：
- 未实现盈利增加可用保证金（可开新仓）
- 未实现亏损减少可用保证金（可能无法开新仓）
- 未实现盈亏直接影响保证金率计算
```

**逐仓模式 - 未实现盈亏不可用：**
```
逐仓保证金固定：初始保证金 + 追加保证金
未实现盈亏：仅用于显示，不影响可用保证金

⚠️ 即使逐仓仓位有浮盈，也不能用于开新仓
   只有平仓后实现盈利才能使用
```

**保证金率计算差异：**
```
# 全仓保证金率
marginRatio = (walletBalance + totalUnrealizedPnl) / totalPositionValue

# 逐仓保证金率
marginRatio = isolatedMargin / positionValue
```

### 11.3 仓位合并/拆分规则

**逐仓模式 - 严格独立：**
```
✅ 允许：
- 同symbol、同方向、不同杠杆的多个逐仓仓位
- 每个仓位独立保证金、独立强平价

❌ 禁止：
- 逐仓仓位不可合并
- 逐仓仓位不可拆分

示例：
用户可以同时持有：
- BTCUSDT 多头 10倍杠杆（逐仓保证金500U）
- BTCUSDT 多头 20倍杠杆（逐仓保证金300U）
两个仓位独立强平，互不影响
```

**全仓模式 - 自动合并：**
```
✅ 同symbol、同方向的全仓仓位自动合并：
- 合并后使用加权平均价
- 保证金共享账户池

示例：
第一笔：买入 1 BTC @ 50000，杠杆10x
第二笔：买入 1 BTC @ 51000，杠杆10x
结果：持有 2 BTC @ 50500（加权平均）
```

**对冲仓位（Hedge Mode）：**
```
🔥 期货交易所通常支持双向持仓模式：
- 单向持仓模式（One-Way）：同symbol只能持有一个方向
- 双向持仓模式（Hedge）：可同时持有多空两个方向

本系统暂不支持Hedge Mode，采用单向持仓：
- 反向开仓视为平仓操作
- 例如：持有多头时卖出视为平多，而非开空
```

### 11.4 ADL（自动减仓）对保证金的影响

**ADL触发场景：**
```
保险基金不足以覆盖强平损失时，触发ADL

例如：
用户A逐仓多头被强平，破产价48000，但只能以47500成交
亏损缺口：(48000 - 47500) × 持仓量 = 500 USDT
如果保险基金不足500 USDT，触发ADL减仓盈利对手方
```

**全仓被ADL：**
```
1. 系统自动平掉部分或全部仓位
2. 强制实现盈利，增加钱包余额
3. 账户保证金率上升（仓位减少）
4. 可用保证金增加

Ledger记账：
借: 钱包余额    1000 USDT
   贷: 已实现盈亏  1000 USDT
   BizType: ADL_FORCED_CLOSE
```

**逐仓被ADL：**
```
1. 逐仓仓位被强制平仓
2. 逐仓保证金 + 未实现盈利归还到钱包余额
3. 删除仓位保证金详情

Ledger记账：
借: 钱包余额    1500 USDT
   贷: 逐仓保证金  1000 USDT
   贷: 已实现盈亏    500 USDT
   BizType: ADL_FORCED_CLOSE_ISOLATED
```

### 11.5 强平机制细节

**部分强平 vs 全部强平：**
```
逐仓模式：
- 只能全部强平（仓位保证金归零）
- 强平价触及后立即触发
- 最大亏损 = 逐仓保证金

全仓模式：
- 优先部分强平（减少仓位，提高保证金率）
- 如果部分强平后保证金率仍低于维持率，继续强平
- 直到保证金率恢复安全或所有仓位被强平
```

**强平顺序（全仓）：**
```
1. 按盈亏排序：优先强平亏损最大的仓位
2. 按杠杆排序：优先强平杠杆最高的仓位
3. 按风险度排序：优先强平最接近强平价的仓位

强平执行：
1. 撤销所有挂单，释放冻结保证金
2. 按强平顺序逐个平仓
3. 每平一个仓位后重新计算保证金率
4. 保证金率恢复到安全值后停止
```

**强平手续费：**
```
逐仓强平手续费：
- 从逐仓保证金扣除
- 费率通常为Taker费率（例如0.05%）

全仓强平手续费：
- 从钱包余额扣除
- 可能导致账户进一步恶化

Ledger记账：
借: 手续费支出    10 USDT
   贷: 钱包余额    10 USDT
   BizType: LIQUIDATION_FEE
```

**保险基金计算：**
```
破产价与成交价的差额进入保险基金

多头强平：
如果成交价 > 破产价：
  保险基金收入 = (成交价 - 破产价) × 持仓量

如果成交价 < 破产价：
  保险基金支出 = (破产价 - 成交价) × 持仓量
  如果保险基金不足，触发ADL

空头强平：
如果成交价 < 破产价：
  保险基金收入 = (破产价 - 成交价) × 持仓量

如果成交价 > 破产价：
  保险基金支出 = (成交价 - 破产价) × 持仓量
```

### 11.6 杠杆调整限制条件

**无持仓时：**
```
✅ 可自由调整杠杆（1-125倍）
✅ 调整后影响下次开仓的初始保证金

初始保证金 = 开仓名义价值 / 杠杆倍数
```

**有持仓时 - 逐仓模式：**
```
🔥 调整杠杆 = 调整仓位保证金需求

降低杠杆（例如20x → 10x）：
- 需要更多保证金
- 必须追加保证金或减少仓位
- 检查：新保证金需求 <= 逐仓保证金 + 可用余额

提高杠杆（例如10x → 20x）：
- 需要的保证金减少
- 多余保证金可取出（可选）
- 检查：新维持保证金 <= 逐仓保证金（避免立即强平）

⚠️ 限制：
- 不能调整到会立即触发强平的杠杆
- 需要检查新杠杆下的强平价是否安全
```

**有持仓时 - 全仓模式：**
```
⚠️ 大部分交易所不支持全仓持仓时调整杠杆

原因：
- 全仓保证金是共享的，调整单个仓位杠杆无意义
- 全仓的"有效杠杆"由总仓位价值/账户保证金动态计算

替代方案：
- 通过增加/减少仓位来调整风险敞口
- 通过追加保证金来降低整体风险
```

**杠杆档位与维持保证金率：**
```
不同杠杆对应不同的维持保证金率（风险阶梯）

示例（Binance风格）：
杠杆1-20倍：  维持保证金率 0.5%
杠杆21-50倍： 维持保证金率 1.0%
杠杆51-100倍：维持保证金率 2.5%
杠杆101-125倍：维持保证金率 5.0%

⚠️ 高杠杆 = 高维持保证金率 = 更容易触发强平
```

### 11.7 模式切换的边界条件

**有挂单时：**
```
❌ 禁止切换保证金模式

原因：
- 挂单已冻结保证金
- 切换模式会导致保证金计算错乱

解决方案：
- 先撤销所有相关挂单
- 然后再执行模式切换
```

**爆仓边缘时：**
```
❌ 禁止从逐仓切换到全仓（可能立即触发全仓强平）
⚠️ 允许从全仓切换到逐仓（需要足够的可用保证金）

检查逻辑：
1. 计算切换后的保证金率
2. 如果保证金率 <= 维持保证金率 × 1.2（安全系数），拒绝切换
3. 提示用户："当前风险过高，无法切换模式"
```

**逐仓转全仓：**
```
1. 归还逐仓保证金到钱包余额
2. 仓位加入全仓保证金池
3. 重新计算账户保证金率

检查条件：
- 切换后账户保证金率必须 > 维持保证金率 × 1.5
- 否则拒绝切换

Ledger记账：
借: 钱包余额    1000 USDT
   贷: 逐仓保证金  1000 USDT
   BizType: SWITCH_TO_CROSS
```

**全仓转逐仓：**
```
1. 从钱包余额扣除保证金
2. 转入逐仓保证金
3. 从全仓保证金池移除该仓位

检查条件：
- 钱包余额必须 >= 所需逐仓保证金
- 切换后账户剩余仓位的保证金率必须安全

Ledger记账：
借: 逐仓保证金  1000 USDT
   贷: 钱包余额  1000 USDT
   BizType: SWITCH_TO_ISOLATED
```

### 11.8 与Hard-Risk / Ledger 的集成点

**开仓前 - Hard Risk 检查：**
```
OMS收到开仓订单
    ↓
Hard Risk Gate（同步阻断）
    ↓
调用 Margin-Mode-Core:
  1. validateMarginSufficient(userId, symbol, side, marginMode, requiredMargin)
  2. getAvailableMargin(userId, marginMode)
    ↓
逐仓模式：
  - 检查钱包可用余额 >= 逐仓保证金
  - 扣减可用余额（预占）
    ↓
全仓模式：
  - 检查账户保证金率 >= 初始保证金率
  - 计算开仓后新的保证金率
  - 如果保证金率过低，拒绝开仓
    ↓
通过检查 → 进入撮合
失败 → 拒绝订单
```

**成交后 - Ledger 记账：**
```
撮合引擎生成 TradeEvent
    ↓
Clearing Service 消费事件
    ↓
调用 Ledger-Core 记账：

逐仓开仓：
借: 逐仓保证金    500 USDT
   贷: 可用余额    500 USDT
   BizType: ISOLATED_MARGIN_OPEN
   BizId: positionId

全仓开仓：
借: 仓位名义价值  10000 USDT
   贷: 可用余额    1000 USDT (占用初始保证金)
   BizType: CROSS_MARGIN_OPEN
   BizId: positionId
    ↓
Ledger 发布 AccountDelta / PositionDelta 事件
    ↓
Margin-Mode-Core 消费事件，更新：
  - PositionMarginDetail（仓位保证金详情）
  - CrossMarginSnapshot（全仓账户快照）
```

**平仓后 - 保证金释放：**
```
逐仓平仓：
借: 可用余额    550 USDT (本金500 + 盈利50)
   贷: 逐仓保证金  500 USDT
   贷: 已实现盈亏   50 USDT
   BizType: ISOLATED_POSITION_CLOSE

全仓平仓：
借: 可用余额    1050 USDT
   贷: 仓位名义价值  10000 USDT
   贷: 已实现盈亏   50 USDT
   BizType: CROSS_POSITION_CLOSE
    ↓
Margin-Mode-Core 删除仓位保证金详情
重新计算全仓账户快照
```

### 11.9 保证金变动流水完整记录

**所有保证金变动必须记录到 t_margin_change_log：**

| 变动类型 | 触发场景 | 金额方向 | 关联业务 |
|---------|---------|---------|---------|
| OPEN | 逐仓开仓 | 正（增加） | 订单ID |
| CLOSE | 逐仓平仓 | 负（减少） | 订单ID |
| ADD | 追加保证金 | 正（增加） | 用户操作 |
| REMOVE | 减少保证金 | 负（减少） | 用户操作 |
| MODE_CHANGE | 模式切换 | ±（转移） | 切换记录ID |
| LIQUIDATION | 强平扣除 | 负（归零） | 强平订单ID |
| FUNDING_FEE | 资金费率 | ±（支付/收取） | 资金费率结算ID |
| ADL | 自动减仓 | 负（减少） | ADL记录ID |

**流水查询用途：**
```
1. 审计追溯：所有保证金变动可追溯
2. 用户对账：用户可查看保证金历史
3. 风控分析：分析用户保证金调整习惯
4. 异常检测：检测异常的保证金变动
```

### 11.10 关键业务规则总结

**保证金充足性检查：**
```
✅ 开仓前必须检查
✅ 追加保证金时必须检查可用余额
✅ 模式切换时必须检查新模式的保证金要求
✅ 杠杆调整时必须检查新杠杆的保证金要求
```

**强平触发条件：**
```
逐仓：逐仓保证金率 <= 维持保证金率
全仓：账户保证金率 <= 全仓维持保证金率

计算频率：
- 标记价格每次更新时重新计算（高频）
- Risk Monitor 消费 mark-price-topic + position-delta-topic
```

**保证金计算精度：**
```
🔥 关键：所有金额使用 long 存储，精度8位小数

例如：
1 USDT = 100000000 (10^8)
0.00000001 USDT = 1 (最小精度)

计算时先乘后除，避免精度损失：
错误：amount / price * leverage
正确：(amount * leverage) / price
```

**并发控制：**
```
🔥 关键操作需要加锁避免并发问题：

1. 追加/减少保证金：用户级分布式锁
2. 模式切换：用户级分布式锁
3. 强平执行：仓位级分布式锁
4. 全仓快照计算：用户级分布式锁

锁的Key格式：
margin:lock:user:{userId}
margin:lock:position:{positionId}
```
