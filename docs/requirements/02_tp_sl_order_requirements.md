# 止盈止损订单系统 (Take-Profit / Stop-Loss Orders) - P0 需求文档

## 1. 功能概述

### 1.1 什么是止盈止损订单
止盈止损订单是一种条件订单，当市场价格达到预设的触发价格时，自动以市价或限价执行平仓操作。

### 1.2 订单类型
| 类型 | 触发条件 | 用途 |
|------|----------|------|
| TP (Take Profit) | 价格达到盈利目标 | 锁定利润 |
| SL (Stop Loss) | 价格达到止损目标 | 控制亏损 |
| TPSL (组合订单) | 同时设置TP和SL | 开仓时同时设置止盈止损 |
| Trailing Stop | 跟踪最优价格 | 动态止盈 |

### 1.3 触发机制
- **标记价格触发**: 使用标记价格判断是否触发（默认，防插针）
- **最新价格触发**: 使用最新成交价判断
- **指数价格触发**: 使用指数价格判断

## 2. 业务需求

### 2.1 止盈止损触发逻辑

#### 多单止盈 (TP)
```
触发条件: 标记价格 >= 触发价格
执行: 市价平多仓 / 限价平多仓
```

#### 多单止损 (SL)
```
触发条件: 标记价格 <= 触发价格
执行: 市价平多仓 / 限价平多仓
```

#### 空单止盈 (TP)
```
触发条件: 标记价格 <= 触发价格
执行: 市价平空仓 / 限价平空仓
```

#### 空单止损 (SL)
```
触发条件: 标记价格 >= 触发价格
执行: 市价平空仓 / 限价平空仓
```

### 2.2 移动止损 (Trailing Stop)
```
回调幅度 = 最高(低)价 - 当前价
触发条件: 回调幅度 >= 回调比例/金额

示例:
- 用户设置回调比例 5%
- BTC多单开仓价 $50,000
- 价格上涨到 $55,000 (最高价)
- 当价格回调到 $52,250 (55,000 × 0.95) 时触发
```

### 2.3 订单绑定规则
1. **与持仓绑定**: 每个止盈止损订单必须关联一个持仓
2. **数量限制**: 一个持仓可同时绑定多个TP/SL订单
3. **撤单规则**: 
   - 持仓平仓后，自动撤销关联的TP/SL订单
   - 用户可手动撤销TP/SL订单
4. **生效条件**: 必须有对应方向的持仓才能生效

## 3. 系统架构

### 3.1 模块划分
```
tp-sl-core (端口: 8089)
├── TP/SL订单管理服务
├── 价格监控服务 (监听mark price)
├── 触发执行服务
├── 跟踪止损计算服务
└── 订单生命周期管理
```

### 3.2 与其他模块关系
```
tp-sl-core
    ↓ 监听
Kafka: mark-price-update (标记价格更新)
    ↓ 调用
oms-core (创建平仓订单)
    ↓ 更新
position-snapshot-core (获取持仓信息)
```

## 4. 数据模型

### 4.1 TP/SL订单表 (t_tp_sl_order)
```sql
CREATE TABLE t_tp_sl_order (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id            BIGINT NOT NULL COMMENT 'TP/SL订单ID',
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 关联信息
    position_id         BIGINT COMMENT '关联持仓ID',
    parent_order_id     BIGINT COMMENT '关联父订单ID(开仓订单)',
    
    -- 订单类型
    order_type          VARCHAR(16) NOT NULL COMMENT '类型: TP/SL/TPSL/TRAILING',
    trigger_type        VARCHAR(16) NOT NULL DEFAULT 'MARK' COMMENT '触发类型: MARK/LAST/INDEX',
    
    -- 触发条件
    trigger_price       BIGINT NOT NULL COMMENT '触发价格',
    trigger_side        VARCHAR(8) NOT NULL COMMENT '触发方向: LONG/SHORT(要平的仓位)',
    
    -- 执行配置
    exec_type           VARCHAR(16) NOT NULL COMMENT '执行类型: MARKET/LIMIT',
    exec_price          BIGINT COMMENT '执行限价(当exec_type=LIMIT时)',
    quantity            BIGINT NOT NULL COMMENT '平仓数量',
    
    -- 移动止损配置
    trailing_offset     BIGINT COMMENT '回调偏移量(金额)',
    trailing_percent    BIGINT COMMENT '回调比例(如500=5%)',
    highest_price       BIGINT COMMENT '最高/低价(用于计算回调)',
    
    -- 状态
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
    -- ACTIVE: 生效中
    -- TRIGGERED: 已触发
    -- EXECUTED: 已执行
    -- CANCELLED: 已取消
    -- EXPIRED: 已过期
    
    trigger_time        BIGINT COMMENT '触发时间',
    exec_order_id       BIGINT COMMENT '生成的平仓订单ID',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_order_id (order_id),
    KEY idx_user_id (user_id),
    KEY idx_symbol (symbol),
    KEY idx_position_id (position_id),
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='止盈止损订单表';
```

### 4.2 TP/SL执行日志表 (t_tp_sl_exec_log)
```sql
CREATE TABLE t_tp_sl_exec_log (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    tp_sl_order_id      BIGINT NOT NULL COMMENT 'TP/SL订单ID',
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    
    trigger_price       BIGINT NOT NULL COMMENT '触发价格',
    mark_price          BIGINT NOT NULL COMMENT '触发时的标记价格',
    exec_type           VARCHAR(16) NOT NULL COMMENT '执行类型',
    quantity            BIGINT NOT NULL COMMENT '执行数量',
    
    exec_order_id       BIGINT COMMENT '生成的平仓订单ID',
    exec_result         VARCHAR(16) COMMENT '执行结果: SUCCESS/FAIL',
    error_msg           VARCHAR(512) COMMENT '错误信息',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    KEY idx_tp_sl_order_id (tp_sl_order_id),
    KEY idx_user_id (user_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB COMMENT='TP/SL执行日志表';
```

## 5. API 接口设计

### 5.1 设置止盈止损 (开仓时)
```
POST /api/v1/order/create-with-tpsl

Request:
{
    "userId": 10001,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": "50000",
    "quantity": "1.0",
    "leverage": 10,
    "tpSlConfig": {
        "tpTriggerPrice": "55000",      // 止盈触发价
        "tpExecType": "MARKET",          // 止盈执行类型
        "slTriggerPrice": "45000",      // 止损触发价
        "slExecType": "MARKET",          // 止损执行类型
        "triggerBy": "MARK"              // 触发价格类型
    }
}

Response:
{
    "code": 0,
    "data": {
        "orderId": 100001,
        "tpOrderId": 200001,
        "slOrderId": 200002
    }
}
```

### 5.2 为已有持仓设置TP/SL
```
POST /api/v1/tp-sl/create

Request:
{
    "userId": 10001,
    "symbol": "BTCUSDT",
    "positionId": 50001,
    "type": "TP",                       // TP/SL/TRAILING
    "triggerPrice": "55000",
    "execType": "MARKET",
    "quantity": "1.0",
    "triggerBy": "MARK"
}

Response:
{
    "code": 0,
    "data": {
        "tpSlOrderId": 200003
    }
}
```

### 5.3 设置移动止损
```
POST /api/v1/tp-sl/create-trailing

Request:
{
    "userId": 10001,
    "symbol": "BTCUSDT",
    "positionId": 50001,
    "side": "LONG",                     // 持仓方向
    "trailingPercent": "5",             // 回调5%
    "quantity": "1.0",
    "triggerBy": "MARK"
}

Response:
{
    "code": 0,
    "data": {
        "tpSlOrderId": 200004
    }
}
```

### 5.4 修改TP/SL
```
POST /api/v1/tp-sl/modify

Request:
{
    "userId": 10001,
    "tpSlOrderId": 200001,
    "triggerPrice": "56000",            // 新触发价
    "quantity": "0.5"                   // 新数量
}
```

### 5.5 撤销TP/SL
```
POST /api/v1/tp-sl/cancel

Request:
{
    "userId": 10001,
    "tpSlOrderId": 200001
}
```

### 5.6 批量撤销TP/SL
```
POST /api/v1/tp-sl/cancel-batch

Request:
{
    "userId": 10001,
    "symbol": "BTCUSDT",
    "positionId": 50001                 // 可选，不填则撤销该symbol下所有
}
```

### 5.7 查询TP/SL订单
```
GET /api/v1/tp-sl/list?symbol=BTCUSDT&status=ACTIVE

Response:
{
    "code": 0,
    "data": [
        {
            "tpSlOrderId": 200001,
            "symbol": "BTCUSDT",
            "type": "TP",
            "triggerPrice": "55000",
            "execType": "MARKET",
            "quantity": "1.0",
            "triggerBy": "MARK",
            "status": "ACTIVE",
            "createdAt": 1704067200000
        }
    ]
}
```

### 5.8 查询持仓关联的TP/SL
```
GET /api/v1/tp-sl/by-position?positionId=50001

Response:
{
    "code": 0,
    "data": {
        "positionId": 50001,
        "takeProfit": {
            "tpSlOrderId": 200001,
            "triggerPrice": "55000",
            "status": "ACTIVE"
        },
        "stopLoss": {
            "tpSlOrderId": 200002,
            "triggerPrice": "45000",
            "status": "ACTIVE"
        }
    }
}
```

## 6. 核心流程

### 6.1 价格监控与触发流程
```
Kafka Consumer (mark-price-update)
    │
    ▼
┌─────────────────┐
│ 获取该symbol下  │
│ 所有ACTIVE订单  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 检查每个订单    │
│ 是否满足触发条件 │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
  触发      未触发
    │         │
    ▼         │
┌─────────┐   │
│ 更新状态 │   │
│ TRIGGERED│   │
└────┬────┘   │
     │        │
     ▼        │
┌─────────┐   │
│ 创建平仓 │   │
│ 订单    │   │
└────┬────┘   │
     │        │
     ▼        │
┌─────────┐   │
│ 发送到  │   │
│ OMS    │   │
└─────────┘   │
```

### 6.2 持仓平仓后自动撤销流程
```
Kafka Consumer (position-closed event)
    │
    ▼
┌─────────────────┐
│ 查询该position  │
│ 关联的TP/SL订单 │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 批量撤销ACTIVE  │
│ 状态的TP/SL     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 发布撤销事件    │
└─────────────────┘
```

### 6.3 移动止损更新流程
```
价格更新
    │
    ▼
┌─────────────────┐
│ 获取TRAILING类型 │
│ 的ACTIVE订单    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 更新最高/低价   │
│ 计算新触发价    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 保存新触发价    │
│ (但不触发执行)  │
└─────────────────┘
```

## 7. Kafka Topic 设计

| Topic | 用途 | 生产者 | 消费者 |
|-------|------|--------|--------|
| mark-price-update | 标记价格更新 | mark-price-service | tp-sl-core |
| position-closed | 持仓平仓事件 | ledger-core | tp-sl-core |
| tp-sl-triggered | TP/SL触发事件 | tp-sl-core | oms-core |
| tp-sl-cancelled | TP/SL撤销事件 | tp-sl-core | 各模块 |

## 8. 风控要求

### 8.1 前置检查
1. 用户必须有对应方向的持仓
2. 平仓数量不能超过持仓数量
3. 触发价格必须合理（TP盈利方向、SL亏损方向）
4. 移动止损回调幅度不能过小（防止误触发）

### 8.2 触发后风控
1. 平仓订单需要经过硬风控检查
2. 如果风控拒绝，记录失败日志，尝试市价平仓
3. 连续失败触发告警

## 9. 性能要求

| 指标 | 目标值 |
|------|--------|
| 价格监控延迟 | < 50ms |
| 触发到下单延迟 | < 100ms |
| 支持同时监控订单数 | > 100万 |
| 查询接口响应时间 | < 30ms |

## 10. 特殊场景处理

### 10.1 插针保护
- 使用标记价格触发而非最新价
- 指数价格与标记价格偏差过大时暂停触发

### 10.2 部分平仓
- TP/SL可以设置部分平仓数量
- 剩余持仓仍保持原TP/SL生效

### 10.3 仓位变化
- 减仓后自动调整TP/SL数量
- 加仓不影响现有TP/SL

### 10.4 订单冲突
- 一个持仓的TP和SL同时触发时，优先执行SL（风控优先）
- 记录冲突日志
