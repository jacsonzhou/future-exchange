OMS（Order Management System）
交易所级接口 + 数据模型 + 事件协议 + 灾备规范
本文档定义交易所核心 OMS（订单管理系统）的：
接口契约
状态机
数据库表结构
与撮合引擎的事件协议
幂等与灾备 Replay 机制
用于：Cursor 自动生成代码 + CTO 级系统设计对外说明

1. 设计原则（Design Principles）
1.1 系统职责边界

OMS 只负责：

订单生命周期状态机

订单幂等

订单事件编排

订单真相源（Source of Truth）

OMS 不负责：

❌ 不冻结资金

❌ 不扣钱

❌ 不做撮合

❌ 不维护订单簿

1.2 架构定位

OMS 是：

Order State Source of Truth

Order Event Producer

Order State Machine

所有下游系统（撮合 / 清算 / 风控）
以 OMS 状态 + Event 为准。

2. 统一字段定义
2.1 公共 Header
X-Trace-Id
X-Request-Id
X-User-Id
X-Idempotency-Key   (Submit / Cancel 必填)

2.2 公共枚举（Proto / Java Enum）
enum OrderSide {
  BUY = 0;
  SELL = 1;
}

enum OrderType {
  LIMIT = 0;
  MARKET = 1;
}

enum OrderStatus {
  NEW = 0;
  PENDING_RISK = 1;
  FROZEN = 2;
  PARTIALLY_FILLED = 3;
  FILLED = 4;
  CANCELED = 5;
  REJECTED = 6;
}

3. OMS 接口契约（REST + gRPC）
3.1 Submit Order（下单）
REST
POST /api/v1/oms/order/submit

Request
{
  "clientOrderId": "C123456789",
  "symbol": "BTC-USDT",
  "side": "BUY",
  "type": "LIMIT",
  "price": "43000.5",
  "quantity": "0.1",
  "timeInForce": "GTC"
}

Response
{
  "orderId": "O987654321",
  "status": "PENDING_RISK",
  "clientOrderId": "C123456789"
}

Protobuf
message SubmitOrderRequest {
  string traceId = 1;
  string requestId = 2;
  int64 userId = 3;

  string clientOrderId = 4;
  string symbol = 5;
  OrderSide side = 6;
  OrderType type = 7;

  string price = 8;
  string quantity = 9;
  string timeInForce = 10;
}

message SubmitOrderResponse {
  string orderId = 1;
  OrderStatus status = 2;
  string clientOrderId = 3;
}

3.2 幂等规则（Submit）
幂等 Key
(userId + clientOrderId)

场景	行为
首次请求	创建订单
重复请求	返回原 orderId
参数不一致	返回错误
3.3 Cancel Order（撤单）
REST
POST /api/v1/oms/order/cancel

Request
{
  "orderId": "O987654321",
  "clientOrderId": "C123456789"
}

Response
{
  "orderId": "O987654321",
  "status": "CANCELED"
}

Protobuf
message CancelOrderRequest {
  string traceId = 1;
  string requestId = 2;
  int64 userId = 3;

  string orderId = 4;
  string clientOrderId = 5;
}

message CancelOrderResponse {
  string orderId = 1;
  OrderStatus status = 2;
}

3.4 Cancel 幂等规则
幂等 Key
(userId + orderId)

当前状态	结果
NEW / FROZEN	CANCELED
FILLED	返回已成交
CANCELED	返回已撤
3.5 Query Order（查询）
REST
GET /api/v1/oms/order/query?orderId=xxx

Protobuf
message QueryOrderRequest {
  int64 userId = 1;
  string orderId = 2;
}

message QueryOrderResponse {
  string orderId = 1;
  string clientOrderId = 2;
  string symbol = 3;
  OrderSide side = 4;
  OrderType type = 5;
  string price = 6;
  string quantity = 7;
  string filledQuantity = 8;
  OrderStatus status = 9;
  int64 createTime = 10;
}

4. OMS 内部状态机（核心）
NEW
  ↓
PENDING_RISK
  ↓
FROZEN   (Account 冻结成功)
  ↓
PARTIALLY_FILLED
  ↓
FILLED

NEW / FROZEN
  ↓
CANCELED

ANY
  ↓
REJECTED

5. OMS 内部编排流程
Submit Order 编排
1. 参数校验
2. 幂等检查
3. 创建订单（NEW）
4. 调用 Hard Risk Gate
5. 调用 Account Service 冻结
6. 更新状态 = FROZEN
7. 投递 OrderEvent -> Match Engine

6. 错误码规范
Code	含义
OMS_1001	重复 clientOrderId
OMS_2001	订单不存在
OMS_2002	订单状态不允许撤单
OMS_3001	风控拒绝
OMS_4001	冻结失败
OMS_9001	系统异常
7. OMS 数据库表结构（SQL 可直接执行）
7.1 oms_order（订单当前态）
CREATE TABLE oms_order (
  id BIGINT PRIMARY KEY COMMENT '订单ID（雪花）',

  user_id BIGINT NOT NULL,
  client_order_id VARCHAR(64) NOT NULL,

  symbol VARCHAR(32) NOT NULL,
  side TINYINT NOT NULL COMMENT '0=BUY,1=SELL',
  type TINYINT NOT NULL COMMENT '0=LIMIT,1=MARKET',

  price DECIMAL(32,16) NULL,
  quantity DECIMAL(32,16) NOT NULL,
  filled_quantity DECIMAL(32,16) NOT NULL DEFAULT 0,

  status TINYINT NOT NULL,
  time_in_force VARCHAR(16) NOT NULL,

  risk_check_status TINYINT NOT NULL DEFAULT 0,
  freeze_status TINYINT NOT NULL DEFAULT 0,

  version INT NOT NULL DEFAULT 0,

  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,

  UNIQUE KEY uk_user_client (user_id, client_order_id),
  KEY idx_user_symbol (user_id, symbol),
  KEY idx_status (status),
  KEY idx_created_at (created_at)
);

7.2 oms_order_event（事件流）
CREATE TABLE oms_order_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,

  order_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,

  event_type VARCHAR(32) NOT NULL,
  event_source VARCHAR(32) NOT NULL,

  event_payload JSON NOT NULL,
  created_at BIGINT NOT NULL,

  KEY idx_order_id (order_id),
  KEY idx_symbol (symbol),
  KEY idx_created_at (created_at)
);

7.3 oms_order_state_log（审计链）
CREATE TABLE oms_order_state_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,

  order_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,

  from_status TINYINT NOT NULL,
  to_status TINYINT NOT NULL,

  reason_code VARCHAR(32) NOT NULL,
  reason_msg VARCHAR(255),

  operator VARCHAR(32) NOT NULL,
  trace_id VARCHAR(64),

  created_at BIGINT NOT NULL,

  KEY idx_order_id (order_id),
  KEY idx_user_id (user_id),
  KEY idx_created_at (created_at)
);

7.4 oms_idempotent_key（幂等）
CREATE TABLE oms_idempotent_key (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,

  user_id BIGINT NOT NULL,
  idem_key VARCHAR(64) NOT NULL,
  order_id BIGINT NOT NULL,
  request_hash VARCHAR(64) NOT NULL,

  created_at BIGINT NOT NULL,

  UNIQUE KEY uk_user_key (user_id, idem_key)
);

8. OMS ↔ Match Engine 事件协议
8.1 OrderEvent（OMS → Match）
enum OrderEventType {
  ORDER_SUBMIT = 0;
  ORDER_CANCEL = 1;
  ORDER_REPLACE = 2;
  ORDER_FORCE_CANCEL = 3;
}

message OrderEvent {
  string eventId = 1;
  int64 sequence = 2;
  string symbol = 3;

  OrderEventType type = 4;

  int64 orderId = 5;
  int64 userId = 6;

  OrderSide side = 7;
  OrderType orderType = 8;

  string price = 9;
  string quantity = 10;

  int64 eventTime = 11;
  map<string,string> ext = 12;
}

8.2 TradeEvent（Match → 全系统）
message TradeEvent {
  string tradeId = 1;
  int64 matchSequence = 2;

  string symbol = 3;

  int64 makerOrderId = 4;
  int64 takerOrderId = 5;

  int64 makerUserId = 6;
  int64 takerUserId = 7;

  string price = 8;
  string quantity = 9;

  string makerFee = 10;
  string takerFee = 11;

  bool isMakerBuy = 12;
  int64 tradeTime = 13;

  map<string,string> ext = 14;
}

8.3 OrderStateEvent（Match → OMS）
enum MatchOrderStatus {
  MATCH_PARTIAL = 0;
  MATCH_FILLED = 1;
  MATCH_CANCELED = 2;
}

message OrderStateEvent {
  string eventId = 1;
  string symbol = 2;

  int64 orderId = 3;
  int64 filledQuantityDelta = 4;

  MatchOrderStatus status = 5;
  int64 matchSequence = 6;
  int64 eventTime = 7;
}

9. 灾备 Replay 设计（交易所灵魂）
9.1 OMS Replay
SELECT * FROM oms_order_event
WHERE symbol = 'BTC-USDT'
ORDER BY id ASC;
用途：
重建订单状态
重放给撮合
对账恢复
跨 Region 灾备