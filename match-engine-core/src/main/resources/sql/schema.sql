-- =====================================================
-- Match Engine Core 数据库表结构
-- 数据库: exchange_match
-- 职责: 撮合引擎、WAL日志、OrderBook快照
-- =====================================================

CREATE DATABASE IF NOT EXISTS exchange_match 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE exchange_match;

-- =====================================================
-- 1. 撮合序列号表（每个symbol独立序列）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_match_sequence (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    current_seq BIGINT NOT NULL DEFAULT 0 COMMENT '当前序列号',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    PRIMARY KEY (id),
    UNIQUE KEY uk_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='撮合序列号表';

-- =====================================================
-- 2. 成交记录表（与OMS的t_trade冗余存储，用于撮合侧对账）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_match_trade (
    id BIGINT NOT NULL COMMENT '成交ID',
    trade_id VARCHAR(64) NOT NULL COMMENT '全局成交ID',
    
    -- 买卖双方
    buy_order_id BIGINT NOT NULL COMMENT '买方订单ID',
    sell_order_id BIGINT NOT NULL COMMENT '卖方订单ID',
    buy_user_id BIGINT NOT NULL COMMENT '买方用户ID',
    sell_user_id BIGINT NOT NULL COMMENT '卖方用户ID',
    
    -- 成交信息
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    price DECIMAL(32,16) NOT NULL COMMENT '成交价格',
    quantity DECIMAL(32,16) NOT NULL COMMENT '成交数量',
    amount DECIMAL(32,16) NOT NULL COMMENT '成交金额',
    
    -- 成交类型
    side TINYINT NOT NULL COMMENT '主动成交方向: 1=BUY 2=SELL',
    trade_type VARCHAR(16) DEFAULT 'NORMAL' COMMENT '成交类型: NORMAL/LIQUIDATION/ADL',
    
    -- 撮合信息
    match_sequence BIGINT NOT NULL COMMENT '撮合序号',
    match_time BIGINT NOT NULL COMMENT '撮合时间（毫秒）',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    
    PRIMARY KEY (id),
    UNIQUE KEY uk_trade_id (trade_id),
    KEY idx_buy_order (buy_order_id),
    KEY idx_sell_order (sell_order_id),
    KEY idx_symbol_seq (symbol, match_sequence),
    KEY idx_symbol_time (symbol, match_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='撮合成交记录表';

-- =====================================================
-- 3. OrderBook快照表（用于故障恢复）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_orderbook_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    
    -- 快照信息
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    last_sequence BIGINT NOT NULL COMMENT '最后撮合序号',
    
    -- 深度数据（JSON格式存储）
    bids_json TEXT NOT NULL COMMENT '买盘JSON [[price, qty], ...]',
    asks_json TEXT NOT NULL COMMENT '卖盘JSON [[price, qty], ...]',
    
    -- 统计
    bid_depth_levels INT NOT NULL DEFAULT 0 COMMENT '买盘深度层数',
    ask_depth_levels INT NOT NULL DEFAULT 0 COMMENT '卖盘深度层数',
    bid_total_qty DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '买盘总数量',
    ask_total_qty DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '卖盘总数量',
    
    -- 时间戳
    snapshot_time BIGINT NOT NULL COMMENT '快照时间（毫秒）',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    
    PRIMARY KEY (id),
    KEY idx_symbol_time (symbol, snapshot_time DESC),
    KEY idx_snapshot_time (snapshot_time DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OrderBook快照表';

-- =====================================================
-- 4. 内存订单表（撮合中的订单）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_match_order (
    id BIGINT NOT NULL COMMENT '订单ID',
    
    -- 用户信息
    user_id BIGINT NOT NULL COMMENT '用户ID',
    
    -- 订单信息
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    side TINYINT NOT NULL COMMENT '方向: 1=BUY 2=SELL',
    type TINYINT NOT NULL COMMENT '类型: 1=LIMIT 2=MARKET',
    
    -- 价格数量
    price DECIMAL(32,16) COMMENT '价格（限价单）',
    quantity DECIMAL(32,16) NOT NULL COMMENT '下单数量',
    filled_quantity DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '已成交数量',
    remaining_quantity DECIMAL(32,16) NOT NULL COMMENT '剩余数量',
    
    -- 状态
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1=PENDING 2=PARTIAL 3=FILLED 4=CANCELLED',
    
    -- 撮合信息
    sequence BIGINT COMMENT '进入OrderBook的序号',
    entry_time BIGINT NOT NULL COMMENT '进入时间（毫秒）',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_symbol_side (symbol, side),
    KEY idx_symbol_price (symbol, side, price),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内存订单表';

-- =====================================================
-- 5. 撮合统计表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_match_statistics (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    stat_date INT NOT NULL COMMENT '统计日期(YYYYMMDD)',
    
    -- 撮合统计
    total_orders INT NOT NULL DEFAULT 0 COMMENT '总订单数',
    total_trades INT NOT NULL DEFAULT 0 COMMENT '总成交笔数',
    total_volume DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '总成交量',
    total_amount DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '总成交金额',
    
    -- 性能统计
    avg_match_latency_ms INT COMMENT '平均撮合延迟(ms)',
    max_match_latency_ms INT COMMENT '最大撮合延迟(ms)',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    PRIMARY KEY (id),
    UNIQUE KEY uk_symbol_date (symbol, stat_date),
    KEY idx_stat_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='撮合统计表';

-- =====================================================
-- 6. WAL日志索引表（用于快速定位WAL文件）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_wal_index (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    
    -- WAL文件信息
    wal_file_name VARCHAR(128) NOT NULL COMMENT 'WAL文件名',
    start_sequence BIGINT NOT NULL COMMENT '起始序列号',
    end_sequence BIGINT NOT NULL COMMENT '结束序列号',
    start_time BIGINT NOT NULL COMMENT '起始时间（毫秒）',
    end_time BIGINT NOT NULL COMMENT '结束时间（毫秒）',
    
    -- 统计
    entry_count INT NOT NULL DEFAULT 0 COMMENT '条目数',
    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
    
    -- 状态
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1=ACTIVE 2=ARCHIVED 3=DELETED',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    archived_at BIGINT COMMENT '归档时间（毫秒）',
    
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_name (wal_file_name),
    KEY idx_start_seq (start_sequence),
    KEY idx_end_seq (end_sequence),
    KEY idx_time_range (start_time, end_time),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WAL日志索引表';

-- =====================================================
-- 初始化数据
-- =====================================================
INSERT INTO t_match_sequence (symbol, current_seq, created_at, updated_at) VALUES
('BTCUSDT', 0, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
('ETHUSDT', 0, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
('SOLUSDT', 0, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000)
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);
