-- =====================================================
-- 止盈止损订单系统 SQL Schema
-- =====================================================

-- TP/SL订单表
CREATE TABLE IF NOT EXISTS t_tp_sl_order (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id            BIGINT NOT NULL COMMENT 'TP/SL订单ID',
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 关联信息
    position_id         BIGINT COMMENT '关联持仓ID',
    parent_order_id     BIGINT COMMENT '关联父订单ID(开仓订单)',
    
    -- 订单类型
    order_type          VARCHAR(16) NOT NULL COMMENT '类型: TP/SL/TPSL/TRAILING',
    trigger_type        VARCHAR(16) NOT NULL DEFAULT 'MARK' COMMENT '触发类型: MARK/LAST/INDEX',
    
    -- 触发条件
    trigger_price       BIGINT NOT NULL COMMENT '触发价格',
    trigger_side        VARCHAR(8) NOT NULL COMMENT '触发方向: LONG/SHORT(要平的仓位)',
    
    -- 执行配置
    exec_type           VARCHAR(16) NOT NULL COMMENT '执行类型: MARKET/LIMIT',
    exec_price          BIGINT COMMENT '执行限价(当exec_type=LIMIT时)',
    quantity            BIGINT NOT NULL COMMENT '平仓数量',
    
    -- 移动止损配置
    trailing_offset     BIGINT COMMENT '回调偏移量(金额)',
    trailing_percent    BIGINT COMMENT '回调比例(如500=5%)',
    highest_price       BIGINT COMMENT '最高/低价(用于计算回调)',
    
    -- 状态
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/TRIGGERED/EXECUTED/CANCELLED/EXPIRED',
    trigger_time        BIGINT COMMENT '触发时间',
    exec_order_id       BIGINT COMMENT '生成的平仓订单ID',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_order_id (order_id),
    KEY idx_user_id (user_id),
    KEY idx_symbol (symbol),
    KEY idx_position_id (position_id),
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='止盈止损订单表';

-- TP/SL执行日志表
CREATE TABLE IF NOT EXISTS t_tp_sl_exec_log (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    tp_sl_order_id      BIGINT NOT NULL COMMENT 'TP/SL订单ID',
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    
    trigger_price       BIGINT NOT NULL COMMENT '触发价格',
    mark_price          BIGINT NOT NULL COMMENT '触发时的标记价格',
    exec_type           VARCHAR(16) NOT NULL COMMENT '执行类型',
    quantity            BIGINT NOT NULL COMMENT '执行数量',
    
    exec_order_id       BIGINT COMMENT '生成的平仓订单ID',
    exec_result         VARCHAR(16) COMMENT '执行结果: SUCCESS/FAIL',
    error_msg           VARCHAR(512) COMMENT '错误信息',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    KEY idx_tp_sl_order_id (tp_sl_order_id),
    KEY idx_user_id (user_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB COMMENT='TP/SL执行日志表';
