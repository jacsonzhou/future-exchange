-- ========================================================
-- ADL (Auto-Deleveraging) Core Database Schema
-- 自动减仓模块数据库表结构
-- ========================================================

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS exchange_adl 
DEFAULT CHARACTER SET utf8mb4 
DEFAULT COLLATE utf8mb4_unicode_ci;

USE exchange_adl;

-- ========================================================
-- 表1: adl_ranking_queue - ADL排名队列
-- ========================================================
DROP TABLE IF EXISTS adl_ranking_queue;

CREATE TABLE adl_ranking_queue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    
    -- 基本信息
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对（如BTCUSDT）',
    position_id BIGINT COMMENT '持仓ID',
    
    -- 持仓信息
    side VARCHAR(8) NOT NULL COMMENT '持仓方向：LONG/SHORT',
    position_size DECIMAL(32, 8) DEFAULT 0 COMMENT '持仓数量（绝对值）',
    entry_price DECIMAL(32, 8) COMMENT '持仓均价',
    mark_price DECIMAL(32, 8) COMMENT '标记价格',
    
    -- 保证金信息
    margin_balance DECIMAL(32, 8) COMMENT '保证金余额',
    maintenance_margin DECIMAL(32, 8) COMMENT '维持保证金',
    effective_leverage DECIMAL(10, 4) COMMENT '实际杠杆倍数',
    
    -- 盈亏信息
    unrealized_pnl DECIMAL(32, 8) DEFAULT 0 COMMENT '未实现盈亏',
    pnl_ratio DECIMAL(10, 8) DEFAULT 0 COMMENT '盈亏比例',
    
    -- ADL排名信息
    adl_score DECIMAL(20, 4) DEFAULT 0 COMMENT 'ADL得分（核心排序字段）',
    adl_rank INT DEFAULT 0 COMMENT 'ADL排名（1开始）',
    risk_level INT DEFAULT 1 COMMENT '风险等级（1-5，5最高风险）',
    
    -- 时间戳
    position_created_at BIGINT COMMENT '持仓创建时间',
    rank_updated_at BIGINT COMMENT '排名最后更新时间',
    created_at BIGINT NOT NULL COMMENT '记录创建时间',
    updated_at BIGINT NOT NULL COMMENT '记录更新时间',
    
    -- 索引
    UNIQUE KEY uk_user_symbol (user_id, symbol),
    INDEX idx_symbol_score (symbol, adl_score DESC),
    INDEX idx_symbol_rank (symbol, adl_rank),
    INDEX idx_symbol_side_score (symbol, side, adl_score DESC),
    INDEX idx_risk_level (risk_level)
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADL排名队列表';

-- ========================================================
-- 表2: adl_execution - ADL执行记录
-- ========================================================
DROP TABLE IF EXISTS adl_execution;

CREATE TABLE adl_execution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    
    -- 执行标识
    adl_execution_id VARCHAR(64) NOT NULL COMMENT 'ADL执行ID（全局唯一）',
    liquidation_id VARCHAR(64) COMMENT '关联的强平事件ID',
    trade_id VARCHAR(64) COMMENT '关联的成交ID',
    
    -- 交易对信息
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 被ADL方（Target - 盈利方）
    target_user_id BIGINT NOT NULL COMMENT '被ADL用户ID',
    target_position_id BIGINT COMMENT '被ADL持仓ID',
    target_side VARCHAR(8) COMMENT '被ADL方原持仓方向',
    target_position_size_before DECIMAL(32, 8) COMMENT '被ADL方原持仓数量',
    target_position_size_after DECIMAL(32, 8) COMMENT '被ADL方ADL后持仓数量',
    target_unrealized_pnl_before DECIMAL(32, 8) COMMENT '被ADL方原未实现盈亏',
    target_realized_pnl_before DECIMAL(32, 8) COMMENT '被ADL方原已实现盈亏',
    target_realized_pnl_after DECIMAL(32, 8) COMMENT '被ADL方ADL后已实现盈亏',
    target_pnl_change DECIMAL(32, 8) COMMENT '被ADL方ADL导致的盈亏变化',
    target_adl_rank INT COMMENT '被ADL方ADL时的ADL排名',
    target_adl_score DECIMAL(20, 4) COMMENT '被ADL方ADL时的ADL得分',
    
    -- 触发ADL方（Source - 被强平方）
    source_user_id BIGINT NOT NULL COMMENT '触发ADL用户ID',
    source_side VARCHAR(8) COMMENT '触发ADL方原持仓方向',
    source_position_size_before DECIMAL(32, 8) COMMENT '触发ADL方原持仓数量',
    source_remaining_size DECIMAL(32, 8) COMMENT '触发ADL方剩余未平仓数量',
    
    -- ADL执行详情
    adl_price DECIMAL(32, 8) NOT NULL COMMENT 'ADL执行价格',
    adl_qty DECIMAL(32, 8) NOT NULL COMMENT 'ADL执行数量',
    adl_value DECIMAL(32, 8) COMMENT 'ADL执行金额（名义价值）',
    is_fully_closed TINYINT DEFAULT 0 COMMENT '是否完全平仓：0否，1是',
    execution_sequence INT DEFAULT 1 COMMENT '执行顺序',
    
    -- 保险基金相关信息
    insurance_fund_involved TINYINT DEFAULT 0 COMMENT '是否涉及保险基金赔付：0否，1是',
    insurance_fund_compensation DECIMAL(32, 8) DEFAULT 0 COMMENT '保险基金赔付金额',
    insurance_fund_balance_before DECIMAL(32, 8) COMMENT 'ADL前保险基金余额',
    insurance_fund_balance_after DECIMAL(32, 8) COMMENT 'ADL后保险基金余额',
    
    -- 状态信息
    status VARCHAR(16) DEFAULT 'PENDING' COMMENT '状态：PENDING/PROCESSING/SUCCESS/FAILED/ROLLED_BACK',
    fail_reason VARCHAR(512) COMMENT '失败原因',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    
    -- 时间戳
    triggered_at BIGINT COMMENT '执行触发时间',
    executed_at BIGINT COMMENT '开始执行时间',
    completed_at BIGINT COMMENT '执行完成时间',
    created_at BIGINT NOT NULL COMMENT '记录创建时间',
    updated_at BIGINT NOT NULL COMMENT '记录更新时间',
    
    -- 幂等性字段
    biz_seq VARCHAR(128) COMMENT '业务序列号（幂等性）',
    
    -- 索引
    UNIQUE KEY uk_adl_execution_id (adl_execution_id),
    UNIQUE KEY uk_biz_seq (biz_seq),
    INDEX idx_liquidation_id (liquidation_id),
    INDEX idx_target_user_id (target_user_id),
    INDEX idx_source_user_id (source_user_id),
    INDEX idx_symbol (symbol),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at),
    INDEX idx_target_user_time (target_user_id, created_at)
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADL执行记录表';

-- ========================================================
-- 表3: insurance_fund - 保险基金
-- ========================================================
DROP TABLE IF EXISTS insurance_fund;

CREATE TABLE insurance_fund (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    
    -- 基本信息
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    currency VARCHAR(16) NOT NULL COMMENT '币种（如USDT）',
    
    -- 余额信息
    balance DECIMAL(32, 8) DEFAULT 0 COMMENT '当前余额',
    available_balance DECIMAL(32, 8) DEFAULT 0 COMMENT '可用余额',
    frozen_amount DECIMAL(32, 8) DEFAULT 0 COMMENT '冻结金额',
    
    -- 累计收支
    total_income DECIMAL(32, 8) DEFAULT 0 COMMENT '累计收入',
    total_expense DECIMAL(32, 8) DEFAULT 0 COMMENT '累计支出',
    total_liquidation_fee_income DECIMAL(32, 8) DEFAULT 0 COMMENT '累计强平手续费收入',
    total_liquidation_profit_injection DECIMAL(32, 8) DEFAULT 0 COMMENT '累计强平盈利注入',
    total_debt_loss_expense DECIMAL(32, 8) DEFAULT 0 COMMENT '累计穿仓赔付支出',
    total_adl_compensation DECIMAL(32, 8) DEFAULT 0 COMMENT '累计ADL相关赔付',
    
    -- 统计信息
    today_income DECIMAL(32, 8) DEFAULT 0 COMMENT '今日收入',
    today_expense DECIMAL(32, 8) DEFAULT 0 COMMENT '今日支出',
    today_date INT COMMENT '今日日期（yyyyMMdd）',
    max_balance DECIMAL(32, 8) DEFAULT 0 COMMENT '历史最高余额',
    min_balance DECIMAL(32, 8) DEFAULT 0 COMMENT '历史最低余额',
    
    -- 阈值配置
    safe_threshold DECIMAL(32, 8) DEFAULT 1000000 COMMENT '安全阈值',
    warning_threshold DECIMAL(32, 8) DEFAULT 500000 COMMENT '警告阈值',
    danger_threshold DECIMAL(32, 8) DEFAULT 100000 COMMENT '危险阈值',
    status VARCHAR(16) DEFAULT 'SAFE' COMMENT '状态：SAFE/WARNING/DANGER',
    
    -- 元信息
    version INT DEFAULT 0 COMMENT '版本号（乐观锁）',
    last_operation_type VARCHAR(32) COMMENT '最后操作类型',
    last_operation_amount DECIMAL(32, 8) COMMENT '最后操作金额',
    last_operation_at BIGINT COMMENT '最后操作时间',
    last_operation_ref_id VARCHAR(64) COMMENT '最后操作关联ID',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '记录创建时间',
    updated_at BIGINT NOT NULL COMMENT '记录更新时间',
    
    -- 索引
    UNIQUE KEY uk_symbol_currency (symbol, currency),
    INDEX idx_status (status),
    INDEX idx_currency (currency)
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='保险基金表';

-- ========================================================
-- 表4: insurance_fund_flow - 保险基金流水（可选，用于详细审计）
-- ========================================================
DROP TABLE IF EXISTS insurance_fund_flow;

CREATE TABLE insurance_fund_flow (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    
    flow_id VARCHAR(64) NOT NULL COMMENT '流水ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    currency VARCHAR(16) NOT NULL COMMENT '币种',
    
    flow_type VARCHAR(32) NOT NULL COMMENT '流水类型：INCOME/EXPENSE/FREEZE/UNFREEZE',
    sub_type VARCHAR(32) COMMENT '子类型：LIQUIDATION_FEE/PROFIT_INJECTION/DEBT_LOSS/ADL_COMPENSATION',
    
    amount DECIMAL(32, 8) NOT NULL COMMENT '变动金额',
    balance_before DECIMAL(32, 8) COMMENT '变动前余额',
    balance_after DECIMAL(32, 8) COMMENT '变动后余额',
    
    ref_id VARCHAR(64) COMMENT '关联业务ID',
    ref_type VARCHAR(32) COMMENT '关联业务类型',
    
    description VARCHAR(256) COMMENT '描述',
    
    created_at BIGINT NOT NULL COMMENT '创建时间',
    
    -- 索引
    UNIQUE KEY uk_flow_id (flow_id),
    INDEX idx_symbol_currency (symbol, currency),
    INDEX idx_ref_id (ref_id),
    INDEX idx_created_at (created_at)
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='保险基金流水表';

-- ========================================================
-- 初始化数据
-- ========================================================

-- 初始化保险基金（示例数据）
INSERT INTO insurance_fund (symbol, currency, balance, available_balance, total_income, total_expense, 
                           today_date, safe_threshold, warning_threshold, danger_threshold, status,
                           version, created_at, updated_at) 
VALUES 
('BTCUSDT', 'USDT', 0, 0, 0, 0, DATE_FORMAT(CURDATE(), '%Y%m%d'), 1000000, 500000, 100000, 'SAFE', 0, UNIX_TIMESTAMP()*1000, UNIX_TIMESTAMP()*1000),
('ETHUSDT', 'USDT', 0, 0, 0, 0, DATE_FORMAT(CURDATE(), '%Y%m%d'), 1000000, 500000, 100000, 'SAFE', 0, UNIX_TIMESTAMP()*1000, UNIX_TIMESTAMP()*1000);

-- ========================================================
-- 创建数据库用户（可选，生产环境使用）
-- ========================================================
-- CREATE USER IF NOT EXISTS 'adl_user'@'%' IDENTIFIED BY 'your_password';
-- GRANT SELECT, INSERT, UPDATE, DELETE ON exchange_adl.* TO 'adl_user'@'%';
-- FLUSH PRIVILEGES;
