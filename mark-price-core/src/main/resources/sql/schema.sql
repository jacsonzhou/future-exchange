-- =====================================================
-- Mark Price Core 数据库表结构
-- 数据库: exchange_market
-- 职责: 标记价格计算、资金费率关联
-- =====================================================

CREATE DATABASE IF NOT EXISTS exchange_market 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE exchange_market;

-- =====================================================
-- 1. 标记价格历史表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_mark_price (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 价格信息
    mark_price BIGINT NOT NULL COMMENT '标记价格（8位精度）',
    index_price BIGINT NOT NULL COMMENT '指数价格（8位精度）',
    
    -- 资金费率相关
    funding_rate BIGINT COMMENT '资金费率（8位精度）',
    next_funding_time BIGINT COMMENT '下次结算时间（毫秒）',
    
    -- 时间戳
    timestamp BIGINT NOT NULL COMMENT '数据时间戳（毫秒）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    KEY idx_symbol_time (symbol, timestamp),
    KEY idx_timestamp (timestamp)
) ENGINE=InnoDB COMMENT='标记价格历史表';

-- =====================================================
-- 2. 标记价格配置表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_mark_price_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 计算配置
    basis_rate_cap BIGINT DEFAULT 1000 COMMENT '基差率上限（百分比*100）',
    basis_rate_floor BIGINT DEFAULT -1000 COMMENT '基差率下限（百分比*100）',
    
    -- 平滑参数
    smoothing_factor DECIMAL(10,8) DEFAULT 0.005 COMMENT '平滑因子',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_symbol (symbol)
) ENGINE=InnoDB COMMENT='标记价格配置表';

-- =====================================================
-- 3. 标记价格统计表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_mark_price_statistics (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    stat_date INT NOT NULL COMMENT '统计日期(YYYYMMDD)',
    
    -- 价格统计
    avg_mark_price BIGINT COMMENT '平均标记价格',
    max_mark_price BIGINT COMMENT '最高标记价格',
    min_mark_price BIGINT COMMENT '最低标记价格',
    
    -- 基差统计
    avg_basis_rate BIGINT COMMENT '平均基差率',
    max_basis_rate BIGINT COMMENT '最大基差率',
    min_basis_rate BIGINT COMMENT '最小基差率',
    
    -- 数据点统计
    sample_count INT DEFAULT 0 COMMENT '采样次数',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_symbol_date (symbol, stat_date)
) ENGINE=InnoDB COMMENT='标记价格统计表';

-- =====================================================
-- 初始化数据
-- =====================================================
INSERT INTO t_mark_price_config (symbol, basis_rate_cap, basis_rate_floor, smoothing_factor) VALUES
('BTCUSDT', 1000, -1000, 0.005),
('ETHUSDT', 1000, -1000, 0.005),
('SOLUSDT', 1000, -1000, 0.005)
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP;
