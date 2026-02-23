-- =====================================================
-- Funding Rate Core 数据库表结构
-- 数据库: exchange_funding
-- 职责: 资金费率计算、资金费用结算
-- =====================================================

CREATE DATABASE IF NOT EXISTS exchange_funding 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE exchange_funding;

-- =====================================================
-- 1. 资金费率配置表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_funding_rate_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 结算配置
    settlement_interval INT DEFAULT 28800 COMMENT '结算间隔(秒)，默认8小时',
    settlement_times VARCHAR(64) DEFAULT '00:00,08:00,16:00' COMMENT '结算时间点(HH:MM)',
    
    -- 费率限制
    max_rate BIGINT NOT NULL COMMENT '最大资金费率(如750000=0.75%)',
    min_rate BIGINT NOT NULL COMMENT '最小资金费率(如-750000=-0.75%)',
    interest_rate BIGINT DEFAULT 1000 COMMENT '利率差(如0.01%=1000)',
    
    -- 状态
    status TINYINT DEFAULT 1 COMMENT '状态: 1启用 0禁用',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_symbol (symbol),
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='资金费率配置表';

-- =====================================================
-- 2. 资金费率历史表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_funding_rate_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 结算信息
    funding_time BIGINT NOT NULL COMMENT '结算时间戳(毫秒)',
    funding_rate BIGINT NOT NULL COMMENT '资金费率(如50000=0.005%)',
    
    -- 价格信息
    mark_price BIGINT NOT NULL COMMENT '标记价格',
    index_price BIGINT NOT NULL COMMENT '指数价格',
    premium_index BIGINT NOT NULL COMMENT '溢价指数',
    
    -- 持仓统计
    total_long_qty BIGINT NOT NULL COMMENT '多头总持仓量',
    total_short_qty BIGINT NOT NULL COMMENT '空头总持仓量',
    
    -- 结算金额
    settlement_amount BIGINT COMMENT '本次结算总金额',
    payer_count INT DEFAULT 0 COMMENT '支付方数量',
    receiver_count INT DEFAULT 0 COMMENT '收取方数量',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_symbol_time (symbol, funding_time),
    KEY idx_funding_time (funding_time),
    KEY idx_symbol (symbol)
) ENGINE=InnoDB COMMENT='资金费率历史表';

-- =====================================================
-- 3. 用户资金费用明细表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_user_funding_fee (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 结算信息
    funding_time BIGINT NOT NULL COMMENT '结算时间戳(毫秒)',
    side VARCHAR(8) NOT NULL COMMENT '持仓方向: LONG/SHORT',
    
    -- 持仓信息
    position_qty BIGINT NOT NULL COMMENT '持仓数量',
    mark_price BIGINT NOT NULL COMMENT '标记价格',
    
    -- 费用计算
    funding_rate BIGINT NOT NULL COMMENT '资金费率',
    funding_fee BIGINT NOT NULL COMMENT '资金费用(正=支付,负=收取)',
    
    -- 保证金模式
    margin_mode VARCHAR(16) NOT NULL COMMENT '保证金模式: ISOLATED/CROSS',
    
    -- 结算状态
    status VARCHAR(16) DEFAULT 'SETTLED' COMMENT '状态: PENDING/SETTLED/FAILED',
    ledger_entry_id BIGINT COMMENT '关联账本记录ID',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    settled_at TIMESTAMP COMMENT '结算完成时间',
    
    UNIQUE KEY uk_user_symbol_time (user_id, symbol, funding_time),
    KEY idx_funding_time (funding_time),
    KEY idx_user_id (user_id),
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='用户资金费用明细表';

-- =====================================================
-- 4. 预估资金费率实时表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_funding_rate_estimate (
    symbol VARCHAR(32) PRIMARY KEY COMMENT '交易对',
    
    -- 预估信息
    next_funding_time BIGINT NOT NULL COMMENT '下次结算时间',
    estimated_rate BIGINT NOT NULL COMMENT '预估资金费率',
    
    -- 当前价格
    mark_price BIGINT NOT NULL COMMENT '当前标记价格',
    index_price BIGINT NOT NULL COMMENT '当前指数价格',
    premium_index BIGINT NOT NULL COMMENT '当前溢价指数',
    
    -- 持仓统计
    long_qty BIGINT DEFAULT 0 COMMENT '多头持仓量',
    short_qty BIGINT DEFAULT 0 COMMENT '空头持仓量',
    
    -- 时间戳
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    KEY idx_next_funding_time (next_funding_time)
) ENGINE=InnoDB COMMENT='预估资金费率实时表';

-- =====================================================
-- 5. 资金费率结算任务表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_funding_settlement_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 任务信息
    funding_time BIGINT NOT NULL COMMENT '结算时间',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0=待执行 1=执行中 2=完成 3=失败',
    
    -- 统计
    total_users INT DEFAULT 0 COMMENT '总用户数',
    processed_users INT DEFAULT 0 COMMENT '已处理用户数',
    success_count INT DEFAULT 0 COMMENT '成功数',
    fail_count INT DEFAULT 0 COMMENT '失败数',
    
    -- 时间戳
    execute_start_time BIGINT COMMENT '开始执行时间',
    execute_end_time BIGINT COMMENT '结束执行时间',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_symbol_time (symbol, funding_time),
    KEY idx_status (status),
    KEY idx_funding_time (funding_time)
) ENGINE=InnoDB COMMENT='资金费率结算任务表';

-- =====================================================
-- 6. 资金费用统计表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_funding_statistics (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    stat_date INT NOT NULL COMMENT '统计日期(YYYYMMDD)',
    
    -- 费用统计
    total_long_fee BIGINT DEFAULT 0 COMMENT '多头总费用',
    total_short_fee BIGINT DEFAULT 0 COMMENT '空头总费用',
    net_funding BIGINT DEFAULT 0 COMMENT '净资金费（收-付）',
    
    -- 用户统计
    long_payers INT DEFAULT 0 COMMENT '多头支付用户数',
    long_receivers INT DEFAULT 0 COMMENT '多头收取用户数',
    short_payers INT DEFAULT 0 COMMENT '空头支付用户数',
    short_receivers INT DEFAULT 0 COMMENT '空头收取用户数',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_symbol_date (symbol, stat_date)
) ENGINE=InnoDB COMMENT='资金费用统计表';

-- =====================================================
-- 初始化数据
-- =====================================================
INSERT INTO t_funding_rate_config (symbol, settlement_interval, max_rate, min_rate, interest_rate, status) VALUES
('BTCUSDT', 28800, 750000, -750000, 1000, 1),
('ETHUSDT', 28800, 750000, -750000, 1000, 1),
('SOLUSDT', 28800, 750000, -750000, 1000, 1)
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP;
