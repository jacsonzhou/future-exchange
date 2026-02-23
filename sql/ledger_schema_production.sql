-- ==========================================
-- Ledger Core 生产级表结构
-- 对标：Binance / OKX / Bybit
-- 特性：分库分表 / 双录账本 / 审计追溯 / 灾备重放
-- ==========================================

-- ==========================================
-- 1. 全局序列号表（单库，中心化）
-- 用途：生成全局唯一递增的biz_seq，用于Replay排序
-- ==========================================
CREATE TABLE ledger_global_sequence (
  seq_name VARCHAR(64) PRIMARY KEY,
  current_value BIGINT NOT NULL DEFAULT 0,
  updated_at BIGINT NOT NULL,
  INDEX idx_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局序列号表（中心化）';

INSERT INTO ledger_global_sequence (seq_name, current_value, updated_at) 
VALUES ('ledger_biz_seq', 0, UNIX_TIMESTAMP(NOW()) * 1000);

-- ==========================================
-- 2. 账户主表（分库：user_id % 128）
-- 用途：记录用户各类账户的元数据
-- ==========================================
CREATE TABLE ledger_account (
  account_id BIGINT PRIMARY KEY COMMENT '账户ID（全局唯一）',
  user_id BIGINT NOT NULL COMMENT '用户ID',
  account_type TINYINT NOT NULL COMMENT '账户类型：1=可用,2=冻结,3=持仓保证金,11=手续费,12=保险基金',
  currency VARCHAR(16) NOT NULL DEFAULT 'USDT' COMMENT '币种',
  created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
  updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
  
  UNIQUE KEY uk_user_type_currency (user_id, account_type, currency),
  INDEX idx_user (user_id),
  INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户主表（分库）';

-- ==========================================
-- 3. Ledger Entry 双录分录表（分库+分表）
-- 分库：user_id % 128
-- 分表：按月 ledger_entry_YYYYMM
-- 用途：记录所有资金变动明细（审计溯源）
-- ==========================================
CREATE TABLE ledger_entry_202601 (
  entry_id BIGINT PRIMARY KEY COMMENT '分录ID（全局唯一）',
  user_id BIGINT NOT NULL COMMENT '用户ID',
  account_type TINYINT NOT NULL COMMENT '账户类型',
  currency VARCHAR(16) NOT NULL DEFAULT 'USDT' COMMENT '币种',
  
  -- 🔥 双录字段
  debit DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '借方（资产增加/负债减少）',
  credit DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '贷方（资产减少/负债增加）',
  
  -- 🔥 余额快照（性能优化）
  balance_before DECIMAL(32,16) NOT NULL COMMENT '分录前余额',
  balance_after DECIMAL(32,16) NOT NULL COMMENT '分录后余额',
  
  -- 🔥 业务关联
  business_type VARCHAR(32) NOT NULL COMMENT 'TRADE/FEE/FUNDING/LIQUIDATION/DEPOSIT/WITHDRAW/ADJUST',
  ref_trade_id VARCHAR(64) COMMENT '关联成交ID',
  ref_order_id BIGINT COMMENT '关联订单ID',
  ref_event_id VARCHAR(64) COMMENT '关联事件ID',
  
  -- 🔥 成对分录（双录必成对）
  pair_entry_id BIGINT COMMENT '成对分录ID',
  
  -- 🔥 全局序列号（Replay核心）
  biz_seq BIGINT NOT NULL COMMENT '全局业务序列号（Replay排序）',
  
  -- 🔥 幂等性保证
  idempotent_key VARCHAR(128) NOT NULL COMMENT '幂等键（业务唯一）',
  
  created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
  
  UNIQUE KEY uk_entry (entry_id),
  UNIQUE KEY uk_idempotent (idempotent_key),
  INDEX idx_user_time (user_id, created_at),
  INDEX idx_biz_seq (biz_seq),
  INDEX idx_ref_trade (ref_trade_id),
  INDEX idx_pair (pair_entry_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='双录分录表-2026年1月（分库分表）';

-- 其他月份表（按需创建）
-- CREATE TABLE ledger_entry_202602 LIKE ledger_entry_202601;
-- CREATE TABLE ledger_entry_202603 LIKE ledger_entry_202601;
-- ...

-- ==========================================
-- 4. Account Snapshot 账户快照表（分库：user_id % 128）
-- 用途：实时账户余额（性能层，供API/风控查询）
-- ==========================================
CREATE TABLE account_snapshot (
  user_id BIGINT PRIMARY KEY COMMENT '用户ID',
  currency VARCHAR(16) NOT NULL DEFAULT 'USDT' COMMENT '币种',
  
  -- 🔥 账户余额
  available DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '可用余额',
  frozen DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '冻结余额（下单占用）',
  position_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '持仓占用保证金',
  
  -- 🔥 盈亏
  unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '未实现盈亏（浮动盈亏）',
  realized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '已实现盈亏',
  
  -- 🔥 权益（风控核心）
  equity DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '权益 = available + position_margin + unrealized_pnl',
  
  -- 🔥 同步位点（幂等/断点续传）
  last_ledger_seq BIGINT NOT NULL DEFAULT 0 COMMENT '最后同步的biz_seq',
  last_ledger_entry_id BIGINT COMMENT '最后同步的entry_id',
  
  -- 🔥 乐观锁（并发控制）
  version INT NOT NULL DEFAULT 0 COMMENT '版本号（乐观锁）',
  
  -- 🔥 校验哈希（数据完整性）
  checksum VARCHAR(64) COMMENT 'MD5(available+frozen+position_margin+unrealized_pnl)',
  
  updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
  
  INDEX idx_updated (updated_at),
  INDEX idx_last_seq (last_ledger_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户快照表（分库）';

-- ==========================================
-- 5. Reconciliation 对账记录表（单库）
-- 用途：记录每日对账结果
-- ==========================================
CREATE TABLE ledger_reconciliation_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  check_date DATE NOT NULL COMMENT '对账日期',
  user_id BIGINT NOT NULL COMMENT '用户ID',
  currency VARCHAR(16) NOT NULL COMMENT '币种',
  
  -- 🔥 对账数据
  ledger_balance DECIMAL(32,16) NOT NULL COMMENT 'LedgerEntry计算余额',
  snapshot_balance DECIMAL(32,16) NOT NULL COMMENT 'AccountSnapshot余额',
  diff_amount DECIMAL(32,16) NOT NULL COMMENT '差异金额',
  
  -- 🔥 对账状态
  status TINYINT NOT NULL COMMENT '状态：0=一致,1=差异,2=已修复',
  
  check_start_seq BIGINT NOT NULL COMMENT '检查起始biz_seq',
  check_end_seq BIGINT NOT NULL COMMENT '检查结束biz_seq',
  
  created_at BIGINT NOT NULL COMMENT '创建时间',
  
  INDEX idx_date (check_date),
  INDEX idx_user (user_id),
  INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账记录表';

-- ==========================================
-- 6. Replay 重放记录表（单库）
-- 用途：记录Replay操作历史
-- ==========================================
CREATE TABLE ledger_replay_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  replay_type VARCHAR(32) NOT NULL COMMENT 'FULL=全量重放,INCREMENTAL=增量重放',
  start_biz_seq BIGINT NOT NULL COMMENT '起始biz_seq',
  end_biz_seq BIGINT NOT NULL COMMENT '结束biz_seq',
  
  affected_users INT NOT NULL DEFAULT 0 COMMENT '影响用户数',
  processed_entries INT NOT NULL DEFAULT 0 COMMENT '处理分录数',
  
  status TINYINT NOT NULL COMMENT '状态：0=进行中,1=成功,2=失败',
  error_msg TEXT COMMENT '错误信息',
  
  start_time BIGINT NOT NULL COMMENT '开始时间',
  end_time BIGINT COMMENT '结束时间',
  duration_ms BIGINT COMMENT '耗时（毫秒）',
  
  operator VARCHAR(64) COMMENT '操作人',
  
  created_at BIGINT NOT NULL,
  
  INDEX idx_status (status),
  INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Replay重放记录表';

-- ==========================================
-- 7. 系统账户预置数据
-- ==========================================
INSERT INTO ledger_account (account_id, user_id, account_type, currency, created_at, updated_at) VALUES
(9900000000000001, 0, 11, 'USDT', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000), -- 交易所手续费账户
(9900000000000002, 0, 12, 'USDT', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000), -- 保险基金账户
(9900000000000003, 0, 13, 'USDT', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000), -- 系统盈亏账户
(9900000000000004, 0, 14, 'USDT', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000), -- Funding资金池
(9900000000000005, 0, 15, 'USDT', UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000); -- 强平清算账户

-- ==========================================
-- 性能优化建议
-- ==========================================

-- 1. 分区表（MySQL 8.0+）
-- ALTER TABLE ledger_entry_202601 PARTITION BY RANGE (biz_seq) (
--   PARTITION p0 VALUES LESS THAN (1000000),
--   PARTITION p1 VALUES LESS THAN (2000000),
--   PARTITION p2 VALUES LESS THAN MAXVALUE
-- );

-- 2. 冷热分离
-- 历史数据（>6个月）迁移到ClickHouse/HBase

-- 3. 读写分离
-- account_snapshot表走读库（从库）
-- ledger_entry表写主库

-- ==========================================
-- 索引优化说明
-- ==========================================

-- ledger_entry_YYYYMM表：
-- - uk_entry: 主键查询
-- - uk_idempotent: 幂等性保证（防重复）
-- - idx_user_time: 用户查询历史记录
-- - idx_biz_seq: Replay全局排序（核心）
-- - idx_ref_trade: 关联成交查询
-- - idx_pair: 成对分录查询

-- account_snapshot表：
-- - PRIMARY KEY: 用户余额查询（O(1)）
-- - idx_last_seq: 增量同步断点续传
-- - idx_updated: 定时任务扫描

-- ==========================================
-- 监控指标
-- ==========================================

-- 1. Ledger写入QPS
-- SELECT COUNT(*) FROM ledger_entry_202601 WHERE created_at > ?;

-- 2. AccountSnapshot同步延迟
-- SELECT AVG(last_ledger_seq) FROM account_snapshot;

-- 3. 对账差异数量
-- SELECT COUNT(*) FROM ledger_reconciliation_log WHERE status = 1;

-- 4. 借贷平衡检查
-- SELECT SUM(debit) - SUM(credit) AS balance_check 
-- FROM ledger_entry_202601;
-- 结果必须为0！

-- ==========================================
-- 完成！生产级Ledger表结构
-- ==========================================

