1️⃣ 用户库（exchange_user_db）
user_base（用户主表）
CREATE TABLE user_base (
  id        BIGINT PRIMARY KEY,
  email          VARCHAR(128) NOT NULL,
  status         VARCHAR(16) NOT NULL,
  kyc_level      INT NOT NULL,
  created_at     TIMESTAMP NOT NULL,
  updated_at     TIMESTAMP NOT NULL,
  UNIQUE KEY uk_email (email)
) ENGINE=InnoDB;

2️⃣ 币种 / 交易对（exchange_market_db）
currency（币种表）
CREATE TABLE currency (
  currency_code  VARCHAR(16) PRIMARY KEY,
  precision_scale INT NOT NULL,      -- 精度，如 8
  withdraw_fee   BIGINT NOT NULL,
  min_withdraw   BIGINT NOT NULL,
  status         VARCHAR(16),
  created_at     TIMESTAMP
) ENGINE=InnoDB;

symbol（交易对）
CREATE TABLE symbol (
  symbol_code    VARCHAR(32) PRIMARY KEY,
  base_currency  VARCHAR(16) NOT NULL,
  quote_currency VARCHAR(16) NOT NULL,
  tick_size      BIGINT NOT NULL,
  lot_size       BIGINT NOT NULL,
  status         VARCHAR(16),
  created_at     TIMESTAMP
) ENGINE=InnoDB;

3️⃣ 账户主库（exchange_account_db）

强一致资金

account_balance（账户余额）
CREATE TABLE account_balance (
  user_id    BIGINT NOT NULL,
  currency   VARCHAR(16) NOT NULL,
  available  BIGINT NOT NULL,
  frozen     BIGINT NOT NULL,
  version    BIGINT NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  PRIMARY KEY (user_id, currency)
) ENGINE=InnoDB;

account_freeze_log（冻结流水）
CREATE TABLE account_freeze_log (
  id         BIGINT PRIMARY KEY,
  user_id    BIGINT NOT NULL,
  currency   VARCHAR(16) NOT NULL,
  amount     BIGINT NOT NULL,
  biz_type   VARCHAR(32) NOT NULL,
  biz_id     BIGINT NOT NULL,
  status     VARCHAR(16) NOT NULL,
  created_at TIMESTAMP NOT NULL
) ENGINE=InnoDB;

4️⃣ 订单库（exchange_order_db）

高写入，可分表

order_info_00（示例分表）
CREATE TABLE order_info_00 (
  order_id    BIGINT PRIMARY KEY,
  user_id     BIGINT NOT NULL,
  symbol      VARCHAR(32) NOT NULL,
  side        VARCHAR(8) NOT NULL,
  type        VARCHAR(8) NOT NULL,
  price       BIGINT NOT NULL,
  quantity    BIGINT NOT NULL,
  filled_qty  BIGINT NOT NULL,
  status      VARCHAR(16) NOT NULL,
  created_at  TIMESTAMP NOT NULL,
  updated_at  TIMESTAMP NOT NULL,
  KEY idx_user (user_id),
  KEY idx_symbol (symbol)
) ENGINE=InnoDB;


分表规则：

order_info_{user_id % 64}

5️⃣ 持仓库（exchange_position_db）
position_current（当前持仓）
CREATE TABLE position_current (
  user_id    BIGINT NOT NULL,
  symbol     VARCHAR(32) NOT NULL,
  side       VARCHAR(8) NOT NULL,
  qty        BIGINT NOT NULL,
  avg_price  BIGINT NOT NULL,
  margin     BIGINT NOT NULL,
  unreal_pnl BIGINT NOT NULL,
  liq_price  BIGINT NOT NULL,
  version    BIGINT NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  PRIMARY KEY (user_id, symbol, side)
) ENGINE=InnoDB;

position_history（历史持仓）
CREATE TABLE position_history (
  id           BIGINT PRIMARY KEY,
  user_id      BIGINT NOT NULL,
  symbol       VARCHAR(32) NOT NULL,
  side         VARCHAR(8) NOT NULL,
  open_price   BIGINT NOT NULL,
  close_price  BIGINT NOT NULL,
  qty          BIGINT NOT NULL,
  realized_pnl BIGINT NOT NULL,
  closed_at    TIMESTAMP NOT NULL
) ENGINE=InnoDB;

6️⃣ Ledger 核心库（exchange_ledger_db）

交易所灵魂，Append-only

ledger_entry（双录分录表）
CREATE TABLE ledger_entry (
  entry_id     BIGINT PRIMARY KEY,
  user_id      BIGINT NOT NULL,
  currency     VARCHAR(16) NOT NULL,
  delta        BIGINT NOT NULL,
  direction    VARCHAR(8) NOT NULL,   -- DEBIT / CREDIT
  biz_type     VARCHAR(32) NOT NULL,  -- TRADE, FEE, LIQUIDATION
  biz_id       BIGINT NOT NULL,
  ref_entry_id BIGINT,
  created_at   TIMESTAMP NOT NULL,
  KEY idx_user (user_id),
  KEY idx_biz (biz_type, biz_id)
) ENGINE=InnoDB;

ledger_balance_snapshot（可选，对账快照）
CREATE TABLE ledger_balance_snapshot (
  user_id    BIGINT NOT NULL,
  currency   VARCHAR(16) NOT NULL,
  balance    BIGINT NOT NULL,
  snapshot_at TIMESTAMP NOT NULL,
  PRIMARY KEY (user_id, currency)
) ENGINE=InnoDB;

7️⃣ 成交与行情（exchange_market_db）
trade_tick（成交明细）
CREATE TABLE trade_tick (
  trade_id BIGINT PRIMARY KEY,
  symbol   VARCHAR(32) NOT NULL,
  price    BIGINT NOT NULL,
  qty      BIGINT NOT NULL,
  ts       BIGINT NOT NULL,
  KEY idx_symbol_ts (symbol, ts)
) ENGINE=InnoDB;

8️⃣ 风控库（exchange_risk_db）
user_risk_snapshot
CREATE TABLE user_risk_snapshot (
  user_id      BIGINT PRIMARY KEY,
  total_equity BIGINT NOT NULL,
  total_margin BIGINT NOT NULL,
  margin_ratio BIGINT NOT NULL,
  risk_level   VARCHAR(16) NOT NULL,
  updated_at   TIMESTAMP NOT NULL
) ENGINE=InnoDB;

9️⃣ WAL / 审计（exchange_audit_db）
order_wal
CREATE TABLE order_wal (
  wal_id     BIGINT PRIMARY KEY,
  order_id   BIGINT NOT NULL,
  payload    MEDIUMBLOB NOT NULL,
  created_at TIMESTAMP NOT NULL
) ENGINE=InnoDB;

match_wal
CREATE TABLE match_wal (
  wal_id     BIGINT PRIMARY KEY,
  payload    MEDIUMBLOB NOT NULL,
  created_at TIMESTAMP NOT NULL
) ENGINE=InnoDB;

关键设计说明（你面试可以直接说）
为什么全部用 BIGINT？

所有金额、数量、价格：
👉 统一 long（最小单位）

避免：

double 精度丢失

decimal 性能差

这是：
头部交易所标准做法

Ledger 双录真实示例（给你对账用）

成交 1 BTC @ 50,000 USDT：

-- 买方扣 USDT
INSERT INTO ledger_entry VALUES
(1001, 10001, 'USDT', -5000000000000, 'DEBIT', 'TRADE', 9001, 1002, NOW());

-- 卖方加 USDT
INSERT INTO ledger_entry VALUES
(1002, 10002, 'USDT',  5000000000000, 'CREDIT', 'TRADE', 9001, 1001, NOW());