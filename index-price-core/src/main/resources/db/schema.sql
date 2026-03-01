-- 指数价格服务数据库脚本

CREATE DATABASE IF NOT EXISTS exchange_market DEFAULT CHARSET utf8mb4;

USE exchange_market;

-- 指数价格配置表
CREATE TABLE IF NOT EXISTS t_index_price_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    components VARCHAR(256) NOT NULL DEFAULT 'binance,okx,coinbase' COMMENT '成分交易所列表，逗号分隔',
    update_interval_ms INT DEFAULT 5000 COMMENT '更新间隔（毫秒）',
    deviation_threshold BIGINT DEFAULT 5 COMMENT '价格偏差阈值（百分比）',
    min_valid_components INT DEFAULT 2 COMMENT '最小有效成分数',
    status TINYINT DEFAULT 1 COMMENT '状态：1启用 0禁用',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol (symbol)
) ENGINE=InnoDB COMMENT='指数价格配置表';

-- 指数价格历史表
CREATE TABLE IF NOT EXISTS t_index_price (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    price BIGINT NOT NULL COMMENT '指数价格（8位精度）',
    source VARCHAR(32) COMMENT '价格来源',
    weight INT COMMENT '权重',
    raw_price BIGINT COMMENT '原始价格',
    timestamp BIGINT NOT NULL COMMENT '数据时间戳',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_symbol_time (symbol, timestamp),
    KEY idx_timestamp (timestamp)
) ENGINE=InnoDB COMMENT='指数价格历史表';

-- 指数价格成分表
CREATE TABLE IF NOT EXISTS t_index_price_component (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    exchange VARCHAR(32) NOT NULL COMMENT '交易所代码',
    raw_price BIGINT NOT NULL COMMENT '交易所原始价格',
    weight INT NOT NULL COMMENT '权重',
    valid TINYINT DEFAULT 1 COMMENT '是否有效：1是 0否',
    invalid_reason VARCHAR(256) COMMENT '失效原因',
    timestamp BIGINT NOT NULL COMMENT '数据时间戳',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_symbol_exchange_time (symbol, exchange, timestamp)
) ENGINE=InnoDB COMMENT='指数价格成分表';

-- 初始化配置数据
INSERT INTO t_index_price_config (symbol, components, update_interval_ms, deviation_threshold, min_valid_components, status) VALUES
('BTCUSDT', 'binance,okx,coinbase', 5000, 5, 2, 1),
('ETHUSDT', 'binance,okx,coinbase', 5000, 5, 2, 1),
('SOLUSDT', 'binance,okx', 5000, 5, 2, 1)
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP;
