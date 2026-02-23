-- Account Snapshot Schema（账户快照表）

-- 账户快照表
CREATE TABLE IF NOT EXISTS account_snapshot (
  user_id BIGINT PRIMARY KEY COMMENT '用户ID',
  currency VARCHAR(16) NOT NULL COMMENT '币种',
  
  -- 余额字段
  available DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '可用余额',
  frozen DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '冻结余额',
  position_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '持仓占用保证金',
  unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '未实现盈亏',
  realized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '已实现盈亏',
  equity DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '权益',
  margin_ratio DECIMAL(10,4) COMMENT '保证金率',
  
  -- 同步位点
  last_biz_seq BIGINT NOT NULL DEFAULT 0 COMMENT '最后同步的bizSeq',
  last_entry_id BIGINT COMMENT '最后同步的entryId',
  last_trade_id VARCHAR(64) COMMENT '最后同步的tradeId',
  
  -- 并发控制
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  
  -- 时间戳
  created_at BIGINT NOT NULL COMMENT '创建时间',
  updated_at BIGINT NOT NULL COMMENT '更新时间',
  
  UNIQUE KEY uk_user_currency (user_id, currency),
  KEY idx_last_biz_seq (last_biz_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户快照表';

-- 初始化测试数据（可选）
INSERT INTO account_snapshot (
  user_id, currency, available, frozen, position_margin, 
  unrealized_pnl, realized_pnl, equity, last_biz_seq, 
  version, created_at, updated_at
) VALUES (
  1001, 'USDT', 100000.00, 0, 0, 0, 0, 100000.00, 0, 0,
  UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000
) ON DUPLICATE KEY UPDATE updated_at = UNIX_TIMESTAMP() * 1000;

INSERT INTO account_snapshot (
  user_id, currency, available, frozen, position_margin, 
  unrealized_pnl, realized_pnl, equity, last_biz_seq, 
  version, created_at, updated_at
) VALUES (
  1002, 'USDT', 50000.00, 0, 0, 0, 0, 50000.00, 0, 0,
  UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000
) ON DUPLICATE KEY UPDATE updated_at = UNIX_TIMESTAMP() * 1000;



