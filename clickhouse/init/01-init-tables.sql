-- ============================================
-- ClickHouse K线数据表初始化脚本
-- 此脚本会在容器首次启动时自动执行
-- ============================================

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS exchange_kline;

-- 使用数据库
USE exchange_kline;

-- ============================================
-- K线历史数据表 (生产环境主表)
-- ============================================
CREATE TABLE IF NOT EXISTS kline_data (
    symbol String COMMENT '交易对',
    interval String COMMENT 'K线周期 (1m, 5m, 15m, 1h, 4h, 1d...)',

    -- 时间字段（UTC时区）
    open_time DateTime64(3, 'UTC') COMMENT '开盘时间（毫秒）',
    close_time DateTime64(3, 'UTC') COMMENT '收盘时间（毫秒）',

    -- OHLC价格（8位小数）
    open_price Decimal64(8) COMMENT '开盘价',
    high_price Decimal64(8) COMMENT '最高价',
    low_price Decimal64(8) COMMENT '最低价',
    close_price Decimal64(8) COMMENT '收盘价',

    -- 成交量数据（8位小数）
    volume Decimal64(8) COMMENT '成交量',
    quote_volume Decimal64(8) COMMENT '成交额',
    trade_count UInt32 COMMENT '成交笔数',

    -- Taker买入数据
    taker_buy_volume Decimal64(8) COMMENT 'Taker买入量',
    taker_buy_quote_volume Decimal64(8) COMMENT 'Taker买入额',

    -- 元数据
    is_closed UInt8 COMMENT '是否已收线 (0=未收线, 1=已收线)',
    source String DEFAULT 'binance' COMMENT '数据源',
    created_at DateTime64(3, 'UTC') DEFAULT now64(3) COMMENT '创建时间'
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(open_time)  -- 按月分区
PRIMARY KEY (symbol, interval, open_time)
ORDER BY (symbol, interval, open_time)
SETTINGS index_granularity = 8192
COMMENT 'K线历史数据表 - 已收线数据存储';

-- ============================================
-- K线实时数据表 (未收线K线)
-- ============================================
CREATE TABLE IF NOT EXISTS kline_realtime (
    symbol String COMMENT '交易对',
    interval String COMMENT 'K线周期',

    -- 时间字段
    open_time DateTime64(3, 'UTC') COMMENT '开盘时间',
    close_time DateTime64(3, 'UTC') COMMENT '收盘时间',

    -- OHLC价格
    open_price Decimal64(8) COMMENT '开盘价',
    high_price Decimal64(8) COMMENT '最高价',
    low_price Decimal64(8) COMMENT '最低价',
    close_price Decimal64(8) COMMENT '收盘价',

    -- 成交量数据
    volume Decimal64(8) COMMENT '成交量',
    quote_volume Decimal64(8) COMMENT '成交额',
    trade_count UInt32 COMMENT '成交笔数',

    -- Taker买入数据
    taker_buy_volume Decimal64(8) COMMENT 'Taker买入量',
    taker_buy_quote_volume Decimal64(8) COMMENT 'Taker买入额',

    -- 元数据
    is_closed UInt8 COMMENT '是否已收线',
    source String DEFAULT 'binance' COMMENT '数据源',
    updated_at DateTime64(3, 'UTC') DEFAULT now64(3) COMMENT '更新时间'
)
ENGINE = ReplacingMergeTree(updated_at)  -- 自动去重，保留最新
PARTITION BY toYYYYMM(open_time)
PRIMARY KEY (symbol, interval, open_time)
ORDER BY (symbol, interval, open_time)
SETTINGS index_granularity = 8192
COMMENT 'K线实时数据表 - 未收线数据存储';

-- ============================================
-- 创建索引以加速查询
-- ============================================

-- 时间范围查询索引（幂等，避免容器重启时重复创建失败）
ALTER TABLE kline_data ADD INDEX IF NOT EXISTS idx_time (open_time) TYPE minmax GRANULARITY 1;

-- 周期查询索引（幂等，避免容器重启时重复创建失败）
ALTER TABLE kline_data ADD INDEX IF NOT EXISTS idx_interval (interval) TYPE set(100) GRANULARITY 1;

-- ============================================
-- 初始化完成标记
-- ============================================
-- K线权威表（用于去重/冲突检测/回补水位）
CREATE TABLE IF NOT EXISTS kline_authority (
    source String COMMENT '权威来源，如 binance',
    symbol String COMMENT '交易对',
    interval String COMMENT '周期',
    open_time DateTime64(3, 'UTC') COMMENT '开盘时间',
    close_time DateTime64(3, 'UTC') COMMENT '收盘时间',
    open_price Decimal64(8) COMMENT '开盘价(内部缩放值)',
    high_price Decimal64(8) COMMENT '最高价(内部缩放值)',
    low_price Decimal64(8) COMMENT '最低价(内部缩放值)',
    close_price Decimal64(8) COMMENT '收盘价(内部缩放值)',
    volume Decimal64(8) COMMENT '成交量(内部缩放值)',
    quote_volume Decimal64(8) COMMENT '成交额(内部缩放值)',
    trade_count UInt32 COMMENT '成交笔数',
    taker_buy_volume Decimal64(8) COMMENT '主动买入量(内部缩放值)',
    taker_buy_quote_volume Decimal64(8) COMMENT '主动买入额(内部缩放值)',
    candle_closed UInt8 COMMENT '是否收线(0/1)',
    payload_hash String COMMENT 'OHLCV 内容哈希',
    first_seen_at DateTime64(3, 'UTC') COMMENT '首次写入时间',
    last_seen_at DateTime64(3, 'UTC') COMMENT '最近写入时间',
    updated_at_ms UInt64 COMMENT '版本时间戳(ms)'
)
ENGINE = ReplacingMergeTree(updated_at_ms)
PARTITION BY toYYYYMM(open_time)
ORDER BY (source, symbol, interval, open_time);

-- K线回补水位表（重启恢复用）
CREATE TABLE IF NOT EXISTS kline_backfill_watermark (
    source String,
    symbol String,
    interval String,
    last_closed_open_time_ms UInt64 COMMENT '最近收线开盘时间(ms)',
    last_event_time_ms UInt64 COMMENT '最近事件时间(ms)',
    updated_at_ms UInt64 COMMENT '版本时间戳(ms)'
)
ENGINE = ReplacingMergeTree(updated_at_ms)
ORDER BY (source, symbol, interval);

-- K线冲突审计表（收线后冲突告警）
CREATE TABLE IF NOT EXISTS kline_authority_conflict (
    source String,
    symbol String,
    interval String,
    open_time_ms UInt64,
    existing_hash String,
    incoming_hash String,
    detected_at_ms UInt64
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(toDateTime(detected_at_ms / 1000))
ORDER BY (source, symbol, interval, open_time_ms, detected_at_ms);

SELECT 'ClickHouse K线表初始化完成' as status;
