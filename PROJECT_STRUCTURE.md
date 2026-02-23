# 项目结构说明

本文档说明合约交易系统的模块划分和职责。

## 模块列表

### 1. common-core
**基础核心模块**

包含：
- `Money`: 金额精度处理（8位小数）
- `IdGenerator`: 雪花算法ID生成器
- `TimeUtils`: 时间工具类
- 枚举类型：Side, OrderType, OrderStatus, LedgerDirection, BizType

无依赖，被所有其他模块引用。

### 2. common-proto
**数据传输对象模块**

包含：
- Request: CreateOrderRequest, CancelOrderRequest
- Response: CreateOrderResponse
- DTO: OrderDTO, AccountSnapshot, PositionSnapshot
- Event: OrderCommand, TradeEvent

依赖：common-core

### 3. api-gateway
**API网关**

职责：
- 统一外部入口
- 认证鉴权（JWT / API Key）
- 限流熔断
- 请求路由到内部服务

端口：8080

依赖：common-core, common-proto

调用：oms-core

### 4. oms-core
**订单管理系统**

职责：
- 订单生命周期管理
- 订单状态机
- 订单持久化
- 不扣钱、不撮合

端口：8081

依赖：common-core, common-proto, MySQL

调用：hard-risk-core, match-engine-core

### 5. hard-risk-core
**硬风控**

职责：
- 下单前同步风控检查
- 余额充足性验证
- 杠杆倍数校验
- 价格偏离保护
- 用户黑名单

端口：8082

依赖：common-core, common-proto, Redis

### 6. match-engine-core
**撮合引擎**

职责：
- 高性能订单撮合
- Disruptor RingBuffer
- 内存 OrderBook
- WAL 持久化

端口：8083

依赖：common-core, common-proto, Disruptor

输出：TradeEvent

### 7. ledger-core
**账本服务**

职责：
- 双录分录记账
- 资金冻结/解冻
- 成交结算
- 手续费记账

端口：8084

依赖：common-core, common-proto, MySQL

### 8. snapshot-core
**快照服务**

职责：
- 账户余额快照（Available/Frozen）
- 持仓快照
- 浮盈浮亏计算
- 强平价格计算

端口：8085

依赖：common-core, common-proto, Redis

### 9. replay-core
**重放服务**

职责：
- WAL 日志重放
- 灾备恢复
- 快照重建
- 对账审计

端口：8086

依赖：common-core, common-proto

## 服务调用关系

```
Client
  ↓
API Gateway (8080)
  ↓
OMS (8081)
  ↓ (风控检查)
Hard Risk (8082)
  ↓ (通过)
OMS (8081)
  ↓ (提交订单)
Match Engine (8083)
  ↓ (产生成交)
TradeEvent
  ↓
Ledger (8084) 结算
  ↓
Snapshot (8085) 更新快照
```

## 数据流转

### 下单流程
1. Client → API Gateway: HTTP请求
2. API Gateway → OMS: Feign调用
3. OMS → Hard Risk: 风控检查
4. OMS → Match Engine: 提交订单
5. Match Engine: 内存撮合
6. Match Engine → WAL: 写日志

### 成交流程
1. Match Engine: 产生TradeEvent
2. TradeEvent → Ledger: 结算分录
3. Ledger → DB: 持久化账本
4. Ledger → WAL: 写日志
5. TradeEvent → Snapshot: 更新快照

## 端口分配

| 服务 | 端口 | 说明 |
|------|------|------|
| api-gateway | 8080 | 对外接口 |
| oms-core | 8081 | 订单管理 |
| hard-risk-core | 8082 | 风控 |
| match-engine-core | 8083 | 撮合 |
| ledger-core | 8084 | 账本 |
| snapshot-core | 8085 | 快照 |
| replay-core | 8086 | 重放 |

## 数据库分配

| 服务 | 数据库 | 表 |
|------|--------|-----|
| oms-core | exchange_oms | t_order, t_order_event, t_trade |
| ledger-core | exchange_ledger | t_ledger_entry, t_account_balance, t_fee_record, t_position |

## Redis 使用

| 服务 | Database | 用途 |
|------|----------|------|
| api-gateway | 0 | 限流、熔断 |
| hard-risk-core | 0 | 黑名单、交易对暂停 |
| snapshot-core | 1 | 账户快照、持仓快照 |

## 日志文件

```
data/
└── wal/
    ├── match.log    # 撮合日志（OrderCommand + TradeEvent）
    └── ledger.log   # 账本日志（LedgerEntry）
```

## 启动顺序

建议按以下顺序启动服务：

1. 基础设施：MySQL, Redis, Nacos
2. 撮合引擎：match-engine-core (8083)
3. 账本服务：ledger-core (8084)
4. 快照服务：snapshot-core (8085)
5. 风控服务：hard-risk-core (8082)
6. 订单管理：oms-core (8081)
7. API网关：api-gateway (8080)
8. 重放服务：replay-core (8086) - 按需启动

## 扩展点

### 性能优化
- OrderBook 使用数组实现
- Off-heap 内存
- 批量提交DB
- 异步事件处理

### 功能扩展
- WebSocket 推送
- K线服务
- 强平引擎
- 市场数据服务
- 用户服务
- 钱包服务

