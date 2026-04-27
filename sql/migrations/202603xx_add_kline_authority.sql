-- PACK-04 B5: K线权威化 + 去重 + 冲突告警 + 回补水位

USE exchange_market;

-- =============================
-- 权威K线表
-- =============================
CREATE TABLE IF NOT EXISTS t_kline_authority (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
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
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_symbol_interval_open (source, symbol, interval_val, open_time),
    KEY idx_symbol_interval_open (symbol, interval_val, open_time DESC),
    KEY idx_source_interval_open (source, interval_val, open_time DESC),
    KEY idx_candle_closed_updated (candle_closed, updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='K线权威表';

-- =============================
-- K线冲突记录表
-- =============================
CREATE TABLE IF NOT EXISTS t_kline_conflict (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
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
    PRIMARY KEY (id),
    UNIQUE KEY uk_conflict_fingerprint (source, symbol, interval_val, open_time, incoming_hash),
    KEY idx_conflict_detected (detected_at DESC),
    KEY idx_conflict_symbol_interval (symbol, interval_val, open_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='K线冲突告警表';

-- =============================
-- 回补水位表（重启恢复）
-- =============================
CREATE TABLE IF NOT EXISTS t_kline_backfill_watermark (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    source VARCHAR(32) NOT NULL COMMENT '来源: binance 等',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    interval_val VARCHAR(10) NOT NULL COMMENT '周期',
    last_closed_open_time BIGINT NOT NULL DEFAULT 0 COMMENT '最近已收线open_time(ms)',
    last_event_time BIGINT NOT NULL DEFAULT 0 COMMENT '最近事件时间(ms)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(ms)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_symbol_interval (source, symbol, interval_val),
    KEY idx_updated_at (updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='K线回补水位表';
