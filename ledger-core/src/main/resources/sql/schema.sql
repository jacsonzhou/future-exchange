-- =====================================================
-- Ledger Core 数据库表结构
-- 数据库: exchange_ledger
-- 职责: 双录分录记账、资金结算、权威账本
-- =====================================================

CREATE DATABASE IF NOT EXISTS exchange_ledger 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE exchange_ledger;

-- =====================================================
-- 1. 全局序列号表（单库，中心化）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_global_sequence (
    seq_name VARCHAR(64) PRIMARY KEY COMMENT '序列名称',
    current_value BIGINT NOT NULL DEFAULT 0 COMMENT '当前值',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    INDEX idx_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局序列号表（中心化）';

-- 初始化序列
INSERT INTO t_global_sequence (seq_name, current_value, updated_at) 
VALUES ('ledger_biz_seq', 0, UNIX_TIMESTAMP() * 1000)
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);

-- =====================================================
-- 2. 账户主表（分库：user_id % 128）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_ledger_account (
    account_id BIGINT PRIMARY KEY COMMENT '账户ID（全局唯一）',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    account_type TINYINT NOT NULL COMMENT '账户类型: 1=可用 2=冻结 3=持仓保证金 11=手续费 12=保险基金 13=系统盈亏 14=Funding池 15=强平清算',
    currency VARCHAR(16) NOT NULL DEFAULT 'USDT' COMMENT '币种',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    UNIQUE KEY uk_user_type_currency (user_id, account_type, currency),
    INDEX idx_user (user_id),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户主表（分库）';

-- =====================================================
-- 3. Ledger Entry 双录分录表（分库+分表：按月 ledger_entry_YYYYMM）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_ledger_entry (
    entry_id BIGINT PRIMARY KEY COMMENT '分录ID（全局唯一）',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    account_type TINYINT NOT NULL COMMENT '账户类型',
    currency VARCHAR(16) NOT NULL DEFAULT 'USDT' COMMENT '币种',
    
    -- 双录字段
    debit DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '借方（资产增加/负债减少）',
    credit DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '贷方（资产减少/负债增加）',
    
    -- 余额快照
    balance_before DECIMAL(32,16) NOT NULL COMMENT '分录前余额',
    balance_after DECIMAL(32,16) NOT NULL COMMENT '分录后余额',
    
    -- 业务关联
    business_type VARCHAR(32) NOT NULL COMMENT '业务类型: TRADE/FEE/FUNDING/LIQUIDATION/DEPOSIT/WITHDRAW/ADJUST/TRANSFER',
    ref_trade_id VARCHAR(64) COMMENT '关联成交ID',
    ref_order_id BIGINT COMMENT '关联订单ID',
    ref_event_id VARCHAR(64) COMMENT '关联事件ID',
    
    -- 成对分录（双录必成对）
    pair_entry_id BIGINT COMMENT '成对分录ID',
    
    -- 全局序列号（Replay核心）
    biz_seq BIGINT NOT NULL COMMENT '全局业务序列号（Replay排序）',
    
    -- 幂等性保证
    idempotent_key VARCHAR(128) NOT NULL COMMENT '幂等键（业务唯一）',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    
    UNIQUE KEY uk_idempotent (idempotent_key),
    INDEX idx_user_time (user_id, created_at),
    INDEX idx_biz_seq (biz_seq),
    INDEX idx_ref_trade (ref_trade_id),
    INDEX idx_ref_order (ref_order_id),
    INDEX idx_pair (pair_entry_id),
    INDEX idx_business_type (business_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='双录分录表';

-- 创建按月分表示例（实际生产需要动态创建）
-- CREATE TABLE t_ledger_entry_202601 LIKE t_ledger_entry;
-- CREATE TABLE t_ledger_entry_202602 LIKE t_ledger_entry;

-- =====================================================
-- 4. 账户快照表（供API/风控查询）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_account_balance (
    user_id BIGINT PRIMARY KEY COMMENT '用户ID',
    currency VARCHAR(16) NOT NULL DEFAULT 'USDT' COMMENT '币种',
    
    -- 余额字段
    available DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '可用余额',
    frozen DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '冻结余额（下单占用）',
    position_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '持仓占用保证金',
    
    -- 盈亏
    unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '未实现盈亏（浮动盈亏）',
    realized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '已实现盈亏',
    
    -- 权益
    equity DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '权益 = available + position_margin + unrealized_pnl',
    
    -- 同步位点
    last_ledger_seq BIGINT NOT NULL DEFAULT 0 COMMENT '最后同步的biz_seq',
    last_ledger_entry_id BIGINT COMMENT '最后同步的entry_id',
    
    -- 校验
    checksum VARCHAR(64) COMMENT 'MD5(available+frozen+position_margin+unrealized_pnl)',
    
    -- 并发控制
    version INT NOT NULL DEFAULT 0 COMMENT '版本号（乐观锁）',
    
    -- 时间戳
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    INDEX idx_updated (updated_at),
    INDEX idx_last_seq (last_ledger_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户余额快照表';

-- =====================================================
-- 5. 持仓快照表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_position (
    id BIGINT PRIMARY KEY COMMENT '持仓ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 持仓方向
    side TINYINT NOT NULL COMMENT '方向: 1=LONG 2=SHORT',
    
    -- 持仓数量
    quantity DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '持仓数量',
    available_qty DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '可平仓数量',
    
    -- 价格信息
    entry_price DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '开仓均价',
    mark_price DECIMAL(32,16) COMMENT '标记价格',
    liquidation_price DECIMAL(32,16) COMMENT '强平价',
    bankruptcy_price DECIMAL(32,16) COMMENT '破产价格',
    
    -- 盈亏
    unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '未实现盈亏',
    realized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '已实现盈亏',
    
    -- 保证金
    position_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '持仓保证金',
    maintenance_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '维持保证金',
    leverage INT NOT NULL DEFAULT 1 COMMENT '杠杆倍数',
    margin_mode VARCHAR(16) NOT NULL DEFAULT 'CROSS' COMMENT '保证金模式: CROSS/ISOLATED',
    
    -- 同步位点
    last_trade_id VARCHAR(64) COMMENT '最后处理的tradeId',
    last_biz_seq BIGINT NOT NULL DEFAULT 0 COMMENT '最后同步的bizSeq',
    
    -- 并发控制
    version INT NOT NULL DEFAULT 0 COMMENT '版本号（乐观锁）',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    UNIQUE KEY uk_user_symbol (user_id, symbol),
    INDEX idx_last_seq (last_biz_seq),
    INDEX idx_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓表';

-- =====================================================
-- 6. 手续费记录表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_fee_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '记录ID',
    
    -- 关联信息
    trade_id VARCHAR(64) NOT NULL COMMENT '成交ID',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 费用信息
    fee_type VARCHAR(16) NOT NULL COMMENT '费用类型: TRADING_FEE/FUNDING_FEE/LIQUIDATION_FEE',
    fee_amount DECIMAL(32,16) NOT NULL COMMENT '手续费金额',
    fee_currency VARCHAR(16) NOT NULL COMMENT '手续费币种',
    fee_rate DECIMAL(16,8) COMMENT '费率',
    
    -- 来源
    maker_taker VARCHAR(8) COMMENT 'Maker/Taker',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    
    INDEX idx_user (user_id),
    INDEX idx_trade (trade_id),
    INDEX idx_order (order_id),
    INDEX idx_symbol (symbol),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手续费记录表';

-- =====================================================
-- 7. 对账记录表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_reconciliation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    check_date DATE NOT NULL COMMENT '对账日期',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    currency VARCHAR(16) NOT NULL COMMENT '币种',
    
    -- 对账数据
    ledger_balance DECIMAL(32,16) NOT NULL COMMENT 'LedgerEntry计算余额',
    snapshot_balance DECIMAL(32,16) NOT NULL COMMENT 'AccountSnapshot余额',
    diff_amount DECIMAL(32,16) NOT NULL COMMENT '差异金额',
    
    -- 对账状态
    status TINYINT NOT NULL COMMENT '状态: 0=一致 1=差异 2=已修复',
    
    -- 检查范围
    check_start_seq BIGINT NOT NULL COMMENT '检查起始biz_seq',
    check_end_seq BIGINT NOT NULL COMMENT '检查结束biz_seq',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    
    INDEX idx_date (check_date),
    INDEX idx_user (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账记录表';

-- =====================================================
-- 8. Replay重放记录表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_replay_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    
    replay_type VARCHAR(32) NOT NULL COMMENT '类型: FULL=全量 INCREMENTAL=增量',
    start_biz_seq BIGINT NOT NULL COMMENT '起始biz_seq',
    end_biz_seq BIGINT NOT NULL COMMENT '结束biz_seq',
    
    -- 统计
    affected_users INT NOT NULL DEFAULT 0 COMMENT '影响用户数',
    processed_entries INT NOT NULL DEFAULT 0 COMMENT '处理分录数',
    
    -- 状态
    status TINYINT NOT NULL COMMENT '状态: 0=进行中 1=成功 2=失败',
    error_msg TEXT COMMENT '错误信息',
    
    -- 时间
    start_time BIGINT NOT NULL COMMENT '开始时间（毫秒）',
    end_time BIGINT COMMENT '结束时间（毫秒）',
    duration_ms BIGINT COMMENT '耗时（毫秒）',
    
    -- 操作人
    operator VARCHAR(64) COMMENT '操作人',
    
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    
    INDEX idx_status (status),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Replay重放记录表';

-- =====================================================
-- 9. 系统账户预置数据
-- =====================================================
INSERT INTO t_ledger_account (account_id, user_id, account_type, currency, created_at, updated_at) VALUES
(9900000000000001, 0, 11, 'USDT', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000), -- 手续费账户
(9900000000000002, 0, 12, 'USDT', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000), -- 保险基金账户
(9900000000000003, 0, 13, 'USDT', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000), -- 系统盈亏账户
(9900000000000004, 0, 14, 'USDT', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000), -- Funding资金池
(9900000000000005, 0, 15, 'USDT', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000)  -- 强平清算账户
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);
