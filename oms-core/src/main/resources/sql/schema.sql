-- =====================================================
-- OMS Core 数据库表结构
-- 数据库: exchange_oms
-- 职责: 订单生命周期管理、订单状态机、订单持久化
-- =====================================================

CREATE DATABASE IF NOT EXISTS exchange_oms 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE exchange_oms;

-- =====================================================
-- 1. 订单主表（订单当前态）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_order (
    id BIGINT NOT NULL COMMENT '订单ID（雪花算法）',
    
    -- 用户与客户端信息
    user_id BIGINT NOT NULL COMMENT '用户ID',
    client_order_id VARCHAR(64) NOT NULL COMMENT '客户端订单ID',
    
    -- 交易对信息
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 订单方向与类型
    side TINYINT NOT NULL COMMENT '买卖方向: 1=BUY 2=SELL',
    type TINYINT NOT NULL COMMENT '订单类型: 1=LIMIT 2=MARKET 3=STOP_LIMIT 4=STOP_MARKET',
    
    -- 价格数量
    price DECIMAL(32,16) NULL COMMENT '价格（限价单必填）',
    quantity DECIMAL(32,16) NOT NULL COMMENT '下单数量',
    filled_quantity DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '已成交数量',
    remaining_quantity DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '剩余未成交数量',
    
    -- 成交汇总
    avg_fill_price DECIMAL(32,16) DEFAULT 0 COMMENT '成交均价',
    total_fill_amount DECIMAL(32,16) DEFAULT 0 COMMENT '总成交金额',
    
    -- 订单状态
    status TINYINT NOT NULL COMMENT '订单状态: 1=NEW 2=PENDING_RISK 3=FROZEN 4=PARTIALLY_FILLED 5=FILLED 6=CANCELED 7=REJECTED 8=EXPIRED',
    time_in_force VARCHAR(16) NOT NULL DEFAULT 'GTC' COMMENT '有效期类型: GTC/IOC/FOK',
    
    -- 风控与资金状态
    risk_check_status TINYINT NOT NULL DEFAULT 0 COMMENT '风控检查状态: 0=未检查 1=通过 2=拒绝',
    freeze_status TINYINT NOT NULL DEFAULT 0 COMMENT '冻结状态: 0=未冻结 1=已冻结 2=已解冻',
    
    -- 杠杆与保证金
    leverage INT DEFAULT 1 COMMENT '杠杆倍数',
    margin_mode VARCHAR(16) DEFAULT 'CROSS' COMMENT '保证金模式: CROSS/ISOLATED',
    
    -- 止盈止损配置
    tp_trigger_price DECIMAL(32,16) COMMENT '止盈触发价格',
    sl_trigger_price DECIMAL(32,16) COMMENT '止损触发价格',
    
    -- 触发订单配置
    trigger_price DECIMAL(32,16) COMMENT '触发价格（条件单）',
    triggered_order_id BIGINT COMMENT '触发的订单ID（条件单触发后生成）',
    
    -- 手续费
    maker_fee_rate DECIMAL(16,8) COMMENT 'Maker费率',
    taker_fee_rate DECIMAL(16,8) COMMENT 'Taker费率',
    fee_paid DECIMAL(32,16) DEFAULT 0 COMMENT '已支付手续费',
    
    -- 来源与追踪
    source VARCHAR(32) DEFAULT 'API' COMMENT '订单来源: API/WEB/APP/SYSTEM',
    trace_id VARCHAR(64) COMMENT '追踪ID',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
    filled_at BIGINT COMMENT '完全成交时间',
    canceled_at BIGINT COMMENT '撤单时间',
    expired_at BIGINT COMMENT '过期时间',
    
    -- 并发控制
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_client (user_id, client_order_id),
    KEY idx_user_symbol (user_id, symbol),
    KEY idx_symbol_status (symbol, status),
    KEY idx_status (status),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表';

-- =====================================================
-- 2. 订单事件表（事件流）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_order_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '事件ID',
    
    -- 关联信息
    order_id BIGINT NOT NULL COMMENT '订单ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 事件信息
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型: CREATE/RISK_CHECK/FREEZE/MATCH/PARTIAL_FILL/FILL/CANCEL/REJECT',
    event_source VARCHAR(32) NOT NULL COMMENT '事件来源: OMS/RISK/MATCH/LEDGER',
    
    -- 事件数据
    event_payload JSON NOT NULL COMMENT '事件数据JSON',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    
    PRIMARY KEY (id),
    KEY idx_order_id (order_id),
    KEY idx_symbol (symbol),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单事件表';

-- =====================================================
-- 3. 订单状态日志表（审计链）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_order_state_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    
    -- 关联信息
    order_id BIGINT NOT NULL COMMENT '订单ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    
    -- 状态变更
    from_status TINYINT NOT NULL COMMENT '原状态',
    to_status TINYINT NOT NULL COMMENT '新状态',
    
    -- 变更原因
    reason_code VARCHAR(32) NOT NULL COMMENT '原因码',
    reason_msg VARCHAR(255) NULL COMMENT '原因描述',
    
    -- 操作信息
    operator VARCHAR(32) NOT NULL COMMENT '操作者: USER/SYSTEM/MATCH',
    operator_id BIGINT COMMENT '操作者ID',
    trace_id VARCHAR(64) NULL COMMENT '追踪ID',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    
    PRIMARY KEY (id),
    KEY idx_order_id (order_id),
    KEY idx_user_id (user_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单状态日志表';

-- =====================================================
-- 4. 成交记录表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_trade (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '成交ID',
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
    KEY idx_symbol_time (symbol, match_time),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成交记录表';

-- =====================================================
-- 5. 幂等Key表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_idempotent_key (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    
    -- 幂等信息
    user_id BIGINT NOT NULL COMMENT '用户ID',
    idem_key VARCHAR(64) NOT NULL COMMENT '幂等key',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    request_hash VARCHAR(64) NOT NULL COMMENT '请求hash',
    
    -- 有效期
    expire_at BIGINT NOT NULL COMMENT '过期时间（毫秒）',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_key (user_id, idem_key),
    KEY idx_expire_at (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='幂等key表';

-- =====================================================
-- 6. 条件订单表（止盈止损/触发单）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_conditional_order (
    id BIGINT NOT NULL COMMENT '条件单ID',
    
    -- 用户信息
    user_id BIGINT NOT NULL COMMENT '用户ID',
    client_order_id VARCHAR(64) NOT NULL COMMENT '客户端订单ID',
    
    -- 交易对
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 条件单类型
    condition_type VARCHAR(16) NOT NULL COMMENT '条件类型: STOP_LIMIT/STOP_MARKET/TRAILING_STOP',
    
    -- 触发条件
    trigger_price DECIMAL(32,16) NOT NULL COMMENT '触发价格',
    trigger_operator VARCHAR(8) NOT NULL COMMENT '触发操作符: >=/<=',
    
    -- 触发后生成的订单
    triggered_order_type TINYINT NOT NULL COMMENT '触发后订单类型: 1=LIMIT 2=MARKET',
    triggered_order_side TINYINT NOT NULL COMMENT '触发后订单方向: 1=BUY 2=SELL',
    triggered_order_price DECIMAL(32,16) COMMENT '触发后订单价格（限价单）',
    triggered_order_qty DECIMAL(32,16) NOT NULL COMMENT '触发后订单数量',
    
    -- 追踪止损配置
    trailing_distance DECIMAL(32,16) COMMENT '追踪距离',
    trailing_percent DECIMAL(8,4) COMMENT '追踪百分比',
    highest_price DECIMAL(32,16) COMMENT '追踪过程中的最高价',
    lowest_price DECIMAL(32,16) COMMENT '追踪过程中的最低价',
    
    -- 状态
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1=ACTIVE 2=TRIGGERED 3=CANCELLED 4=EXPIRED',
    
    -- 关联
    triggered_order_id BIGINT COMMENT '已触发的订单ID',
    triggered_at BIGINT COMMENT '触发时间',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    expire_at BIGINT COMMENT '过期时间（毫秒）',
    
    -- 并发控制
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_client (user_id, client_order_id),
    KEY idx_user_symbol (user_id, symbol),
    KEY idx_status (status),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='条件订单表';

-- =====================================================
-- 初始化数据
-- =====================================================
