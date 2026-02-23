# Market Maker Core - 做市商核心服务

## 📋 概述

做市商核心服务（Market Maker Core）负责管理做市商的整个生命周期，包括申请、审核、等级管理、批量订单处理、费率优惠计算、考核指标统计等功能。

**端口**: 8092

## 🎯 核心功能

### 1. 做市商管理
- ✅ 做市商申请与审核
- ✅ 等级体系管理（4个等级）
- ✅ 状态管理（ACTIVE/SUSPENDED/TERMINATED/IN_COOLING_PERIOD/WITHDRAWN）
- ✅ 费率配置管理
- ✅ API频率限制配置

### 2. 批量订单服务
- ✅ 批量下单（支持幂等）
- ✅ 批量撤单
- ✅ 一键撤单（按symbol/side过滤）
- ✅ 订单修改（先撤后下）

### 3. 费率优惠服务
- ✅ Maker返佣计算（负费率）
- ✅ Taker优惠费率
- ✅ 费率流水记录
- ✅ 费率查询统计

### 4. 考核指标服务
- ✅ 每日考核指标计算
- ✅ 挂单时间占比统计
- ✅ 买卖价差监控
- ✅ 挂单深度统计
- ✅ 成交率/撤单率分析
- ✅ 综合评分机制

### 5. 报价质量监控
- ✅ 实时监控做市商报价
- ✅ 异常告警
- ✅ 报价快照记录

## 🏗️ 架构设计

### 模块划分

```
market-maker-core
├── entity/              # 实体类
│   ├── MarketMaker           # 做市商信息
│   ├── MarketMakerApplication # 做市商申请
│   ├── MmPerformance         # 考核指标
│   ├── MmFeeLog              # 费率流水
│   └── MmQuoteSnapshot       # 报价快照
│
├── enums/               # 枚举类
│   ├── MmStatus              # 做市商状态
│   ├── MmLevel               # 做市商等级
│   ├── ApplicationStatus     # 申请状态
│   └── FeeType               # 费率类型
│
├── dto/                 # 数据传输对象
│   ├── request/              # 请求对象
│   │   ├── ApplyMarketMakerRequest
│   │   ├── BatchOrderRequest
│   │   ├── BatchCancelRequest
│   │   ├── ModifyOrderRequest
│   │   └── CancelAllRequest
│   └── response/             # 响应对象
│       ├── BatchOrderResponse
│       ├── MmStatusResponse
│       ├── MmPerformanceResponse
│       └── FeeLogResponse
│
├── mapper/              # 数据访问层
│   ├── MarketMakerMapper
│   ├── MarketMakerApplicationMapper
│   ├── MmPerformanceMapper
│   ├── MmFeeLogMapper
│   └── MmQuoteSnapshotMapper
│
├── service/             # 业务逻辑层
│   ├── MarketMakerService        # 做市商管理
│   ├── BatchOrderService         # 批量订单
│   ├── MmPerformanceService      # 考核统计
│   └── MmFeeService              # 费率服务
│
├── controller/          # 控制器层
│   ├── MarketMakerController     # 做市商管理接口
│   ├── BatchOrderController      # 批量订单接口
│   └── MmPerformanceController   # 考核查询接口
│
├── kafka/               # Kafka消息
│   ├── consumer/
│   │   └── TradeEventConsumer    # 成交事件消费
│   └── producer/
│       └── MmEventProducer       # 做市商事件生产
│
├── job/                 # 定时任务
│   └── MmPerformanceJob          # 每日考核计算
│
├── interceptor/         # 拦截器
│   └── MmAuthInterceptor         # 做市商身份验证
│
└── config/              # 配置类
    ├── MmConfig                  # 做市商配置
    ├── WebMvcConfig              # MVC配置
    └── ScheduleConfig            # 定时任务配置
```

## 📊 数据模型

### 核心表结构

| 表名 | 说明 | 关键字段 |
|-----|------|---------|
| t_market_maker_application | 做市商申请表 | user_id, status, level |
| t_market_maker | 做市商信息表 | user_id, level, maker_fee_rate, taker_fee_rate |
| t_mm_performance | 考核指标表 | user_id, symbol, period_date, score, is_qualified |
| t_mm_fee_log | 费率流水表 | user_id, trade_id, fee_type, fee_amount |
| t_mm_quote_snapshot | 报价快照表 | user_id, symbol, snapshot_time, spread |

## 🔌 API接口

### 1. 做市商管理

#### 1.1 申请做市商
```
POST /api/v1/mm/apply
Headers: X-User-Id

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
    "data": 10001,
    "timestamp": 1704067200000
}
```

#### 1.2 查询做市商状态
```
GET /api/v1/mm/status
Headers: X-User-Id

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

### 2. 批量订单

#### 2.1 批量下单
```
POST /api/v1/mm/batch-order/create
Headers: X-User-Id

Request:
{
    "batchId": "batch_001",
    "orders": [
        {
            "symbol": "BTCUSDT",
            "side": "BUY",
            "orderType": "LIMIT",
            "price": "50000",
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
        "results": [...]
    }
}
```

#### 2.2 批量撤单
```
POST /api/v1/mm/batch-order/cancel
Headers: X-User-Id

Request:
{
    "batchId": "cancel_batch_001",
    "orderIds": [100001, 100002]
}
```

#### 2.3 一键撤单
```
POST /api/v1/mm/batch-order/cancel-all
Headers: X-User-Id

Request:
{
    "symbol": "BTCUSDT",
    "side": "BUY"
}

Response:
{
    "code": 0,
    "data": {
        "cancelledCount": 50
    }
}
```

### 3. 考核查询

#### 3.1 查询考核指标
```
GET /api/v1/mm/performance?symbol=BTCUSDT&startDate=2024-01-01&endDate=2024-01-31
Headers: X-User-Id

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
        "daily": [...]
    }
}
```

#### 3.2 查询费率流水
```
GET /api/v1/mm/fee-log?startTime=2024-01-01%2000:00:00&endTime=2024-01-31%2023:59:59
Headers: X-User-Id

Response:
{
    "code": 0,
    "data": {
        "totalRebate": "500 USDT",
        "logs": [...]
    }
}
```

## 🔄 业务流程

### 1. 做市商申请流程
```
提交申请 → 待审核 → 人工审核 → 分配等级 → 激活账户 → 开始做市
```

### 2. 批量订单处理流程
```
接收批量订单
    ↓
验证做市商身份
    ↓
频率限制检查
    ↓
逐笔风控检查（并行）
    ↓
发送到撮合引擎
    ↓
返回批量结果
```

### 3. 费率计算流程
```
成交事件
    ↓
判断是否做市商
    ↓
获取做市商费率
    ↓
计算手续费/返佣
    ↓
记录费率流水
    ↓
更新账户余额
```

### 4. 每日考核流程
```
定时触发（凌晨2点）
    ↓
获取所有活跃做市商
    ↓
遍历每个做市商
    ↓
计算各项指标
    ↓
综合评分
    ↓
保存考核结果
    ↓
发送告警（如不达标）
```

## 🎚️ 做市商等级体系

| 等级 | 名称 | Maker费率 | Taker费率 | API限制 | 月交易量要求 |
|-----|------|----------|----------|---------|------------|
| 1 | 普通做市商 | -0.02% | 0.05% | 1000/s | > 1000 BTC |
| 2 | 高级做市商 | -0.03% | 0.04% | 2000/s | > 5000 BTC |
| 3 | 顶级做市商 | -0.05% | 0.03% | 5000/s | > 20000 BTC |
| 4 | 战略做市商 | -0.08% | 0.02% | 10000/s | 特殊邀请 |

## 📈 考核指标标准

| 指标 | 计算方式 | 最低要求 |
|-----|---------|---------|
| 挂单时间占比 | 有挂单时间 / 总交易时间 | > 80% |
| 平均买卖价差 | (卖一 - 买一) / 中间价 | < 0.1% |
| 平均挂单深度 | 买单总量 + 卖单总量 | > 100 BTC |
| 订单成交率 | Maker成交 / 总下单 | > 30% |
| 订单撤单率 | 撤单数 / 总下单数 | < 50% |

## 🚀 启动方式

### 1. 前置条件
- MySQL 8.0+
- Kafka 2.8+
- JDK 8+

### 2. 初始化数据库
```bash
mysql -u root -p < sql/market_maker.sql
```

### 3. 修改配置
```yaml
# application.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/exchange_mm
    username: root
    password: your_password

  kafka:
    bootstrap-servers: localhost:9092
```

### 4. 启动服务
```bash
cd market-maker-core
mvn spring-boot:run
```

### 5. 验证启动
```bash
curl http://localhost:8092/api/v1/mm/status \
  -H "X-User-Id: 10001"
```

## 🔧 配置说明

### 做市商配置（application.yml）
```yaml
market-maker:
  # 考核配置
  evaluation:
    min-quote-time-ratio: 80000000  # 最小挂单时间占比（80%）
    max-spread: 100000              # 最大买卖价差（0.1%）
    min-depth: 50000000000          # 最小挂单深度（50 BTC）
    min-fill-rate: 50000000         # 最小成交率（50%）

  # 费率配置
  fee:
    level-1-maker: 20000            # 等级1 Maker费率（-0.02%）
    level-1-taker: 50000            # 等级1 Taker费率（0.05%）
    # ... 其他等级配置

  # 奖励配置
  reward:
    base-qualified-reward: 10000000000   # 达标奖励（100 USDT）
    base-excellent-reward: 50000000000   # 优秀奖励（500 USDT）
```

## 📊 监控指标

### 业务监控
- 做市商申请转化率
- 各等级做市商分布
- 做市商考核达标率
- 平均返佣金额

### 技术监控
- API响应时间（P99 < 50ms）
- 批量订单处理性能
- Kafka消息消费延迟
- 定时任务执行状态

## ⚠️ 注意事项

### 1. 幂等性保证
- 批量下单使用batchId保证幂等
- 批量撤单使用batchId保证幂等

### 2. 频率限制
- 根据做市商等级限制API调用频率
- 使用Redis或内存限流器

### 3. 数据精度
- 所有金额和价格使用8位精度（100000000 = 1）
- 费率使用8位精度（1000000 = 0.01%）

### 4. 性能优化
- 批量订单处理使用并行
- 考核指标计算使用批处理
- 数据库索引优化

## 📝 待办事项

### P0 - 核心功能
- [ ] 接入OMS批量下单接口
- [ ] 接入OMS批量撤单接口
- [ ] 完善考核指标计算逻辑
- [ ] 实现订单修改功能

### P1 - 增强功能
- [ ] 做市商争议处理流程
- [ ] 做市商激励计划
- [ ] 做市商退出机制
- [ ] API Key生命周期管理

### P2 - 优化功能
- [ ] 报价质量实时监控
- [ ] 异常行为检测
- [ ] 性能优化（缓存、批处理）
- [ ] 监控告警完善

## 📞 联系方式

如有问题，请联系开发团队。
