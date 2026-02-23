-- =====================================================
-- Public Push Core 数据库表结构
-- 数据库: exchange_push
-- 职责: WebSocket连接管理、订阅管理、推送限流
-- =====================================================

CREATE DATABASE IF NOT EXISTS exchange_push 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE exchange_push;

-- =====================================================
-- 1. WebSocket连接表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_ws_connection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    
    -- 连接信息
    connection_id VARCHAR(64) NOT NULL COMMENT '连接ID',
    session_id VARCHAR(64) COMMENT '会话ID',
    
    -- 客户端信息
    client_ip VARCHAR(64) NOT NULL COMMENT '客户端IP',
    user_agent VARCHAR(512) COMMENT 'User Agent',
    
    -- 用户关联（可选，未登录用户为空）
    user_id BIGINT COMMENT '用户ID',
    is_authenticated TINYINT DEFAULT 0 COMMENT '是否已认证: 0-否 1-是',
    
    -- 连接状态
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1=连接中 2=已断开',
    
    -- 统计
    subscribe_count INT DEFAULT 0 COMMENT '订阅数',
    message_sent_count BIGINT DEFAULT 0 COMMENT '发送消息数',
    message_recv_count BIGINT DEFAULT 0 COMMENT '接收消息数',
    bytes_sent BIGINT DEFAULT 0 COMMENT '发送字节数',
    
    -- 限流
    rate_limit_exceeded TINYINT DEFAULT 0 COMMENT '是否超过限流: 0-否 1-是',
    
    -- 时间戳
    connected_at BIGINT NOT NULL COMMENT '连接时间（毫秒）',
    disconnected_at BIGINT COMMENT '断开时间（毫秒）',
    last_ping_at BIGINT COMMENT '最后心跳时间（毫秒）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    UNIQUE KEY uk_connection_id (connection_id),
    KEY idx_user_id (user_id),
    KEY idx_status (status),
    KEY idx_connected_at (connected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WebSocket连接表';

-- =====================================================
-- 2. 订阅记录表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_subscription (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    
    -- 关联信息
    connection_id VARCHAR(64) NOT NULL COMMENT '连接ID',
    user_id BIGINT COMMENT '用户ID',
    
    -- 订阅信息
    channel VARCHAR(64) NOT NULL COMMENT '频道: ticker/depth/trade/kline/markPrice',
    symbol VARCHAR(32) COMMENT '交易对（全局频道为空）',
    params VARCHAR(256) COMMENT '额外参数（如K线周期）',
    
    -- 订阅状态
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1=活跃 0=已取消',
    
    -- 时间戳
    subscribed_at BIGINT NOT NULL COMMENT '订阅时间（毫秒）',
    unsubscribed_at BIGINT COMMENT '取消订阅时间（毫秒）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    KEY idx_connection (connection_id),
    KEY idx_user_id (user_id),
    KEY idx_channel_symbol (channel, symbol),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订阅记录表';

-- =====================================================
-- 3. 推送消息日志表（可选，用于审计）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_push_message_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    
    -- 消息信息
    message_id VARCHAR(64) NOT NULL COMMENT '消息ID',
    channel VARCHAR(64) NOT NULL COMMENT '频道',
    symbol VARCHAR(32) COMMENT '交易对',
    
    -- 消息内容摘要
    message_type VARCHAR(32) NOT NULL COMMENT '消息类型',
    message_size INT NOT NULL DEFAULT 0 COMMENT '消息大小（字节）',
    
    -- 推送统计
    target_connections INT DEFAULT 0 COMMENT '目标连接数',
    success_count INT DEFAULT 0 COMMENT '成功推送数',
    fail_count INT DEFAULT 0 COMMENT '失败数',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    
    UNIQUE KEY uk_message_id (message_id),
    KEY idx_channel_symbol (channel, symbol),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推送消息日志表';

-- =====================================================
-- 4. IP限流记录表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_ip_rate_limit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    
    client_ip VARCHAR(64) NOT NULL COMMENT '客户端IP',
    
    -- 限流统计
    connection_count INT DEFAULT 0 COMMENT '当前连接数',
    request_count INT DEFAULT 0 COMMENT '请求计数',
    blocked TINYINT DEFAULT 0 COMMENT '是否被限流: 0-否 1-是',
    
    -- 时间戳
    window_start BIGINT NOT NULL COMMENT '当前窗口开始时间',
    blocked_until BIGINT COMMENT '限流截止时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    UNIQUE KEY uk_client_ip (client_ip),
    KEY idx_blocked (blocked)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IP限流记录表';

-- =====================================================
-- 5. 推送统计表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_push_statistics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    stat_date INT NOT NULL COMMENT '统计日期(YYYYMMDD)',
    stat_hour INT COMMENT '统计小时(0-23，日统计为空)',
    
    -- 连接统计
    total_connections BIGINT DEFAULT 0 COMMENT '总连接数',
    peak_connections INT DEFAULT 0 COMMENT '峰值连接数',
    auth_connections BIGINT DEFAULT 0 COMMENT '认证连接数',
    
    -- 消息统计
    messages_sent BIGINT DEFAULT 0 COMMENT '发送消息总数',
    messages_dropped BIGINT DEFAULT 0 COMMENT '丢弃消息数',
    bytes_sent BIGINT DEFAULT 0 COMMENT '发送字节数',
    
    -- 频道统计
    ticker_subscribers BIGINT DEFAULT 0 COMMENT 'ticker订阅数',
    depth_subscribers BIGINT DEFAULT 0 COMMENT 'depth订阅数',
    trade_subscribers BIGINT DEFAULT 0 COMMENT 'trade订阅数',
    kline_subscribers BIGINT DEFAULT 0 COMMENT 'kline订阅数',
    
    -- 时间戳
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    UNIQUE KEY uk_date_hour (stat_date, stat_hour),
    KEY idx_stat_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推送统计表';
