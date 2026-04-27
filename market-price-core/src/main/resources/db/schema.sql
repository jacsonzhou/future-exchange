-- =====================================================
-- 行情服务数据库初始化脚本
-- 用于MySQL 8.0+
-- =====================================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS exchange_market 
DEFAULT CHARACTER SET utf8mb4 
COLLATE utf8mb4_unicode_ci;

USE exchange_market;

-- =====================================================
-- K线历史表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_kline_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    interval_val VARCHAR(10) NOT NULL COMMENT '周期(1m,5m,1h,1d)',
    open_time BIGINT NOT NULL COMMENT '开盘时间戳(ms)',
    close_time BIGINT NOT NULL COMMENT '收盘时间戳(ms)',
    open_price BIGINT NOT NULL COMMENT '开盘价(8位小数)',
    high_price BIGINT NOT NULL COMMENT '最高价(8位小数)',
    low_price BIGINT NOT NULL COMMENT '最低价(8位小数)',
    close_price BIGINT NOT NULL COMMENT '收盘价(8位小数)',
    volume BIGINT NOT NULL COMMENT '成交量',
    quote_volume BIGINT NOT NULL COMMENT '成交额',
    trade_count INT NOT NULL DEFAULT 0 COMMENT '成交笔数',
    taker_buy_volume BIGINT NOT NULL DEFAULT 0 COMMENT '主动买入成交量',
    taker_buy_quote_volume BIGINT NOT NULL DEFAULT 0 COMMENT '主动买入成交额',
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
-- 成交历史表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_trade_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增ID',
    trade_id VARCHAR(50) NOT NULL COMMENT '成交ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    sequence BIGINT NOT NULL COMMENT '撮合序号',
    price BIGINT NOT NULL COMMENT '成交价(8位小数)',
    quantity BIGINT NOT NULL COMMENT '成交量(8位小数)',
    side TINYINT NOT NULL COMMENT '方向(1买2卖)',
    trade_time BIGINT NOT NULL COMMENT '成交时间戳(ms)',
    is_buyer_maker TINYINT NOT NULL DEFAULT 0 COMMENT '是否买方maker',
    buyer_order_id BIGINT NOT NULL DEFAULT 0 COMMENT '买方订单ID',
    seller_order_id BIGINT NOT NULL DEFAULT 0 COMMENT '卖方订单ID',
    trade_type VARCHAR(20) NOT NULL DEFAULT 'NORMAL' COMMENT '成交类型(NORMAL,LIQUIDATION,ADL)',
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
-- 24小时统计表（快照）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_ticker_24h (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    price_change BIGINT NOT NULL DEFAULT 0 COMMENT '价格变动',
    price_change_percent DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '价格变动百分比',
    weighted_avg_price BIGINT NOT NULL DEFAULT 0 COMMENT '加权平均价',
    last_price BIGINT NOT NULL DEFAULT 0 COMMENT '最新成交价',
    last_qty BIGINT NOT NULL DEFAULT 0 COMMENT '最新成交量',
    open_price BIGINT NOT NULL DEFAULT 0 COMMENT '开盘价',
    high_price BIGINT NOT NULL DEFAULT 0 COMMENT '最高价',
    low_price BIGINT NOT NULL DEFAULT 0 COMMENT '最低价',
    volume BIGINT NOT NULL DEFAULT 0 COMMENT '成交量',
    quote_volume BIGINT NOT NULL DEFAULT 0 COMMENT '成交额',
    open_time BIGINT NOT NULL COMMENT '统计开始时间',
    close_time BIGINT NOT NULL COMMENT '统计结束时间',
    first_id BIGINT NOT NULL DEFAULT 0 COMMENT '第一笔成交ID',
    last_id BIGINT NOT NULL DEFAULT 0 COMMENT '最后一笔成交ID',
    trade_count INT NOT NULL DEFAULT 0 COMMENT '成交笔数',
    timestamp BIGINT NOT NULL COMMENT '数据更新时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    UNIQUE KEY uk_symbol (symbol),
    KEY idx_symbol_time (symbol, timestamp DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='24小时统计表';

-- =====================================================
-- 深度快照表（用于故障恢复）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_depth_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    last_update_id BIGINT NOT NULL COMMENT '最后更新序号',
    bids_json TEXT NOT NULL COMMENT '买盘JSON',
    asks_json TEXT NOT NULL COMMENT '卖盘JSON',
    timestamp BIGINT NOT NULL COMMENT '快照时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    KEY idx_symbol_time (symbol, timestamp DESC),
    KEY idx_timestamp (timestamp DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='深度快照表';

-- =====================================================
-- 标记价格历史表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_mark_price_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    mark_price BIGINT NOT NULL COMMENT '标记价格',
    index_price BIGINT NOT NULL COMMENT '指数价格',
    estimated_settle_price BIGINT NOT NULL DEFAULT 0 COMMENT '预估结算价',
    last_funding_rate DECIMAL(10,8) NOT NULL DEFAULT 0 COMMENT '最新资金费率',
    next_funding_time BIGINT NOT NULL DEFAULT 0 COMMENT '下次资金费时间',
    timestamp BIGINT NOT NULL COMMENT '时间戳',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    KEY idx_symbol_time (symbol, timestamp DESC),
    KEY idx_timestamp (timestamp DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标记价格历史表'
PARTITION BY RANGE (timestamp DIV 86400000) (
    PARTITION p_mp_future VALUES LESS THAN MAXVALUE
);

-- =====================================================
-- K线权威表（用于恢复/去重/对账）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_kline_authority (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    source VARCHAR(32) NOT NULL COMMENT '来源: binance 等',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    interval_val VARCHAR(10) NOT NULL COMMENT '周期',
    open_time BIGINT NOT NULL COMMENT '开盘时间(ms)',
    close_time BIGINT NOT NULL COMMENT '收盘时间(ms)',
    open_price BIGINT NOT NULL COMMENT '开盘价(8位小数)',
    high_price BIGINT NOT NULL COMMENT '最高价(8位小数)',
    low_price BIGINT NOT NULL COMMENT '最低价(8位小数)',
    close_price BIGINT NOT NULL COMMENT '收盘价(8位小数)',
    volume BIGINT NOT NULL COMMENT '成交量(8位小数)',
    quote_volume BIGINT NOT NULL COMMENT '成交额(8位小数)',
    trade_count INT NOT NULL DEFAULT 0 COMMENT '成交笔数',
    taker_buy_volume BIGINT NOT NULL DEFAULT 0 COMMENT '主动买入量(8位小数)',
    taker_buy_quote_volume BIGINT NOT NULL DEFAULT 0 COMMENT '主动买入额(8位小数)',
    candle_closed TINYINT NOT NULL DEFAULT 0 COMMENT '是否收线: 0否1是',
    payload_hash CHAR(64) NOT NULL COMMENT 'K线内容哈希',
    first_seen_at BIGINT NOT NULL COMMENT '首次写入时间(ms)',
    last_seen_at BIGINT NOT NULL COMMENT '最近写入时间(ms)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(ms)',

    UNIQUE KEY uk_source_symbol_interval_open (source, symbol, interval_val, open_time),
    KEY idx_symbol_interval_open (symbol, interval_val, open_time DESC),
    KEY idx_source_interval_open (source, interval_val, open_time DESC),
    KEY idx_candle_closed_updated (candle_closed, updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='K线权威表';

-- =====================================================
-- K线冲突表（收线后冲突告警）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_kline_conflict (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    source VARCHAR(32) NOT NULL COMMENT '来源: binance 等',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    interval_val VARCHAR(10) NOT NULL COMMENT '周期',
    open_time BIGINT NOT NULL COMMENT '开盘时间(ms)',
    existing_hash CHAR(64) NOT NULL COMMENT '已存权威哈希',
    incoming_hash CHAR(64) NOT NULL COMMENT '新消息哈希',
    existing_close_time BIGINT NOT NULL COMMENT '已存收盘时间(ms)',
    incoming_close_time BIGINT NOT NULL COMMENT '新消息收盘时间(ms)',
    existing_payload_json TEXT NOT NULL COMMENT '已存K线快照JSON',
    incoming_payload_json TEXT NOT NULL COMMENT '新K线快照JSON',
    detected_at BIGINT NOT NULL COMMENT '冲突检测时间(ms)',
    resolved TINYINT NOT NULL DEFAULT 0 COMMENT '是否已处理: 0否1是',
    note VARCHAR(256) DEFAULT NULL COMMENT '备注',

    UNIQUE KEY uk_conflict_fingerprint (source, symbol, interval_val, open_time, incoming_hash),
    KEY idx_conflict_detected (detected_at DESC),
    KEY idx_conflict_symbol_interval (symbol, interval_val, open_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='K线冲突告警表';

-- =====================================================
-- K线回补水位（重启恢复）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_kline_backfill_watermark (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    source VARCHAR(32) NOT NULL COMMENT '来源: binance 等',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    interval_val VARCHAR(10) NOT NULL COMMENT '周期',
    last_closed_open_time BIGINT NOT NULL DEFAULT 0 COMMENT '最近已收线open_time(ms)',
    last_event_time BIGINT NOT NULL DEFAULT 0 COMMENT '最近事件时间(ms)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(ms)',

    UNIQUE KEY uk_source_symbol_interval (source, symbol, interval_val),
    KEY idx_updated_at (updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='K线回补水位表';

-- =====================================================
-- 初始化数据
-- =====================================================

-- 添加示例交易对（可选）
-- INSERT INTO t_ticker_24h (symbol, open_time, close_time, timestamp) VALUES 
-- ('BTCUSDT', 0, 0, 0),
-- ('ETHUSDT', 0, 0, 0),
-- ('SOLUSDT', 0, 0, 0)
-- ON DUPLICATE KEY UPDATE symbol = symbol;
