-- =====================================================
-- TP/SL Core 数据库表结构
-- 数据库: exchange_tpsl
-- 职责: 止盈止损订单管理、触发执行
-- =====================================================

CREATE DATABASE IF NOT EXISTS exchange_tpsl 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE exchange_tpsl;

-- =====================================================
-- 1. 止盈止损订单主表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_tp_sl_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    order_id BIGINT NOT NULL COMMENT 'TP/SL订单ID（全局唯一）',
    
    -- 用户信息
    user_id BIGINT NOT NULL COMMENT '用户ID',
    client_order_id VARCHAR(64) COMMENT '客户端订单ID',
    
    -- 交易对
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 关联信息
    position_id BIGINT COMMENT '关联持仓ID',
    parent_order_id BIGINT COMMENT '关联父订单ID（开仓订单）',
    
    -- 订单类型
    order_type VARCHAR(16) NOT NULL COMMENT '类型: TP/SL/TPSL/TRAILING',
    trigger_type VARCHAR(16) NOT NULL DEFAULT 'MARK' COMMENT '触发类型: MARK/LAST/INDEX',
    
    -- 触发条件
    trigger_price BIGINT NOT NULL COMMENT '触发价格',
    trigger_direction VARCHAR(16) COMMENT '触发方向: ABOVE/BELOW',
    trigger_side VARCHAR(8) NOT NULL COMMENT '要平的仓位方向: LONG/SHORT',
    
    -- 平仓配置
    close_side TINYINT COMMENT '平仓方向: 1=平多 2=平空',
    exec_type VARCHAR(16) NOT NULL COMMENT '执行类型: MARKET/LIMIT',
    exec_price BIGINT COMMENT '执行限价（当exec_type=LIMIT时）',
    quantity BIGINT NOT NULL COMMENT '平仓数量',
    
    -- 移动止损配置
    trailing_offset BIGINT COMMENT '回调偏移量（金额）',
    trailing_percent BIGINT COMMENT '回调比例（如500=5%）',
    trailing_callback_rate BIGINT COMMENT '回调比例',
    trailing_callback_distance BIGINT COMMENT '回调距离',
    trailing_active_price BIGINT COMMENT '激活价格',
    highest_price BIGINT COMMENT '追踪过程中的最高价',
    lowest_price BIGINT COMMENT '追踪过程中的最低价',
    
    -- 状态
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/TRIGGERED/EXECUTED/CANCELLED/EXPIRED',
    trigger_time BIGINT COMMENT '触发时间',
    triggered_time BIGINT COMMENT '触发时间戳',
    triggered_price BIGINT COMMENT '触发价格',
    
    -- 执行结果
    exec_order_id BIGINT COMMENT '生成的平仓订单ID',
    close_order_id BIGINT COMMENT '平仓订单ID',
    exec_result VARCHAR(16) COMMENT '执行结果: SUCCESS/PARTIAL/FAIL',
    exec_qty BIGINT COMMENT '实际执行数量',
    exec_price BIGINT COMMENT '实际执行价格',
    
    -- 过期时间
    expire_time BIGINT COMMENT '过期时间',
    
    -- 时间戳
    create_time BIGINT NOT NULL COMMENT '创建时间戳',
    update_time BIGINT NOT NULL COMMENT '更新时间戳',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_order_id (order_id),
    KEY idx_user_id (user_id),
    KEY idx_symbol (symbol),
    KEY idx_position_id (position_id),
    KEY idx_status (status),
    KEY idx_symbol_status (symbol, status),
    KEY idx_user_client_order (user_id, client_order_id),
    KEY idx_symbol_status_trigger (symbol, status, trigger_price)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='止盈止损订单表';

-- =====================================================
-- 2. TP/SL执行日志表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_tp_sl_exec_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    tp_sl_order_id BIGINT NOT NULL COMMENT 'TP/SL订单ID',
    
    -- 用户信息
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 执行信息
    trigger_price BIGINT NOT NULL COMMENT '触发价格',
    mark_price BIGINT NOT NULL COMMENT '触发时的标记价格',
    exec_type VARCHAR(16) NOT NULL COMMENT '执行类型',
    quantity BIGINT NOT NULL COMMENT '执行数量',
    
    -- 执行结果
    exec_order_id BIGINT COMMENT '生成的平仓订单ID',
    exec_result VARCHAR(16) COMMENT '执行结果: SUCCESS/FAIL/PARTIAL',
    error_msg VARCHAR(512) COMMENT '错误信息',
    
    -- 时间戳
    create_time BIGINT NOT NULL COMMENT '创建时间戳',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    KEY idx_tp_sl_order_id (tp_sl_order_id),
    KEY idx_user_id (user_id),
    KEY idx_created_at (created_at),
    KEY idx_exec_result (exec_result)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TP/SL执行日志表';

-- =====================================================
-- 3. TP/SL触发记录表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_tp_sl_trigger (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    tp_sl_order_id BIGINT NOT NULL COMMENT 'TP/SL订单ID',
    
    -- 触发信息
    trigger_type VARCHAR(16) NOT NULL COMMENT '触发类型: PRICE/TRAILING/EXPIRED',
    trigger_condition VARCHAR(32) NOT NULL COMMENT '触发条件描述',
    
    -- 价格信息
    trigger_price BIGINT NOT NULL COMMENT '触发价格',
    mark_price BIGINT NOT NULL COMMENT '触发时标记价格',
    index_price BIGINT COMMENT '触发时指数价格',
    
    -- 触发状态
    status TINYINT DEFAULT 0 COMMENT '处理状态: 0=待处理 1=已处理 2=处理失败',
    error_msg VARCHAR(512) COMMENT '错误信息',
    
    -- 时间戳
    triggered_at BIGINT NOT NULL COMMENT '触发时间（毫秒）',
    processed_at BIGINT COMMENT '处理时间（毫秒）',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    KEY idx_tp_sl_order_id (tp_sl_order_id),
    KEY idx_status (status),
    KEY idx_triggered_at (triggered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TP/SL触发记录表';

-- =====================================================
-- 4. 用户TP/SL配置表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_user_tpsl_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(32) COMMENT '交易对（为空表示全局配置）',
    
    -- 默认配置
    default_tp_type VARCHAR(16) DEFAULT 'MARKET' COMMENT '默认止盈执行类型',
    default_sl_type VARCHAR(16) DEFAULT 'MARKET' COMMENT '默认止损执行类型',
    default_trigger_type VARCHAR(16) DEFAULT 'MARK' COMMENT '默认触发价格类型',
    
    -- 限制
    max_tp_sl_orders INT DEFAULT 10 COMMENT '最大TP/SL订单数',
    allow_trailing TINYINT DEFAULT 1 COMMENT '是否允许移动止损: 0-否 1-是',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_user_symbol (user_id, symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户TP/SL配置表';
