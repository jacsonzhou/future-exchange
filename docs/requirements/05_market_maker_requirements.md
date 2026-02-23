# 做市商接口系统 (Market Maker API) - P0 需求文档

## 1. 功能概述

### 1.1 什么是做市商
做市商(Market Maker)是通过持续提供双边报价(买/卖)，为市场提供流动性的专业机构。做市商通过买卖价差获利，同时降低市场滑点。

### 1.2 做市商特权
| 特权 | 说明 |
|------|------|
| 更低手续费 | Maker费率优惠，甚至负费率(返佣) |
| 更高频率限制 | 更高的API调用频率限制 |
| 批量订单接口 | 支持批量下单、撤单 |
| 特殊订单类型 | 仅做市商可用的订单类型 |
| 优先撮合 | 同等价格下优先成交 |
| 保证金优惠 | 可能获得更高的杠杆 |

### 1.3 做市义务
| 义务 | 要求 |
|------|------|
| 最小挂单时间 | 订单必须在book中停留一定时间 |
| 最大买卖价差 | 买/卖报价不能超过指定比例 |
| 最小挂单数量 | 每个价格档位最小挂单量 |
| 持续报价 | 交易时间内持续提供双边报价 |

## 2. 业务需求

### 2.1 做市商等级体系
```
等级1 - 普通做市商
- 月交易量 > 1000 BTC
- Maker返佣: 0.02%
- API限制: 1000 req/s

等级2 - 高级做市商
- 月交易量 > 5000 BTC
- Maker返佣: 0.03%
- API限制: 2000 req/s

等级3 - 顶级做市商
- 月交易量 > 20000 BTC
- Maker返佣: 0.05%
- API限制: 5000 req/s
- 专属客户经理
```

### 2.2 做市商考核指标
| 指标 | 计算方式 | 最低要求 |
|------|----------|----------|
| 挂单时间占比 | 有挂单时间 / 总交易时间 | > 80% |
| 平均买卖价差 | (卖一 - 买一) / 中间价 | < 0.1% |
| 平均挂单深度 | 买单总量 + 卖单总量 | > 100 BTC |
| 订单成交率 | Maker成交 / 总下单 | > 30% |
| 订单撤单率 | 撤单数 / 总下单数 | < 50% |

### 2.3 惩罚机制
- 连续不达标: 降低等级
- 严重违规: 取消做市商资格
- 恶意报价: 封禁账户

## 3. 系统架构

### 3.1 模块划分
```
market-maker-core (端口: 8092)
├── 做市商管理服务
├── 批量订单服务
├── 报价质量监控服务
├── 绩效统计服务
├── 特殊订单类型服务
└── 费率优惠计算服务
```

### 3.2 与其他模块关系
```
market-maker-core
    ↓ 调用
oms-core (批量订单)
    ↓ 监听
match-engine-core (成交事件)
    ↓ 更新
ledger-core (费率记账)
    ↓ 推送
ws-gateway (实时报价)
```

## 4. 数据模型

### 4.1 做市商申请表 (t_market_maker_application)
```sql
CREATE TABLE t_market_maker_application (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    
    -- 申请信息
    company_name        VARCHAR(128) COMMENT '公司名',
    contact_name        VARCHAR(64) NOT NULL COMMENT '联系人',
    contact_email       VARCHAR(128) NOT NULL COMMENT '联系邮箱',
    contact_phone       VARCHAR(32) COMMENT '联系电话',
    
    -- 资质证明
    license_no          VARCHAR(64) COMMENT '牌照号码',
    license_doc         VARCHAR(256) COMMENT '牌照文件URL',
    
    -- 历史业绩
    other_exchanges     VARCHAR(512) COMMENT '其他交易所做市经历',
    monthly_volume      BIGINT COMMENT '月均交易量(USD)',
    
    -- 申请状态
    status              VARCHAR(16) DEFAULT 'PENDING' COMMENT '状态',
    -- PENDING: 待审核
    -- APPROVED: 已通过
    -- REJECTED: 已拒绝
    -- REVOKED: 已撤销
    
    level               INT COMMENT '获批等级',
    approved_by         BIGINT COMMENT '审核人ID',
    approved_at         TIMESTAMP COMMENT '审核时间',
    reject_reason       VARCHAR(512) COMMENT '拒绝原因',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_user_id (user_id),
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='做市商申请表';
```

### 4.2 做市商信息表 (t_market_maker)
```sql
CREATE TABLE t_market_maker (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    
    -- 等级信息
    level               INT NOT NULL DEFAULT 1 COMMENT '做市商等级',
    status              VARCHAR(16) DEFAULT 'ACTIVE' COMMENT '状态',
    -- ACTIVE: 正常
    -- SUSPENDED: 暂停
    -- TERMINATED: 终止
    
    -- 费率配置
    maker_fee_rate      BIGINT NOT NULL COMMENT 'Maker费率(如-2=-0.02%)',
    taker_fee_rate      BIGINT NOT NULL COMMENT 'Taker费率',
    
    -- 限制配置
    api_limit_per_sec   INT NOT NULL COMMENT 'API频率限制(每秒)',
    max_order_count     INT NOT NULL COMMENT '最大挂单数量',
    max_position_value  BIGINT COMMENT '最大持仓价值',
    
    -- 考核周期
    eval_period_start   DATE COMMENT '考核周期开始',
    eval_period_end     DATE COMMENT '考核周期结束',
    
    -- 账户信息
    mm_account_id       BIGINT COMMENT '做市商专用账户ID',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_user_id (user_id),
    KEY idx_level (level),
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='做市商信息表';
```

### 4.3 做市商考核指标表 (t_mm_performance)
```sql
CREATE TABLE t_mm_performance (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    period_date         DATE NOT NULL COMMENT '考核日期',
    
    -- 交易统计
    total_order_count   INT DEFAULT 0 COMMENT '总下单数',
    filled_order_count  INT DEFAULT 0 COMMENT '成交订单数',
    cancelled_order_count INT DEFAULT 0 COMMENT '撤单数',
    maker_volume        BIGINT DEFAULT 0 COMMENT 'Maker成交量',
    taker_volume        BIGINT DEFAULT 0 COMMENT 'Taker成交量',
    
    -- 质量指标
    quote_time_ratio    BIGINT COMMENT '挂单时间占比(如8000=80%)',
    avg_spread          BIGINT COMMENT '平均价差',
    avg_depth_bid       BIGINT COMMENT '平均买方深度',
    avg_depth_ask       BIGINT COMMENT '平均卖方深度',
    
    -- 计算结果
    score               INT COMMENT '综合得分',
    is_qualified        TINYINT DEFAULT 0 COMMENT '是否达标',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_user_symbol_date (user_id, symbol, period_date),
    KEY idx_user_id (user_id),
    KEY idx_period_date (period_date)
) ENGINE=InnoDB COMMENT='做市商考核指标表';
```

### 4.4 做市商费率流水表 (t_mm_fee_log)
```sql
CREATE TABLE t_mm_fee_log (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    trade_id            BIGINT NOT NULL COMMENT '成交ID',
    
    -- 费率信息
    fee_type            VARCHAR(16) NOT NULL COMMENT '费率类型',
    -- MAKER_REBATE: Maker返佣
    -- MAKER_FEE: Maker手续费
    -- TAKER_FEE: Taker手续费
    
    fee_amount          BIGINT NOT NULL COMMENT '手续费金额(负=返佣)',
    fee_rate            BIGINT NOT NULL COMMENT '费率',
    
    -- 成交信息
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    side                VARCHAR(8) NOT NULL COMMENT '方向',
    price               BIGINT NOT NULL COMMENT '价格',
    quantity            BIGINT NOT NULL COMMENT '数量',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    KEY idx_user_id (user_id),
    KEY idx_trade_id (trade_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB COMMENT='做市商费率流水表';
```

## 5. API 接口设计

### 5.1 批量下单
```
POST /api/v1/mm/batch-order/create

Request:
{
    "batchId": "batch_001",             // 批次ID(幂等)
    "orders": [
        {
            "symbol": "BTCUSDT",
            "side": "BUY",
            "orderType": "LIMIT",
            "price": "50000",
            "quantity": "1.0",
            "timeInForce": "GTC",
            "postOnly": true                // 仅做maker
        },
        {
            "symbol": "BTCUSDT",
            "side": "SELL",
            "orderType": "LIMIT",
            "price": "50100",
            "quantity": "1.0",
            "timeInForce": "GTC",
            "postOnly": true
        }
    ]
}

Response:
{
    "code": 0,
    "data": {
        "batchId": "batch_001",
        "totalCount": 2,
        "successCount": 2,
        "failCount": 0,
        "results": [
            {
                "index": 0,
                "success": true,
                "orderId": 100001
            },
            {
                "index": 1,
                "success": true,
                "orderId": 100002
            }
        ]
    }
}
```

### 5.2 批量撤单
```
POST /api/v1/mm/batch-order/cancel

Request:
{
    "batchId": "cancel_batch_001",
    "orderIds": [100001, 100002, 100003],
    // 或按条件撤销
    "symbol": "BTCUSDT",
    "side": "BUY"
}

Response:
{
    "code": 0,
    "data": {
        "batchId": "cancel_batch_001",
        "totalCount": 3,
        "successCount": 3,
        "results": [
            {
                "orderId": 100001,
                "success": true
            }
        ]
    }
}
```

### 5.3 一键撤单（仅做市商）
```
POST /api/v1/mm/order/cancel-all

Request:
{
    "symbol": "BTCUSDT",                // 可选，不传则撤销全部
    "side": "BUY"                       // 可选
}

Response:
{
    "code": 0,
    "data": {
        "cancelledCount": 50
    }
}
```

### 5.4 修改订单（仅价格/数量）
```
POST /api/v1/mm/order/modify

Request:
{
    "orderId": 100001,
    "newPrice": "50050",                // 新价格
    "newQuantity": "2.0"                // 新数量
}

Response:
{
    "code": 0,
    "data": {
        "orderId": 100001,
        "oldPrice": "50000",
        "newPrice": "50050",
        "modifyTime": 1704067200000
    }
}
```

### 5.5 查询做市商状态
```
GET /api/v1/mm/status

Response:
{
    "code": 0,
    "data": {
        "isMarketMaker": true,
        "level": 2,
        "status": "ACTIVE",
        "makerFeeRate": "-0.03%",
        "takerFeeRate": "0.04%",
        "apiLimit": 2000,
        "evalPeriod": {
            "start": "2024-01-01",
            "end": "2024-01-31"
        }
    }
}
```

### 5.6 查询考核指标
```
GET /api/v1/mm/performance?symbol=BTCUSDT&startDate=2024-01-01&endDate=2024-01-31

Response:
{
    "code": 0,
    "data": {
        "summary": {
            "avgQuoteTimeRatio": "85%",
            "avgSpread": "0.05%",
            "avgDepth": "150 BTC",
            "makerVolume": "5000 BTC",
            "isQualified": true
        },
        "daily": [
            {
                "date": "2024-01-01",
                "quoteTimeRatio": "82%",
                "spread": "0.06%",
                "score": 85
            }
        ]
    }
}
```

### 5.7 查询费率优惠记录
```
GET /api/v1/mm/fee-log?startTime=xxx&endTime=xxx

Response:
{
    "code": 0,
    "data": {
        "totalRebate": "500 USDT",        // 总返佣
        "logs": [
            {
                "tradeId": 100001,
                "symbol": "BTCUSDT",
                "side": "BUY",
                "price": "50000",
                "quantity": "1.0",
                "feeRate": "-0.03%",
                "feeAmount": "-15 USDT",    // 负数表示返佣
                "time": 1704067200000
            }
        ]
    }
}
```

### 5.8 WebSocket深度推送（做市商专用）
```
订阅: /ws/mm/depth

推送内容(增量):
{
    "symbol": "BTCUSDT",
    "type": "delta",
    "bids": [
        ["50000", "1.0"],   // 价格, 数量
        ["49990", "2.0"]
    ],
    "asks": [
        ["50100", "1.0"],
        ["50110", "3.0"]
    ],
    "timestamp": 1704067200000
}
```

### 5.9 做市商申请
```
POST /api/v1/mm/apply

Request:
{
    "companyName": "ABC Trading",
    "contactName": "John Doe",
    "contactEmail": "john@abctrading.com",
    "contactPhone": "+1234567890",
    "licenseNo": "MM123456",
    "otherExchanges": "Binance, OKX",
    "monthlyVolume": 10000000
}

Response:
{
    "code": 0,
    "data": {
        "applicationId": 10001,
        "status": "PENDING",
        "message": "申请已提交，审核需要3-5个工作日"
    }
}
```

## 6. 核心流程

### 6.1 批量订单处理流程
```
接收批量订单请求
    │
    ▼
┌─────────────────┐
│ 验证做市商身份  │
│ 和权限          │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 频率限制检查    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 逐笔风控检查    │
│ (批量并行)      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 发送到撮合引擎  │
│ (批量)          │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 返回批量结果    │
└─────────────────┘
```

### 6.2 费率计算流程
```
成交发生
    │
    ▼
┌─────────────────┐
│ 检查用户是否    │
│ 为做市商        │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
   是         否
    │         │
    ▼         ▼
┌─────────┐ ┌─────────┐
│ 使用做市 │ │ 使用普通 │
│ 商费率   │ │ 用户费率 │
│ (可能为负)│ │        │
└────┬────┘ └────┬────┘
     │           │
     ▼           ▼
┌─────────────────┐
│ 计算手续费/返佣 │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 保存费率流水    │
│ 更新余额        │
└─────────────────┘
```

### 6.3 考核统计流程
```
定时触发(每日)
    │
    ▼
┌─────────────────┐
│ 获取当日所有    │
│ 做市商交易数据  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 计算各项指标    │
│ - 挂单时间      │
│ - 买卖价差      │
│ - 挂单深度      │
│ - 成交统计      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 计算综合得分    │
│ 判断是否达标    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 保存考核结果    │
│ 连续不达标告警  │
└─────────────────┘
```

## 7. Ledger 分录设计

### 7.1 Maker返佣
```
做市商用户A，Maker成交 1 BTC @ $50,000
Maker返佣率: 0.03% = $15

分录:
1. 借: 平台手续费收入  15 USDT
      贷: 用户A可用余额  15 USDT
      
   BizType: MAKER_REBATE
```

### 7.2 Taker手续费（优惠费率）
```
做市商用户A，Taker成交 1 BTC @ $50,000
优惠Taker费率: 0.04% = $20

分录:
1. 借: 用户A可用余额  20 USDT
      贷: 平台手续费收入  20 USDT
      
   BizType: TAKER_FEE
```

## 8. 特殊订单类型

### 8.1 Post-Only订单（仅做市商）
```
确保订单只会作为Maker成交
如果会立即与现有订单成交，则自动撤销
```

### 8.2 冰山订单(Iceberg)
```
显示数量 < 实际数量
每成交一部分后自动补充显示数量
适合大额做市

Request:
{
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "ICEBERG",
    "price": "50000",
    "totalQuantity": "100.0",           // 总数量
    "visibleQuantity": "1.0",           // 显示数量
    "variance": "0.1"                   // 数量随机波动范围
}
```

### 8.3 做市订单(Quote)
```
同时发送买单和卖单
如果其中一张成交，自动撤销另一张
适合快速报价

Request:
{
    "symbol": "BTCUSDT",
    "orderType": "QUOTE",
    "bidPrice": "50000",
    "bidQty": "1.0",
    "askPrice": "50100",
    "askQty": "1.0",
    "oco": true                         // 成对撤单
}
```

## 9. 风控要求

### 9.1 API风控
1. **频率限制**: 按等级限制每秒请求数
2. **突发限制**: 短时间内请求过多临时封禁
3. **IP白名单**: 做市商绑定固定IP
4. **签名验证**: 强制的API Key签名

### 9.2 交易风控
1. **最大挂单数**: 防止book被塞满
2. **最小挂单时间**: 防止高频刷单
3. **报价偏离限制**: 报价不能偏离最新价过多
4. **自成交限制**: 防止自己跟自己交易

## 10. 监控告警

### 10.1 关键监控指标
- 做市商API响应时间
- 批量订单成功率
- 费率优惠发放金额
- 考核达标率
- 买卖价差监控

### 10.2 告警规则
- 做市商连续3天不达标: P1告警
- 买卖价差异常扩大: P0告警
- 做市商API大量失败: P0告警
- 费率发放异常: P0告警
