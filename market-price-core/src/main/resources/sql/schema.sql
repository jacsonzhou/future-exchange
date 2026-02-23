-- =====================================================
-- Market Price Core 数据库表结构
-- 数据库: exchange_market
-- 职责: 行情生成、K线计算、Ticker统计、OrderBook深度
-- =====================================================

CREATE DATABASE IF NOT EXISTS exchange_market 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE exchange_market;

-- =====================================================
-- 1. K线历史表（按时间分区）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_kline (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    interval_val VARCHAR(10) NOT NULL COMMENT '周期(1m,5m,15m,1h,4h,1d,1w,1M)',
    
    -- 时间
    open_time BIGINT NOT NULL COMMENT '开盘时间戳(ms)',
    close_time BIGINT NOT NULL COMMENT '收盘时间戳(ms)',
    
    -- OHLC
    open_price BIGINT NOT NULL COMMENT '开盘价(8位小数)',
    high_price BIGINT NOT NULL COMMENT '最高价(8位小数)',
    low_price BIGINT NOT NULL COMMENT '最低价(8位小数)',
    close_price BIGINT NOT NULL COMMENT '收盘价(8位小数)',
    
    -- 成交量
    volume BIGINT NOT NULL COMMENT '成交量',
    quote_volume BIGINT NOT NULL COMMENT '成交额',
    trade_count INT NOT NULL DEFAULT 0 COMMENT '成交笔数',
    
    -- 主动买入
    taker_buy_volume BIGINT NOT NULL DEFAULT 0 COMMENT '主动买入成交量',
    taker_buy_quote_volume BIGINT NOT NULL DEFAULT 0 COMMENT '主动买入成交额',
    
    -- 时间戳
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    UNIQUE KEY uk_symbol_interval_time (symbol, interval_val, open_time),
    KEY idx_symbol_interval (symbol, interval_val, open_time DESC),
    KEY idx_symbol_time (symbol, open_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='K线历史表'
PARTITION BY RANGE (open_time DIV 2592000000) (
    PARTITION p202401 VALUES LESS THAN (1706745600),
    PARTITION p202402 VALUES LESS THAN (1709251200),
    PARTITION p202403 VALUES LESS THAN (1711929600),
    PARTITION p202404 VALUES LESS THAN (1714521600),
    PARTITION p202405 VALUES LESS THAN (1717200000),
    PARTITION p202406 VALUES LESS THAN (1719792000),
    PARTITION p202407 VALUES LESS THAN (1722470400),
    PARTITION p202408 VALUES LESS THAN (1725148800),
    PARTITION p202409 VALUES LESS THAN (1727740800),
    PARTITION p202410 VALUES LESS THAN (1730419200),
    PARTITION p202411 VALUES LESS THAN (1733011200),
    PARTITION p202412 VALUES LESS THAN (1735689600),
    PARTITION p_future VALUES LESS THAN MAXVALUE
);

-- =====================================================
-- 2. 成交历史表（按天分区）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_trade_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增ID',
    trade_id VARCHAR(50) NOT NULL COMMENT '成交ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    
    -- 成交信息
    sequence BIGINT NOT NULL COMMENT '撮合序号',
    price BIGINT NOT NULL COMMENT '成交价(8位小数)',
    quantity BIGINT NOT NULL COMMENT '成交量(8位小数)',
    side TINYINT NOT NULL COMMENT '方向: 1=BUY 2=SELL',
    
    -- 成交时间
    trade_time BIGINT NOT NULL COMMENT '成交时间戳(ms)',
    
    -- 成交类型
    is_buyer_maker TINYINT NOT NULL DEFAULT 0 COMMENT '是否买方maker',
    buyer_order_id BIGINT NOT NULL DEFAULT 0 COMMENT '买方订单ID',
    seller_order_id BIGINT NOT NULL DEFAULT 0 COMMENT '卖方订单ID',
    trade_type VARCHAR(20) NOT NULL DEFAULT 'NORMAL' COMMENT '成交类型: NORMAL/LIQUIDATION/ADL',
    
    -- 时间戳
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    UNIQUE KEY uk_trade_id (trade_id),
    KEY idx_symbol_sequence (symbol, sequence),
    KEY idx_symbol_time (symbol, trade_time DESC),
    KEY idx_trade_time (trade_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成交历史表'
PARTITION BY RANGE (trade_time DIV 86400000) (
    PARTITION p20240101 VALUES LESS THAN (1704153600),
    PARTITION p20240201 VALUES LESS THAN (1706745600),
    PARTITION p20240301 VALUES LESS THAN (1709251200),
    PARTITION p20240401 VALUES LESS THAN (1711929600),
    PARTITION p20240501 VALUES LESS THAN (1714521600),
    PARTITION p20240601 VALUES LESS THAN (1717200000),
    PARTITION p20240701 VALUES LESS THAN (1719792000),
    PARTITION p20240801 VALUES LESS THAN (1722470400),
    PARTITION p20240901 VALUES LESS THAN (1725148800),
    PARTITION p20241001 VALUES LESS THAN (1727740800),
    PARTITION p20241101 VALUES LESS THAN (1730419200),
    PARTITION p20241201 VALUES LESS THAN (1733011200),
    PARTITION p_future VALUES LESS THAN MAXVALUE
);

-- =====================================================
-- 3. 24小时统计表（Ticker）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_ticker_24h (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    
    -- 价格变动
    price_change BIGINT NOT NULL DEFAULT 0 COMMENT '价格变动',
    price_change_percent DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '价格变动百分比',
    weighted_avg_price BIGINT NOT NULL DEFAULT 0 COMMENT '加权平均价',
    
    -- 最新价格
    last_price BIGINT NOT NULL DEFAULT 0 COMMENT '最新成交价',
    last_qty BIGINT NOT NULL DEFAULT 0 COMMENT '最新成交量',
    
    -- 价格范围
    open_price BIGINT NOT NULL DEFAULT 0 COMMENT '开盘价',
    high_price BIGINT NOT NULL DEFAULT 0 COMMENT '最高价',
    low_price BIGINT NOT NULL DEFAULT 0 COMMENT '最低价',
    
    -- 成交量
    volume BIGINT NOT NULL DEFAULT 0 COMMENT '成交量',
    quote_volume BIGINT NOT NULL DEFAULT 0 COMMENT '成交额',
    
    -- 统计区间
    open_time BIGINT NOT NULL COMMENT '统计开始时间',
    close_time BIGINT NOT NULL COMMENT '统计结束时间',
    first_id BIGINT NOT NULL DEFAULT 0 COMMENT '第一笔成交ID',
    last_id BIGINT NOT NULL DEFAULT 0 COMMENT '最后一笔成交ID',
    trade_count INT NOT NULL DEFAULT 0 COMMENT '成交笔数',
    
    -- 买卖盘最优价
    best_bid_price BIGINT COMMENT '最优买价',
    best_bid_qty BIGINT COMMENT '最优买量',
    best_ask_price BIGINT COMMENT '最优卖价',
    best_ask_qty BIGINT COMMENT '最优卖量',
    
    -- 时间戳
    timestamp BIGINT NOT NULL COMMENT '数据更新时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    UNIQUE KEY uk_symbol (symbol),
    KEY idx_symbol_time (symbol, timestamp DESC),
    KEY idx_timestamp (timestamp DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='24小时统计表';

-- =====================================================
-- 4. 深度快照表（用于故障恢复）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_depth_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    
    -- 快照信息
    last_update_id BIGINT NOT NULL COMMENT '最后更新序号',
    bids_json TEXT NOT NULL COMMENT '买盘JSON',
    asks_json TEXT NOT NULL COMMENT '卖盘JSON',
    
    -- 统计
    bid_levels INT NOT NULL DEFAULT 0 COMMENT '买盘档位数',
    ask_levels INT NOT NULL DEFAULT 0 COMMENT '卖盘档位数',
    bid_total_qty DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '买盘总数量',
    ask_total_qty DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '卖盘总数量',
    
    -- 时间戳
    timestamp BIGINT NOT NULL COMMENT '快照时间（毫秒）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    KEY idx_symbol_time (symbol, timestamp DESC),
    KEY idx_timestamp (timestamp DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='深度快照表';

-- =====================================================
-- 5. 标记价格历史表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_mark_price_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    
    mark_price BIGINT NOT NULL COMMENT '标记价格',
    index_price BIGINT NOT NULL COMMENT '指数价格',
    estimated_settle_price BIGINT NOT NULL DEFAULT 0 COMMENT '预估结算价',
    
    -- 资金费率
    last_funding_rate DECIMAL(10,8) NOT NULL DEFAULT 0 COMMENT '最新资金费率',
    next_funding_time BIGINT NOT NULL DEFAULT 0 COMMENT '下次资金费时间',
    
    -- 时间戳
    timestamp BIGINT NOT NULL COMMENT '时间戳（毫秒）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    KEY idx_symbol_time (symbol, timestamp DESC),
    KEY idx_timestamp (timestamp DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标记价格历史表'
PARTITION BY RANGE (timestamp DIV 86400000) (
    PARTITION p_mp_future VALUES LESS THAN MAXVALUE
);

-- =====================================================
-- 6. 交易对配置表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_symbol_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    
    -- 基础信息
    base_asset VARCHAR(20) NOT NULL COMMENT '基础资产',
    quote_asset VARCHAR(20) NOT NULL COMMENT '计价资产',
    
    -- 价格精度
    price_precision INT NOT NULL DEFAULT 2 COMMENT '价格精度（小数位）',
    quantity_precision INT NOT NULL DEFAULT 8 COMMENT '数量精度（小数位）',
    
    -- 交易限制
    min_qty DECIMAL(32,16) NOT NULL COMMENT '最小下单数量',
    max_qty DECIMAL(32,16) NOT NULL COMMENT '最大下单数量',
    min_notional DECIMAL(32,16) NOT NULL COMMENT '最小下单金额',
    
    -- 状态
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1=交易 0=暂停',
    
    -- 时间戳
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    UNIQUE KEY uk_symbol (symbol),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易对配置表';

-- =====================================================
-- 初始化数据
-- =====================================================
INSERT INTO t_symbol_config (symbol, base_asset, quote_asset, price_precision, quantity_precision, min_qty, max_qty, min_notional, status) VALUES
('BTCUSDT', 'BTC', 'USDT', 2, 8, 0.001, 100, 10, 1),
('ETHUSDT', 'ETH', 'USDT', 2, 8, 0.01, 1000, 10, 1),
('SOLUSDT', 'SOL', 'USDT', 4, 8, 0.1, 5000, 10, 1)
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP;

INSERT INTO t_ticker_24h (symbol, open_time, close_time, timestamp) VALUES 
('BTCUSDT', 0, 0, 0),
('ETHUSDT', 0, 0, 0),
('SOLUSDT', 0, 0, 0)
ON DUPLICATE KEY UPDATE symbol = symbol;
