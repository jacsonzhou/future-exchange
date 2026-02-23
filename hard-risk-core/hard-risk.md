Hard Risk Gate（同步硬风控）模块设计文档
角色定位：
Hard Risk Gate 是 OMS → Match Engine 之间的 最后同步风控防线，任何订单在进入撮合前，必须同步通过 Hard Risk Gate。
这是交易所级系统中：
最严格 最实时 最不能异步 最不能出错,的一道同步关卡。
1. 架构定位（CTO级）
1.1 在整体架构中的位置
Client
  |
API Gateway
  |
OMS  --(Submit Order)-->
  |
  |-- Sync Call -->
  |   Hard Risk Gate   ❗
  |-- PASS -->
  |
Account Freeze
  |
OrderEvent -> Match Engine
1.2 核心设计原则
原则	说明
同步	必须同步返回 PASS / REJECT
强一致	必须读取最新 Account + Position Snapshot
快	P99 < 5ms
无状态	本身不维护资金，仅做校验
幂等	同一 orderId 重试必须一致
最后防线	异步风控不可替代它
2. 核心职责（交易所级）
Hard Risk Gate 负责 实时阻断不可成交订单
必须校验的风险项 可用保证金校验 杠杆限制 最大仓位限制
ReduceOnly 规则
风控黑名单 / 白名单
价格有效性（防极端报价）
账户状态（冻结 / 强平中 / 风控锁定）
3. 强一致性依赖（面试重点）
Hard Risk Gate 必须同步读取
3.1 Account Snapshot（账户快照）
字段（示例）：
account_id user_id currency equity                // 净资产
balance               // 余额
available_margin      // 可用保证金
used_margin           // 已用保证金
margin_ratio          // 保证金率
account_status        // NORMAL / FROZEN / LIQUIDATING
updated_at
3.2 Position Snapshot（持仓快照）
position_id
user_id
symbol
side                  // LONG / SHORT
quantity
entry_price
mark_price
unrealized_pnl
used_margin
leverage
liquidation_price
position_status       // NORMAL / LIQUIDATING / CLOSED
updated_at

4. 同步接口定义（gRPC / Protobuf）
Hard Risk Gate 只提供同步 RPC
4.1 接口
rpc CheckOrderRisk(CheckOrderRiskRequest)
    returns (CheckOrderRiskResponse)
4.2 Request
message CheckOrderRiskRequest {
  string traceId = 1;
  string requestId = 2;
  int64 userId = 3;
  string symbol = 4;
  OrderSide side = 5;
  string price = 6;
  string quantity = 7;
  int32 leverage = 8;
  bool reduceOnly = 9;
  string orderId = 10;   // OMS生成，用于幂等
}
4.3 Response
enum RiskResult {
  PASS = 0;
  REJECT = 1;
}
enum RiskRejectReason {
  NONE = 0;
  INSUFFICIENT_MARGIN = 1;
  LEVERAGE_EXCEEDED = 2;
  POSITION_LIMIT_EXCEEDED = 3;
  REDUCE_ONLY_VIOLATION = 4;
  ACCOUNT_FROZEN = 5;
  ACCOUNT_LIQUIDATING = 6;
  PRICE_OUT_OF_RANGE = 7;
  RISK_BLACKLISTED = 8;
}
message CheckOrderRiskResponse {
  RiskResult result = 1;
  RiskRejectReason rejectReason = 2;
  string rejectMessage = 3;
  // 风控计算中间值（用于审计 / debug）
  string requiredMargin = 4;
  string availableMargin = 5;
  int64 riskCheckTime = 6;
}

5. 风控规则明细（核心算法级）
5.1 可用保证金校验（最核心）
所需保证金
notional = price * quantity

requiredMargin = notional / leverage

校验逻辑
if available_margin < requiredMargin:
    REJECT(INSUFFICIENT_MARGIN)

5.2 杠杆限制
maxLeverage = SymbolConfig.maxLeverage

if leverage > maxLeverage:
    REJECT(LEVERAGE_EXCEEDED)

5.3 最大仓位限制
新仓位计算
newPositionQty = currentPositionQty + orderQty (same direction)

校验
if newPositionQty > SymbolConfig.maxPositionQty:
    REJECT(POSITION_LIMIT_EXCEEDED)

5.4 ReduceOnly 校验（行业容易出事故）

规则：

ReduceOnly = true

只能减少已有仓位

不能开新方向

逻辑：

if reduceOnly:
  if no existing position:
     REJECT(REDUCE_ONLY_VIOLATION)

  if orderSide increases position:
     REJECT(REDUCE_ONLY_VIOLATION)

5.5 账户状态
if account_status == FROZEN:
   REJECT(ACCOUNT_FROZEN)

if account_status == LIQUIDATING:
   REJECT(ACCOUNT_LIQUIDATING)

5.6 价格有效性（防插针单 / 穿仓单）

基于 Mark Price：

maxDeviation = SymbolConfig.maxPriceDeviationPct

if abs(orderPrice - markPrice) / markPrice > maxDeviation:
   REJECT(PRICE_OUT_OF_RANGE)

5.7 黑白名单
if user in blacklist:
   REJECT(RISK_BLACKLISTED)

if user in whitelist:
   skip some rules (configurable)

6. SQL 表设计（给 Cursor 直接建）
6.1 账户快照（示例）
CREATE TABLE risk_account_snapshot (
  account_id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,

  equity DECIMAL(32,16) NOT NULL,
  balance DECIMAL(32,16) NOT NULL,
  available_margin DECIMAL(32,16) NOT NULL,
  used_margin DECIMAL(32,16) NOT NULL,

  margin_ratio DECIMAL(16,8) NOT NULL,

  account_status TINYINT NOT NULL COMMENT '0=NORMAL,1=FROZEN,2=LIQUIDATING',

  updated_at BIGINT NOT NULL,

  KEY idx_user_id (user_id)
) ENGINE=InnoDB COMMENT='Hard Risk账户快照';

6.2 持仓快照
CREATE TABLE risk_position_snapshot (
  position_id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,

  side TINYINT NOT NULL COMMENT '0=LONG,1=SHORT',
  quantity DECIMAL(32,16) NOT NULL,
  entry_price DECIMAL(32,16) NOT NULL,
  mark_price DECIMAL(32,16) NOT NULL,
  unrealized_pnl DECIMAL(32,16) NOT NULL,
  used_margin DECIMAL(32,16) NOT NULL,
  leverage INT NOT NULL,
  liquidation_price DECIMAL(32,16),
  position_status TINYINT NOT NULL COMMENT '0=NORMAL,1=LIQUIDATING,2=CLOSED',

  updated_at BIGINT NOT NULL,

  UNIQUE KEY uk_user_symbol (user_id, symbol)
) ENGINE=InnoDB COMMENT='Hard Risk持仓快照';

6.3 风控黑白名单
CREATE TABLE risk_user_list (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,

  list_type TINYINT NOT NULL COMMENT '0=BLACK,1=WHITE',

  reason VARCHAR(128),
  created_at BIGINT NOT NULL,

  UNIQUE KEY uk_user (user_id)
) ENGINE=InnoDB COMMENT='风控黑白名单';

7. 与 OMS 的同步链路（面试王炸）
Submit Order 时序
OMS
  |
  |--> CheckOrderRisk
  |      - read account snapshot
  |      - read position snapshot
  |      - calculate margin
  |      - validate rules
  |
  |<-- PASS / REJECT

OMS 行为：

风控结果	OMS 行为
PASS	继续冻结资金
REJECT	订单 REJECTED + reason
8. 幂等 & 重试（生产必备）
幂等 Key
(userId + orderId)
规则：
同 orderId 重试
必须返回同样结果
允许短期内缓存结果（Redis / Local Cache）
9. 性能与 SLA（CTO级）
指标	要求
P99 延迟	< 5ms
QPS	10k+
失败策略	Fail-Close（失败即拒单）
降级	禁止绕过 Hard Risk
10. 审计 & 风控日志（合规）
CREATE TABLE risk_check_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  result TINYINT NOT NULL COMMENT '0=PASS,1=REJECT',
  reject_reason TINYINT,
  required_margin DECIMAL(32,16),
  available_margin DECIMAL(32,16),
  created_at BIGINT NOT NULL,
  KEY idx_user (user_id),
  KEY idx_order (order_id)
) ENGINE=InnoDB COMMENT='Hard Risk风控审计日志';

11. Cursor 可直接拆任务（给 AI 用）
功能模块拆解
AccountSnapshotDAO
PositionSnapshotDAO
SymbolConfigService
RiskRuleEngine
CheckOrderRiskService
RiskCheckLogger