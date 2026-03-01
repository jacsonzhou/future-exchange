-- =====================================================
-- Index Price Core 数据库表结构
-- 数据库: exchange_market
-- 职责: 指数价格计算、多交易所数据源聚合
-- =====================================================

CREATE DATABASE IF NOT EXISTS exchange_market 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE exchange_market;

-- =====================================================
-- 1. 指数价格配置表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_index_price_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 成分交易所配置
    components VARCHAR(256) NOT NULL DEFAULT 'binance,okx,coinbase' COMMENT '成分交易所列表，逗号分隔',
    component_weights VARCHAR(256) COMMENT '权重配置，逗号分隔（为空则等权）',
    
    -- 更新配置
    update_interval_ms INT DEFAULT 5000 COMMENT '更新间隔（毫秒）',
    
    -- 偏差阈值
    deviation_threshold BIGINT DEFAULT 5 COMMENT '价格偏差阈值（百分比*100，如5=5%）',
    min_valid_components INT DEFAULT 2 COMMENT '最小有效成分数',
    max_deviation_from_median BIGINT DEFAULT 10 COMMENT '与价格中位数最大偏差（百分比*100）',
    
    -- 状态
    status TINYINT DEFAULT 1 COMMENT '状态: 1启用 0禁用',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_symbol (symbol),
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='指数价格配置表';

-- =====================================================
-- 2. 指数价格历史表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_index_price (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 价格信息
    price BIGINT NOT NULL COMMENT '指数价格（8位精度）',
    confidence INT DEFAULT 100 COMMENT '置信度（0-100）',
    
    -- 来源信息
    source VARCHAR(32) COMMENT '价格来源: AGGREGATED/SINGLE',
    weight INT COMMENT '权重',
    raw_price BIGINT COMMENT '原始价格',
    valid_component_count INT DEFAULT 0 COMMENT '有效成分数',
    total_component_count INT DEFAULT 0 COMMENT '总成分数',
    
    -- 时间戳
    timestamp BIGINT NOT NULL COMMENT '数据时间戳（毫秒）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    KEY idx_symbol_time (symbol, timestamp),
    KEY idx_timestamp (timestamp)
) ENGINE=InnoDB COMMENT='指数价格历史表';

-- =====================================================
-- 3. 指数价格成分表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_index_price_component (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    exchange VARCHAR(32) NOT NULL COMMENT '交易所代码',
    
    -- 价格信息
    raw_price BIGINT NOT NULL COMMENT '交易所原始价格',
    adjusted_price BIGINT COMMENT '调整后价格',
    weight INT NOT NULL COMMENT '权重',
    
    -- 有效性
    valid TINYINT DEFAULT 1 COMMENT '是否有效: 1是 0否',
    invalid_reason VARCHAR(256) COMMENT '失效原因',
    
    -- 时间戳
    timestamp BIGINT NOT NULL COMMENT '数据时间戳（毫秒）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    KEY idx_symbol_exchange_time (symbol, exchange, timestamp),
    KEY idx_valid (valid)
) ENGINE=InnoDB COMMENT='指数价格成分表';

-- =====================================================
-- 4. 交易所数据源配置表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_exchange_data_source (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    exchange_code VARCHAR(32) NOT NULL COMMENT '交易所代码',
    exchange_name VARCHAR(64) NOT NULL COMMENT '交易所名称',
    
    -- API配置
    rest_base_url VARCHAR(256) COMMENT 'REST API基础URL',
    ws_base_url VARCHAR(256) COMMENT 'WebSocket基础URL',
    api_key VARCHAR(128) COMMENT 'API Key',
    
    -- 权重配置
    default_weight INT DEFAULT 1 COMMENT '默认权重',
    priority INT DEFAULT 1 COMMENT '优先级',
    
    -- 状态
    status TINYINT DEFAULT 1 COMMENT '状态: 1启用 0禁用',
    last_healthy_time TIMESTAMP COMMENT '最后健康检查时间',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_exchange_code (exchange_code),
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='交易所数据源配置表';

-- =====================================================
-- 5. 价格异常记录表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_price_anomaly (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 异常信息
    anomaly_type VARCHAR(32) NOT NULL COMMENT '异常类型: DEVIATION/STALE/INVALID',
    severity VARCHAR(16) NOT NULL COMMENT '严重程度: WARNING/CRITICAL',
    description VARCHAR(512) COMMENT '描述',
    
    -- 相关数据
    expected_price BIGINT COMMENT '预期价格',
    actual_price BIGINT COMMENT '实际价格',
    deviation_pct BIGINT COMMENT '偏差百分比*100',
    
    -- 处理状态
    status TINYINT DEFAULT 0 COMMENT '状态: 0=未处理 1=已处理',
    handled_by VARCHAR(64) COMMENT '处理人',
    handled_at TIMESTAMP COMMENT '处理时间',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    KEY idx_symbol (symbol),
    KEY idx_anomaly_type (anomaly_type),
    KEY idx_status (status),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB COMMENT='价格异常记录表';

-- =====================================================
-- 初始化数据
-- =====================================================
INSERT INTO t_index_price_config (symbol, components, update_interval_ms, deviation_threshold, min_valid_components, status) VALUES
('BTCUSDT', 'binance,okx,coinbase', 5000, 5, 2, 1),
('ETHUSDT', 'binance,okx,coinbase', 5000, 5, 2, 1),
('SOLUSDT', 'binance,okx', 5000, 5, 2, 1)
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP;

INSERT INTO t_exchange_data_source (exchange_code, exchange_name, rest_base_url, ws_base_url, default_weight, priority, status) VALUES
('binance', 'Binance', 'https://api.binance.com', 'wss://stream.binance.com:9443', 1, 1, 1),
('okx', 'OKX', 'https://www.okx.com', 'wss://ws.okx.com:8443', 1, 2, 1),
('coinbase', 'Coinbase', 'https://api.exchange.coinbase.com', 'wss://ws-feed.exchange.coinbase.com', 1, 3, 1)
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP;
