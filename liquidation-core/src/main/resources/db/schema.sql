-- 强平服务数据库表结构
-- 数据库: exchange_liquidation

-- 强平执行记录表
CREATE TABLE IF NOT EXISTS `t_liquidation_execution` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `liquidation_id` VARCHAR(64) NOT NULL COMMENT '强平ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `position_id` BIGINT NOT NULL COMMENT '仓位ID',
    `symbol` VARCHAR(32) NOT NULL COMMENT '交易对',
    `margin_mode` VARCHAR(16) NOT NULL COMMENT '保证金模式: ISOLATED/CROSS',
    `position_side` TINYINT NOT NULL COMMENT '仓位方向: 1=多, 2=空',
    
    -- 触发信息
    `trigger_type` VARCHAR(32) NOT NULL COMMENT '触发类型: MARGIN_RATIO/MARK_PRICE',
    `trigger_price` BIGINT NOT NULL COMMENT '触发价格',
    `margin_ratio` BIGINT COMMENT '触发时保证金率(万分比)',
    
    -- 订单信息
    `order_id` BIGINT COMMENT 'OMS订单ID',
    `order_type` VARCHAR(16) NOT NULL COMMENT '订单类型: MARKET/LIMIT',
    `side` VARCHAR(8) NOT NULL COMMENT '订单方向: BUY/SELL',
    `quantity` BIGINT NOT NULL COMMENT '订单数量',
    `executed_price` BIGINT COMMENT '成交价格',
    `executed_qty` BIGINT COMMENT '成交数量',
    
    -- 价格信息
    `entry_price` BIGINT NOT NULL COMMENT '开仓均价',
    `bankruptcy_price` BIGINT NOT NULL COMMENT '破产价格',
    `mark_price` BIGINT COMMENT '标记价格',
    
    -- 盈亏计算
    `realized_pnl` BIGINT COMMENT '已实现盈亏',
    `bankrupt_loss` BIGINT COMMENT '穿仓损失',
    `initial_margin` BIGINT COMMENT '初始保证金',
    `maintenance_margin` BIGINT COMMENT '维持保证金',
    
    -- 保险基金
    `insurance_cover` BIGINT DEFAULT 0 COMMENT '保险基金赔付',
    `remaining_loss` BIGINT DEFAULT 0 COMMENT '剩余穿仓损失',
    `adl_required` TINYINT DEFAULT 0 COMMENT '是否需要ADL: 0=否, 1=是',
    
    -- 部分成交支持
    `remaining_qty` BIGINT DEFAULT 0 COMMENT '剩余仓位数量(8位小数)',
    `remaining_order_id` BIGINT COMMENT '剩余仓位订单ID',
    `partial_pnl` BIGINT DEFAULT 0 COMMENT '部分成交累计盈亏(8位小数)',
    `partial_bankrupt_loss` BIGINT DEFAULT 0 COMMENT '部分成交累计穿仓损失(8位小数)',
    `parent_liquidation_id` VARCHAR(64) COMMENT '父强平ID(剩余仓位订单关联)',
    
    -- 状态
    `status` VARCHAR(32) NOT NULL COMMENT '状态: PENDING/SUBMITTED/PARTIALLY_FILLED/FILLED/FAILED',
    `retry_count` INT DEFAULT 0 COMMENT '重试次数',
    `error_msg` VARCHAR(512) COMMENT '错误信息',
    
    -- 时间戳
    `triggered_at` BIGINT NOT NULL COMMENT '触发时间',
    `submitted_at` BIGINT COMMENT '提交时间',
    `filled_at` BIGINT COMMENT '成交时间',
    `created_at` BIGINT NOT NULL COMMENT '创建时间',
    `updated_at` BIGINT NOT NULL COMMENT '更新时间',
    `version` BIGINT DEFAULT 0 COMMENT '版本号（乐观锁）',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_liquidation_id` (`liquidation_id`),
    UNIQUE KEY `uk_position_trigger_time` (`position_id`, `trigger_type`, `triggered_at`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_position_id` (`position_id`),
    KEY `idx_position_created` (`position_id`, `created_at`),  -- 幂等性查询优化
    KEY `idx_position_trigger_created` (`position_id`, `trigger_type`, `created_at`),
    KEY `idx_symbol_status` (`symbol`, `status`),
    KEY `idx_created_at` (`created_at`),
    KEY `idx_parent_liquidation_id` (`parent_liquidation_id`)  -- 剩余仓位订单关联查询
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='强平执行记录表';

-- 本地事件表 (用于保证事件发布的可靠性)
CREATE TABLE IF NOT EXISTS `t_liquidation_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `event_id` VARCHAR(64) NOT NULL COMMENT '事件ID',
    `liquidation_id` VARCHAR(64) NOT NULL COMMENT '强平ID',
    `event_type` VARCHAR(32) NOT NULL COMMENT '事件类型: LIQUIDATION_COMPLETED',
    `topic` VARCHAR(128) NOT NULL COMMENT 'Kafka Topic',
    `event_data` TEXT NOT NULL COMMENT '事件内容JSON',

    -- 发送状态
    `send_status` VARCHAR(16) NOT NULL COMMENT '发送状态: PENDING/SENT/FAILED',
    `retry_count` INT DEFAULT 0 COMMENT '重试次数',
    `max_retry` INT DEFAULT 5 COMMENT '最大重试次数',
    `next_retry_time` BIGINT COMMENT '下次重试时间',
    `error_msg` VARCHAR(512) COMMENT '错误信息',

    -- 时间戳
    `created_at` BIGINT NOT NULL COMMENT '创建时间',
    `updated_at` BIGINT NOT NULL COMMENT '更新时间',
    `sent_at` BIGINT COMMENT '发送成功时间',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_id` (`event_id`),
    KEY `idx_liquidation_id` (`liquidation_id`),
    KEY `idx_send_status` (`send_status`, `next_retry_time`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地事件表';

-- 审计日志表 (用于满足金融监管要求)
CREATE TABLE IF NOT EXISTS `t_liquidation_audit` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `audit_id` VARCHAR(64) NOT NULL COMMENT '审计ID',
    `liquidation_id` VARCHAR(64) NOT NULL COMMENT '强平ID',
    `operation` VARCHAR(32) NOT NULL COMMENT '操作类型: CREATE/UPDATE/CANCEL/RETRY',
    `operator_id` BIGINT COMMENT '操作人ID (NULL表示系统自动)',
    `operator_type` VARCHAR(16) NOT NULL COMMENT '操作类型: AUTO/MANUAL',
    `reason` VARCHAR(256) COMMENT '操作原因',

    -- 数据快照
    `before_data` TEXT COMMENT '变更前数据JSON',
    `after_data` TEXT COMMENT '变更后数据JSON',

    -- 操作结果
    `success` TINYINT NOT NULL COMMENT '是否成功: 0=否, 1=是',
    `error_msg` VARCHAR(512) COMMENT '错误信息',

    -- 时间戳
    `created_at` BIGINT NOT NULL COMMENT '创建时间',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_audit_id` (`audit_id`),
    KEY `idx_liquidation_id` (`liquidation_id`),
    KEY `idx_created_at` (`created_at`),
    KEY `idx_operator` (`operator_id`, `operator_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='强平审计日志表';
