-- =====================================================
-- Liquidation Core 数据库表结构
-- 数据库: exchange_liquidation
-- 职责: 强平执行、穿仓处理、保险基金交互
-- =====================================================

CREATE DATABASE IF NOT EXISTS exchange_liquidation 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE exchange_liquidation;

-- =====================================================
-- 1. 强平执行记录表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_liquidation_execution (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    liquidation_id VARCHAR(64) NOT NULL COMMENT '强平ID（全局唯一）',
    
    -- 用户信息
    user_id BIGINT NOT NULL COMMENT '用户ID',
    position_id BIGINT NOT NULL COMMENT '仓位ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 仓位信息
    margin_mode VARCHAR(16) NOT NULL COMMENT '保证金模式: ISOLATED/CROSS',
    position_side TINYINT NOT NULL COMMENT '仓位方向: 1=多, 2=空',
    
    -- 触发信息
    trigger_type VARCHAR(32) NOT NULL COMMENT '触发类型: MARGIN_RATIO/MARK_PRICE/USER_REQUEST',
    trigger_price BIGINT NOT NULL COMMENT '触发价格',
    margin_ratio BIGINT COMMENT '触发时保证金率（万分比）',
    
    -- 订单信息
    order_id BIGINT COMMENT 'OMS订单ID',
    order_type VARCHAR(16) NOT NULL COMMENT '订单类型: MARKET/LIMIT',
    side VARCHAR(8) NOT NULL COMMENT '订单方向: BUY/SELL',
    quantity BIGINT NOT NULL COMMENT '订单数量',
    executed_price BIGINT COMMENT '成交价格',
    executed_qty BIGINT COMMENT '成交数量',
    
    -- 价格信息
    entry_price BIGINT NOT NULL COMMENT '开仓均价',
    bankruptcy_price BIGINT NOT NULL COMMENT '破产价格',
    mark_price BIGINT COMMENT '触发时标记价格',
    liquidation_price BIGINT COMMENT '强平价',
    
    -- 盈亏计算
    realized_pnl BIGINT COMMENT '已实现盈亏',
    bankrupt_loss BIGINT COMMENT '穿仓损失',
    initial_margin BIGINT COMMENT '初始保证金',
    maintenance_margin BIGINT COMMENT '维持保证金',
    
    -- 保险基金
    insurance_cover BIGINT DEFAULT 0 COMMENT '保险基金赔付',
    remaining_loss BIGINT DEFAULT 0 COMMENT '剩余穿仓损失',
    adl_required TINYINT DEFAULT 0 COMMENT '是否需要ADL: 0=否, 1=是',
    
    -- 部分成交支持
    remaining_qty BIGINT DEFAULT 0 COMMENT '剩余仓位数量',
    remaining_order_id BIGINT COMMENT '剩余仓位订单ID',
    partial_pnl BIGINT DEFAULT 0 COMMENT '部分成交累计盈亏',
    partial_bankrupt_loss BIGINT DEFAULT 0 COMMENT '部分成交累计穿仓损失',
    parent_liquidation_id VARCHAR(64) COMMENT '父强平ID（剩余仓位订单关联）',
    
    -- 状态
    status VARCHAR(32) NOT NULL COMMENT '状态: PENDING/SUBMITTED/PARTIALLY_FILLED/FILLED/FAILED/CANCELLED',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    error_msg VARCHAR(512) COMMENT '错误信息',
    
    -- 时间戳
    triggered_at BIGINT NOT NULL COMMENT '触发时间（毫秒）',
    submitted_at BIGINT COMMENT '提交时间（毫秒）',
    filled_at BIGINT COMMENT '成交时间（毫秒）',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    -- 并发控制
    version BIGINT DEFAULT 0 COMMENT '版本号（乐观锁）',
    
    UNIQUE KEY uk_liquidation_id (liquidation_id),
    KEY idx_user_id (user_id),
    KEY idx_position_id (position_id),
    KEY idx_position_created (position_id, created_at),
    KEY idx_symbol_status (symbol, status),
    KEY idx_created_at (created_at),
    KEY idx_parent_liquidation_id (parent_liquidation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='强平执行记录表';

-- =====================================================
-- 2. 强平事件表（用于可靠事件发布）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_liquidation_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    event_id VARCHAR(64) NOT NULL COMMENT '事件ID（全局唯一）',
    liquidation_id VARCHAR(64) NOT NULL COMMENT '强平ID',
    
    -- 事件信息
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型: LIQUIDATION_STARTED/LIQUIDATION_COMPLETED/LIQUIDATION_FAILED',
    topic VARCHAR(128) NOT NULL COMMENT 'Kafka Topic',
    event_data TEXT NOT NULL COMMENT '事件内容JSON',
    
    -- 发送状态
    send_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '发送状态: PENDING/SENT/FAILED',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    max_retry INT DEFAULT 5 COMMENT '最大重试次数',
    next_retry_time BIGINT COMMENT '下次重试时间（毫秒）',
    error_msg VARCHAR(512) COMMENT '错误信息',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    sent_at BIGINT COMMENT '发送成功时间（毫秒）',
    
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_liquidation_id (liquidation_id),
    KEY idx_send_status (send_status, next_retry_time),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='强平事件表';

-- =====================================================
-- 3. 强平审计日志表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_liquidation_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    audit_id VARCHAR(64) NOT NULL COMMENT '审计ID（全局唯一）',
    liquidation_id VARCHAR(64) NOT NULL COMMENT '强平ID',
    
    -- 操作信息
    operation VARCHAR(32) NOT NULL COMMENT '操作类型: CREATE/UPDATE/CANCEL/RETRY/ADL_TRIGGER',
    operator_id BIGINT COMMENT '操作人ID（NULL表示系统自动）',
    operator_type VARCHAR(16) NOT NULL COMMENT '操作类型: AUTO/MANUAL',
    reason VARCHAR(256) COMMENT '操作原因',
    
    -- 数据快照
    before_data TEXT COMMENT '变更前数据JSON',
    after_data TEXT COMMENT '变更后数据JSON',
    
    -- 操作结果
    success TINYINT NOT NULL COMMENT '是否成功: 0=否, 1=是',
    error_msg VARCHAR(512) COMMENT '错误信息',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    
    UNIQUE KEY uk_audit_id (audit_id),
    KEY idx_liquidation_id (liquidation_id),
    KEY idx_created_at (created_at),
    KEY idx_operator (operator_id, operator_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='强平审计日志表';

-- =====================================================
-- 4. 穿仓记录表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_bankruptcy_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    liquidation_id VARCHAR(64) NOT NULL COMMENT '强平记录ID',
    
    -- 用户信息
    user_id BIGINT NOT NULL COMMENT '穿仓用户ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 穿仓信息
    side TINYINT NOT NULL COMMENT '方向: 1=多 2=空',
    bankrupt_price BIGINT NOT NULL COMMENT '破产价格',
    mark_price BIGINT COMMENT '标记价格',
    bankrupt_qty BIGINT NOT NULL COMMENT '穿仓数量',
    bankrupt_loss BIGINT NOT NULL COMMENT '穿仓损失金额',
    
    -- 原始保证金
    original_margin BIGINT COMMENT '原始保证金',
    maintenance_margin BIGINT COMMENT '维持保证金',
    account_equity BIGINT COMMENT '穿仓时账户权益',
    
    -- 处理信息
    handle_type VARCHAR(16) COMMENT '处理方式: INSURANCE/ADL/HYBRID/PENDING',
    insurance_cover BIGINT COMMENT '保险基金赔付金额',
    adl_cover BIGINT COMMENT 'ADL分摊金额',
    uncovered_amount BIGINT COMMENT '未覆盖金额',
    
    -- 状态
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/PROCESSING/COMPLETED/FAILED/PARTIAL',
    fail_reason VARCHAR(512) COMMENT '失败原因',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    
    -- ADL相关
    adl_batch_count INT DEFAULT 0 COMMENT '触发的ADL批次数',
    adl_affected_users INT DEFAULT 0 COMMENT 'ADL影响的用户数',
    adl_execution_ids TEXT COMMENT 'ADL执行记录ID列表',
    
    -- 时间戳
    bankrupt_at BIGINT NOT NULL COMMENT '穿仓发生时间（毫秒）',
    processing_at BIGINT COMMENT '开始处理时间（毫秒）',
    completed_at BIGINT COMMENT '完成时间（毫秒）',
    created_at BIGINT NOT NULL COMMENT '记录创建时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '记录更新时间（毫秒）',
    
    UNIQUE KEY uk_liquidation (liquidation_id),
    KEY idx_user_id (user_id),
    KEY idx_symbol (symbol),
    KEY idx_status (status),
    KEY idx_bankrupt_at (bankrupt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='穿仓记录表';

-- =====================================================
-- 5. 强平配置表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_liquidation_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 强平配置
    liquidation_fee_rate BIGINT DEFAULT 50 COMMENT '强平手续费率（万分比）',
    insurance_fee_rate BIGINT DEFAULT 50 COMMENT '保险基金费率（万分比）',
    
    -- 价格配置
    price_slippage_pct BIGINT DEFAULT 100 COMMENT '价格滑点容忍（万分比，如100=1%）',
    max_liquidation_qty BIGINT COMMENT '单次最大强平数量',
    
    -- 状态
    status TINYINT DEFAULT 1 COMMENT '状态: 1=启用 0=禁用',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_symbol (symbol),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='强平配置表';
