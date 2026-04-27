-- ============================================
-- ClickHouse K 线数据表结构
-- 高性能时序数据存储
-- ============================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS exchange_kline;

-- ============================================
-- K 线数据主表 (MergeTree 引擎)
-- ============================================
CREATE TABLE IF NOT EXISTS exchange_kline.kline_data
(
    symbol String,                    -- 交易对，如 BTCUSDT
    interval String,                  -- 周期，如 1m, 5m, 1h, 1d
    open_time DateTime64(3),          -- 开盘时间（毫秒级）
    close_time DateTime64(3),         -- 收盘时间
    open_price Decimal(32, 8),        -- 开盘价
    high_price Decimal(32, 8),        -- 最高价
    low_price Decimal(32, 8),         -- 最低价
    close_price Decimal(32, 8),       -- 收盘价
    volume Decimal(32, 8),            -- 成交量
    quote_volume Decimal(32, 8),      -- 成交额
    trade_count UInt32,               -- 成交笔数
    taker_buy_volume Decimal(32, 8),  -- 主动买入成交量
    taker_buy_quote_volume Decimal(32, 8), -- 主动买入成交额
    
    -- 分区键：按月份分区
    event_date Date DEFAULT toDate(open_time)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(event_date)
-- 排序键：交易对 + 周期 + 开盘时间
ORDER BY (symbol, interval, open_time)
-- 主键
PRIMARY KEY (symbol, interval, open_time)
-- 设置TTL：数据保留3年
TTL event_date + INTERVAL 3 YEAR
-- 压缩设置
SETTINGS index_granularity = 8192;

-- ============================================
-- 实时 K 线数据表 (ReplacingMergeTree)
-- 用于存储未完成的当前 K 线，支持实时更新
-- ============================================
CREATE TABLE IF NOT EXISTS exchange_kline.kline_realtime
(
    symbol String,
    interval String,
    open_time DateTime64(3),
    close_time DateTime64(3),
    open_price Decimal(32, 8),
    high_price Decimal(32, 8),
    low_price Decimal(32, 8),
    close_price Decimal(32, 8),
    volume Decimal(32, 8),
    quote_volume Decimal(32, 8),
    trade_count UInt32,
    taker_buy_volume Decimal(32, 8),
    taker_buy_quote_volume Decimal(32, 8),
    -- 版本号（毫秒时间戳），用于 ReplacingMergeTree 去重
    -- ClickHouse 24.x 中 now() 为 DateTime，不能直接参与乘法
    version UInt64 DEFAULT toUInt64(toUnixTimestamp64Milli(now64(3))),
    event_date Date DEFAULT toDate(open_time)
)
ENGINE = ReplacingMergeTree(version)
PARTITION BY (symbol, interval)
ORDER BY (symbol, interval, open_time)
PRIMARY KEY (symbol, interval, open_time)
SETTINGS index_granularity = 8192;

-- ============================================
-- K 线聚合表 (AggregatingMergeTree)
-- 用于高性能的聚合查询
-- ============================================
CREATE TABLE IF NOT EXISTS exchange_kline.kline_aggregated
(
    symbol String,
    interval String,
    open_time DateTime64(3),
    
    -- 聚合字段
    open_price AggregateFunction(argMin, Decimal(32, 8), DateTime64(3)),
    high_price AggregateFunction(max, Decimal(32, 8)),
    low_price AggregateFunction(min, Decimal(32, 8)),
    close_price AggregateFunction(argMax, Decimal(32, 8), DateTime64(3)),
    volume AggregateFunction(sum, Decimal(32, 8)),
    quote_volume AggregateFunction(sum, Decimal(32, 8)),
    trade_count AggregateFunction(sum, UInt32),
    
    event_date Date DEFAULT toDate(open_time)
)
ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMM(event_date)
ORDER BY (symbol, interval, open_time)
PRIMARY KEY (symbol, interval, open_time)
SETTINGS index_granularity = 8192;

-- ============================================
-- 物化视图：自动聚合数据
-- ============================================
CREATE MATERIALIZED VIEW IF NOT EXISTS exchange_kline.kline_aggregated_mv
TO exchange_kline.kline_aggregated
AS
SELECT
    symbol,
    interval,
    open_time,
    argMinState(open_price, open_time) as open_price,
    maxState(high_price) as high_price,
    minState(low_price) as low_price,
    argMaxState(close_price, open_time) as close_price,
    sumState(volume) as volume,
    sumState(quote_volume) as quote_volume,
    sumState(trade_count) as trade_count,
    toDate(open_time) as event_date
FROM exchange_kline.kline_data
GROUP BY symbol, interval, open_time, event_date;

-- ============================================
-- 成交明细表（用于重新生成 K 线）
-- ============================================
CREATE TABLE IF NOT EXISTS exchange_kline.trade_data
(
    symbol String,
    trade_id UInt64,
    price Decimal(32, 8),
    quantity Decimal(32, 8),
    quote_quantity Decimal(32, 8),
    trade_time DateTime64(3),
    is_buyer_maker UInt8,  -- 0 或 1
    
    event_date Date DEFAULT toDate(trade_time)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(event_date)
ORDER BY (symbol, trade_time)
PRIMARY KEY (symbol, trade_time)
TTL event_date + INTERVAL 1 MONTH  -- 成交明细保留1个月
SETTINGS index_granularity = 8192;

-- ============================================
-- 创建分布式表（用于集群部署）
-- ============================================
-- 注意：单机部署时可跳过此步骤
-- CREATE TABLE IF NOT EXISTS exchange_kline.kline_data_dist
-- ON CLUSTER '{cluster}'
-- AS exchange_kline.kline_data
-- ENGINE = Distributed('{cluster}', 'exchange_kline', 'kline_data', rand());

-- ============================================
-- K 线权威数据（去重/冲突/恢复）
-- ============================================
CREATE TABLE IF NOT EXISTS exchange_kline.kline_authority
(
    source String,
    symbol String,
    interval String,
    open_time DateTime64(3, 'UTC'),
    close_time DateTime64(3, 'UTC'),
    open_price Decimal64(8),
    high_price Decimal64(8),
    low_price Decimal64(8),
    close_price Decimal64(8),
    volume Decimal64(8),
    quote_volume Decimal64(8),
    trade_count UInt32,
    taker_buy_volume Decimal64(8),
    taker_buy_quote_volume Decimal64(8),
    candle_closed UInt8,
    payload_hash String,
    first_seen_at DateTime64(3, 'UTC'),
    last_seen_at DateTime64(3, 'UTC'),
    updated_at_ms UInt64
)
ENGINE = ReplacingMergeTree(updated_at_ms)
PARTITION BY toYYYYMM(open_time)
ORDER BY (source, symbol, interval, open_time)
PRIMARY KEY (source, symbol, interval, open_time)
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS exchange_kline.kline_backfill_watermark
(
    source String,
    symbol String,
    interval String,
    last_closed_open_time_ms UInt64,
    last_event_time_ms UInt64,
    updated_at_ms UInt64
)
ENGINE = ReplacingMergeTree(updated_at_ms)
ORDER BY (source, symbol, interval)
PRIMARY KEY (source, symbol, interval)
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS exchange_kline.kline_authority_conflict
(
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
ORDER BY (source, symbol, interval, open_time_ms, detected_at_ms)
PRIMARY KEY (source, symbol, interval, open_time_ms, detected_at_ms)
SETTINGS index_granularity = 8192;

-- ============================================
-- 创建视图：便于查询
-- ============================================
CREATE OR REPLACE VIEW exchange_kline.v_kline_latest AS
SELECT
    symbol,
    interval,
    open_time,
    close_time,
    open_price,
    high_price,
    low_price,
    close_price,
    volume,
    quote_volume,
    trade_count,
    taker_buy_volume,
    taker_buy_quote_volume
FROM exchange_kline.kline_realtime
FINAL;  -- 使用 FINAL 确保获取最新版本

-- ============================================
-- 测试数据插入（可选）
-- ============================================
-- INSERT INTO exchange_kline.kline_data
-- SELECT
--     'BTCUSDT' as symbol,
--     '1m' as interval,
--     toDateTime64('2024-01-01 00:00:00', 3) + INTERVAL number MINUTE as open_time,
--     toDateTime64('2024-01-01 00:00:59.999', 3) + INTERVAL number MINUTE as close_time,
--     50000.00 + rand() % 1000 as open_price,
--     51000.00 + rand() % 1000 as high_price,
--     49000.00 + rand() % 1000 as low_price,
--     50500.00 + rand() % 1000 as close_price,
--     10.5 + rand() % 5 as volume,
--     525000.00 + rand() % 50000 as quote_volume,
--     toUInt32(rand() % 100) as trade_count,
--     5.25 + rand() % 3 as taker_buy_volume,
--     262500.00 + rand() % 25000 as taker_buy_quote_volume
-- FROM numbers(100);
