Clearing Core（Ledger / 双录账本）交易所级完整技术规范 v2
适用对象：合约交易所 / 永续合约 / 杠杆系统
级别：CTO / 核心清结算架构
目标：可 Replay、可对账、可审计、支持强平、支持 Funding、支持穿仓兜底、支持硬风控
1. 总体定位（Source of Truth）
Ledger 是交易所资金与资产的唯一真相源（Single Source of Truth）。
所有资金变化必须由 TradeEvent / FundingEvent / LiquidationEvent / TransferEvent 驱动写入 Ledger。
Snapshot（AccountSnapshot / PositionSnapshot）只是性能视图，不是真相。
任何时刻可以通过 Replay LedgerEntry 重建 Snapshot。

2. 账户体系（Account Model）
2.1 账户类型（交易所级）
account_type	说明
USER_MARGIN	用户保证金账户
USER_POSITION	用户持仓虚拟账户
EXCHANGE_FEE	手续费收入账户
INSURANCE_FUND	风险准备金（保险基金）
SOCIALIZED_LOSS	穿仓分摊账户
FUNDING_POOL	Funding 资金池
LIQUIDATION_CLEAR	强平中间清算账户
SYSTEM_ADJUST	系统调账账户
3. 合约保证金模型（硬风控核心）
3.1 保证金类型

Initial Margin (IM) 初始保证金

Maintenance Margin (MM) 维持保证金

Available Margin 可用保证金

Margin Ratio = (Equity / Maintenance Margin)

3.2 风控读取字段（来自 Snapshot）
Equity = WalletBalance + UnrealizedPnL
MarginRatio = Equity / MaintenanceMargin
Hard Risk Gate 只读 Snapshot，不读 Ledger 明细。
4. Ledger Entry 标准模型
class LedgerEntry {
  String entryId;
  long accountId;
  String asset;
  BigDecimal debit;
  BigDecimal credit;
  String businessType;
  String refTradeId;
  String refEventId;
  long ts;
}

5. SQL 核心表（生产级）
5.1 ledger_account（账户主表）
CREATE TABLE ledger_account (
  account_id BIGINT PRIMARY KEY,
  user_id BIGINT,
  account_type VARCHAR(32) NOT NULL,
  asset VARCHAR(16) NOT NULL,
  created_at BIGINT NOT NULL,
  UNIQUE KEY uk_user_type_asset (user_id, account_type, asset)
);

5.2 ledger_entry（双录分录）
CREATE TABLE ledger_entry (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  entry_id VARCHAR(64) NOT NULL,
  account_id BIGINT NOT NULL,
  asset VARCHAR(16) NOT NULL,
  debit DECIMAL(32,16) NOT NULL,
  credit DECIMAL(32,16) NOT NULL,
  business_type VARCHAR(32) NOT NULL,
  ref_trade_id VARCHAR(64),
  ref_event_id VARCHAR(64),
  created_at BIGINT NOT NULL,
  UNIQUE KEY uk_entry (entry_id),
  KEY idx_account_asset (account_id, asset),
  KEY idx_ref_trade (ref_trade_id)
);

5.3 ledger_balance（当前余额快照）
CREATE TABLE ledger_balance (
  account_id BIGINT NOT NULL,
  asset VARCHAR(16) NOT NULL,
  balance DECIMAL(32,16) NOT NULL,
  available DECIMAL(32,16) NOT NULL,
  frozen DECIMAL(32,16) NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (account_id, asset)
);

6. 成交 Trade → Ledger 双录（核心）
6.1 成交本身（名义资金流）
示例：买入 0.1 BTC @ 43000
买方（USER_MARGIN）
account	asset	debit	credit
buyer_margin	USDT	4300	0
卖方（USER_MARGIN）
account	asset	debit	credit
seller_margin	USDT	0	4300
7. 初始保证金冻结（IM Freeze）
7.1 冻结保证金（下单时）

业务类型：MARGIN_FREEZE

account	asset	debit	credit
user_margin	USDT	0	IM
user_margin_frozen	USDT	IM	0

（实现上：available -> frozen）

8. 维持保证金与浮盈浮亏（PnL）
8.1 未实现盈亏不直接入 Ledger

PnL 只进入 PositionSnapshot，用于风控和显示。
只有在平仓 / 强平时，Realized PnL 才入 Ledger。

9. 强平（Liquidation）账本分录（极重要）
9.1 强平成交 Realized PnL

假设：
用户保证金不足，强平成交亏损 200 USDT

用户
account	asset	debit	credit
user_margin	USDT	200	0
对手 / 市场
account	asset	debit	credit
liquidation_clear	USDT	0	200
9.2 强平手续费
account	asset	debit	credit
user_margin	USDT	fee	0
exchange_fee	USDT	0	fee
10. 穿仓（负余额）与保险基金（保险兜底）
10.1 穿仓发生（用户亏损 > 保证金）

示例：用户亏损 6000，保证金只有 5000，穿仓 1000

用户账户清空
account	asset	debit	credit
user_margin	USDT	5000	0
穿仓转入保险基金
account	asset	debit	credit
insurance_fund	USDT	1000	0
liquidation_clear	USDT	0	1000
10.2 保险基金不足 → 社会化亏损
保险基金用尽
account	asset	debit	credit
insurance_fund	USDT	all	0
剩余穿仓进入 SOCIALIZED_LOSS
account	asset	debit	credit
socialized_loss	USDT	remain	0

（后续通过 ADL / 社会化分摊处理）

11. Funding Fee 分录（永续灵魂）
11.1 Funding 正向（多付空）

多头支付 50 USDT 给空头

多头
account	asset	debit	credit
long_user_margin	USDT	50	0
空头
account	asset	debit	credit
short_user_margin	USDT	0	50
11.2 Funding 通过 Funding Pool

可选实现：

account	asset	debit	credit
long_user	USDT	50	0
funding_pool	USDT	0	50
funding_pool	USDT	50	0
short_user	USDT	0	50
12. Replay & 灾备（交易所灵魂）
12.1 Replay 方式
SELECT * FROM ledger_entry ORDER BY id ASC;


按顺序：

重建 ledger_balance

重建 AccountSnapshot

重建 PositionSnapshot

12.2 全系统平账校验
SUM(debit) == SUM(credit)

13. 幂等与一致性（生产级）
13.1 幂等 Key
事件	幂等键
Trade	tradeId
Liquidation	liquidationId
Funding	fundingEventId
Transfer	transferId
13.2 applyTrade 原子性
DB 事务：
校验幂等
insert ledger_entry (多条)

update ledger_balance

publish LedgerEntryMsg

14. Snapshot 依赖（风控读取）

Hard Risk Gate 只读：

AccountSnapshot.available

AccountSnapshot.equity

PositionSnapshot.marginRatio

PositionSnapshot.liquidationPrice

15. Clearing Core 关键原则（面试王炸）

TradeEvent 是钱的唯一来源

Ledger 是资金唯一真相

Snapshot 是性能层

所有账可 Replay

所有账可对账

所有穿仓有去向

所有亏损有归属

所有钱能审计

16. Cursor 可直接拆任务

LedgerServiceImpl

TradeToLedgerConverter

LiquidationLedgerProcessor

FundingLedgerProcessor

InsuranceFundProcessor

SocializedLossProcessor

LedgerReplayJob

LedgerReconciliationJob

17. CTO级总结（你背这个）
我们采用双录账本作为资金唯一真相源，
通过 TradeEvent 驱动，
支持强平、Funding、穿仓与保险基金，
所有资金可 Replay、可对账、可审计，
Snapshot 仅作为性能层，
这是标准头部交易所清结算架构。