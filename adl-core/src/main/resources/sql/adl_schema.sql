-- =====================================================
-- ADL自动减仓系统 - 数据库表结构
-- =====================================================

-- 1. ADL排名队列表
CREATE TABLE IF NOT EXISTS `adl_ranking_queue` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `symbol` VARCHAR(32) NOT NULL COMMENT '交易对',
    `position_id` BIGINT NOT NULL COMMENT '仓位ID',

    -- 仓位信息
    `side` VARCHAR(8) NOT NULL COMMENT '方向: LONG/SHORT',
    `position_size` DECIMAL(30, 8) NOT NULL COMMENT '持仓数量',
    `entry_price` DECIMAL(30, 8) NOT NULL COMMENT '开仓均价',
    `mark_price` DECIMAL(30, 8) NOT NULL COMMENT '标记价格',

    -- 保证金信息
    `margin_balance` DECIMAL(30, 8) NOT NULL COMMENT '保证金余额',
    `maintenance_margin` DECIMAL(30, 8) NOT NULL COMMENT '维持保证金',
    `effective_leverage` DECIMAL(10, 2) NOT NULL COMMENT '有效杠杆',

    -- 盈亏信息
    `unrealized_pnl` DECIMAL(30, 8) NOT NULL COMMENT '未实现盈亏',
    `pnl_ratio` DECIMAL(10, 4) NOT NULL COMMENT '盈亏比例',

    -- ADL排名信息
    `adl_score` DECIMAL(30, 8) NOT NULL COMMENT 'ADL得分',
    `adl_rank` INT NOT NULL COMMENT 'ADL排名',
    `risk_level` INT NOT NULL COMMENT '风险等级(1-5)',

    -- 时间戳
    `position_created_at` BIGINT NOT NULL COMMENT '持仓创建时间',
    `rank_updated_at` BIGINT NOT NULL COMMENT '排名更新时间',
    `created_at` BIGINT NOT NULL COMMENT '记录创建时间',
    `updated_at` BIGINT NOT NULL COMMENT '记录更新时间',

    UNIQUE KEY `uk_position` (`position_id`),
    KEY `idx_symbol_side_score` (`symbol`, `side`, `adl_score` DESC),
    KEY `idx_user_symbol` (`user_id`, `symbol`),
    KEY `idx_rank_updated` (`rank_updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADL排名队列表';

-- 2. ADL执行记录表
CREATE TABLE IF NOT EXISTS `adl_execution` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    `adl_execution_id` VARCHAR(64) NOT NULL COMMENT 'ADL执行ID',
    `liquidation_id` VARCHAR(64) NOT NULL COMMENT '关联的强平事件ID',
    `trade_id` VARCHAR(64) COMMENT '关联的成交ID',

    -- 交易对
    `symbol` VARCHAR(32) NOT NULL COMMENT '交易对',

    -- 被ADL方（盈利方）
    `target_user_id` BIGINT NOT NULL COMMENT '被ADL用户ID',
    `target_position_id` BIGINT NOT NULL COMMENT '被ADL仓位ID',
    `target_side` VARCHAR(8) NOT NULL COMMENT '被ADL方向',
    `target_position_size_before` DECIMAL(30, 8) NOT NULL COMMENT '被ADL方原持仓数量',
    `target_position_size_after` DECIMAL(30, 8) NOT NULL COMMENT '被ADL方ADL后持仓数量',
    `target_unrealized_pnl_before` DECIMAL(30, 8) COMMENT '被ADL方原未实现盈亏',
    `target_realized_pnl_before` DECIMAL(30, 8) COMMENT '被ADL方原已实现盈亏',
    `target_realized_pnl_after` DECIMAL(30, 8) COMMENT '被ADL方ADL后已实现盈亏',
    `target_pnl_change` DECIMAL(30, 8) COMMENT '被ADL方盈亏变化',
    `target_adl_rank` INT COMMENT '被ADL方ADL排名',
    `target_adl_score` DECIMAL(30, 8) COMMENT '被ADL方ADL得分',

    -- 触发ADL方（被强平方）
    `source_user_id` BIGINT NOT NULL COMMENT '触发ADL用户ID',
    `source_side` VARCHAR(8) NOT NULL COMMENT '触发ADL方向',
    `source_position_size_before` DECIMAL(30, 8) COMMENT '触发ADL方原持仓数量',
    `source_remaining_size` DECIMAL(30, 8) COMMENT '触发ADL方剩余未平仓数量',

    -- ADL执行详情
    `adl_price` DECIMAL(30, 8) NOT NULL COMMENT 'ADL执行价格',
    `adl_qty` DECIMAL(30, 8) NOT NULL COMMENT 'ADL执行数量',
    `adl_value` DECIMAL(30, 8) NOT NULL COMMENT 'ADL执行金额',
    `is_fully_closed` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否完全平仓',
    `execution_sequence` INT NOT NULL DEFAULT 1 COMMENT '执行顺序',

    -- 保险基金相关
    `insurance_fund_involved` BOOLEAN DEFAULT FALSE COMMENT '是否涉及保险基金',
    `insurance_fund_compensation` DECIMAL(30, 8) COMMENT '保险基金赔付金额',
    `insurance_fund_balance_before` DECIMAL(30, 8) COMMENT 'ADL前保险基金余额',
    `insurance_fund_balance_after` DECIMAL(30, 8) COMMENT 'ADL后保险基金余额',

    -- 状态
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/PROCESSING/SUCCESS/FAILED/ROLLED_BACK',
    `fail_reason` VARCHAR(512) COMMENT '失败原因',
    `retry_count` INT DEFAULT 0 COMMENT '重试次数',

    -- 时间戳
    `triggered_at` BIGINT NOT NULL COMMENT '触发时间',
    `executed_at` BIGINT NOT NULL COMMENT '执行时间',
    `completed_at` BIGINT COMMENT '完成时间',
    `created_at` BIGINT NOT NULL COMMENT '记录创建时间',
    `updated_at` BIGINT NOT NULL COMMENT '记录更新时间',

    -- 幂等性
    `biz_seq` VARCHAR(128) NOT NULL COMMENT '业务序列号',

    UNIQUE KEY `uk_adl_execution_id` (`adl_execution_id`),
    UNIQUE KEY `uk_biz_seq` (`biz_seq`),
    KEY `idx_target_user` (`target_user_id`, `symbol`),
    KEY `idx_source_user` (`source_user_id`),
    KEY `idx_liquidation` (`liquidation_id`),
    KEY `idx_symbol_time` (`symbol`, `executed_at`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ADL执行记录表';

-- 3. 保险基金表
CREATE TABLE IF NOT EXISTS `insurance_fund` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    `symbol` VARCHAR(32) COMMENT '交易对(全局则为空)',
    `currency` VARCHAR(16) NOT NULL COMMENT '币种',

    -- 余额
    `balance` DECIMAL(30, 8) NOT NULL DEFAULT 0 COMMENT '当前余额',
    `available_balance` DECIMAL(30, 8) NOT NULL DEFAULT 0 COMMENT '可用余额',
    `frozen_amount` DECIMAL(30, 8) NOT NULL DEFAULT 0 COMMENT '冻结金额',

    -- 累计收支
    `total_income` DECIMAL(30, 8) NOT NULL DEFAULT 0 COMMENT '累计收入',
    `total_expense` DECIMAL(30, 8) NOT NULL DEFAULT 0 COMMENT '累计支出',
    `total_liquidation_fee_income` DECIMAL(30, 8) NOT NULL DEFAULT 0 COMMENT '累计强平手续费收入',
    `total_liquidation_profit_injection` DECIMAL(30, 8) NOT NULL DEFAULT 0 COMMENT '累计强平盈利注入',
    `total_debt_loss_expense` DECIMAL(30, 8) NOT NULL DEFAULT 0 COMMENT '累计穿仓赔付支出',
    `total_adl_compensation` DECIMAL(30, 8) NOT NULL DEFAULT 0 COMMENT '累计ADL相关赔付',

    -- 统计
    `today_income` DECIMAL(30, 8) NOT NULL DEFAULT 0 COMMENT '今日收入',
    `today_expense` DECIMAL(30, 8) NOT NULL DEFAULT 0 COMMENT '今日支出',
    `today_date` INT NOT NULL COMMENT '今日日期(yyyyMMdd)',
    `max_balance` DECIMAL(30, 8) NOT NULL DEFAULT 0 COMMENT '历史最高余额',
    `min_balance` DECIMAL(30, 8) NOT NULL DEFAULT 0 COMMENT '历史最低余额',

    -- 阈值配置
    `safe_threshold` DECIMAL(30, 8) NOT NULL COMMENT '安全阈值',
    `warning_threshold` DECIMAL(30, 8) NOT NULL COMMENT '警告阈值',
    `danger_threshold` DECIMAL(30, 8) NOT NULL COMMENT '危险阈值',
    `status` VARCHAR(16) NOT NULL DEFAULT 'SAFE' COMMENT '状态: SAFE/WARNING/DANGER',

    -- 元信息
    `version` INT NOT NULL DEFAULT 0 COMMENT '版本号(乐观锁)',
    `last_operation_type` VARCHAR(32) COMMENT '最后操作类型',
    `last_operation_amount` DECIMAL(30, 8) COMMENT '最后操作金额',
    `last_operation_at` BIGINT COMMENT '最后操作时间',
    `last_operation_ref_id` VARCHAR(64) COMMENT '最后操作关联ID',

    -- 时间戳
    `created_at` BIGINT NOT NULL COMMENT '记录创建时间',
    `updated_at` BIGINT NOT NULL COMMENT '记录更新时间',

    UNIQUE KEY `uk_symbol_currency` (`symbol`, `currency`),
    KEY `idx_status` (`status`),
    KEY `idx_balance` (`balance`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='保险基金表';

-- 4. 保险基金流水表
CREATE TABLE IF NOT EXISTS `insurance_fund_log` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    `symbol` VARCHAR(32) COMMENT '交易对',
    `currency` VARCHAR(16) NOT NULL COMMENT '币种',

    -- 变动信息
    `change_type` VARCHAR(32) NOT NULL COMMENT '变动类型',
    `amount` DECIMAL(30, 8) NOT NULL COMMENT '变动金额(正=收入,负=支出)',
    `balance_before` DECIMAL(30, 8) NOT NULL COMMENT '变动前余额',
    `balance_after` DECIMAL(30, 8) NOT NULL COMMENT '变动后余额',

    -- 关联信息
    `ref_id` VARCHAR(64) COMMENT '关联ID(强平ID等)',
    `ref_type` VARCHAR(32) COMMENT '关联类型',
    `ref_user_id` BIGINT COMMENT '关联用户ID',
    `description` VARCHAR(512) COMMENT '说明',

    -- 操作信息
    `operator_id` BIGINT COMMENT '操作人ID',
    `operator_name` VARCHAR(64) COMMENT '操作人姓名',
    `operator_ip` VARCHAR(64) COMMENT '操作IP',

    -- 时间戳
    `created_at` BIGINT NOT NULL COMMENT '流水创建时间',

    KEY `idx_symbol` (`symbol`),
    KEY `idx_type` (`change_type`),
    KEY `idx_ref_id` (`ref_id`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='保险基金流水表';

-- 5. 穿仓记录表
CREATE TABLE IF NOT EXISTS `bankruptcy_record` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    `liquidation_id` VARCHAR(64) NOT NULL COMMENT '强平记录ID',
    `user_id` BIGINT NOT NULL COMMENT '穿仓用户ID',
    `symbol` VARCHAR(32) NOT NULL COMMENT '交易对',

    -- 穿仓信息
    `side` VARCHAR(8) NOT NULL COMMENT '方向',
    `bankrupt_price` DECIMAL(30, 8) NOT NULL COMMENT '破产价格',
    `mark_price` DECIMAL(30, 8) COMMENT '标记价格',
    `bankrupt_qty` DECIMAL(30, 8) NOT NULL COMMENT '穿仓数量',
    `bankrupt_loss` DECIMAL(30, 8) NOT NULL COMMENT '穿仓损失金额',
    `original_margin` DECIMAL(30, 8) COMMENT '原始保证金',
    `maintenance_margin` DECIMAL(30, 8) COMMENT '维持保证金',
    `account_equity` DECIMAL(30, 8) COMMENT '穿仓时账户权益',

    -- 处理信息
    `handle_type` VARCHAR(16) COMMENT '处理方式: INSURANCE/ADL/HYBRID/PENDING',
    `insurance_cover` DECIMAL(30, 8) COMMENT '保险基金赔付金额',
    `adl_cover` DECIMAL(30, 8) COMMENT 'ADL分摊金额',
    `uncovered_amount` DECIMAL(30, 8) COMMENT '未覆盖金额',

    -- 状态
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/PROCESSING/COMPLETED/FAILED/PARTIAL',
    `fail_reason` VARCHAR(512) COMMENT '失败原因',
    `retry_count` INT DEFAULT 0 COMMENT '重试次数',

    -- ADL相关
    `adl_batch_count` INT DEFAULT 0 COMMENT '触发的ADL批次数',
    `adl_affected_users` INT DEFAULT 0 COMMENT 'ADL影响的用户数',
    `adl_execution_ids` TEXT COMMENT 'ADL执行记录ID列表',

    -- 时间戳
    `bankrupt_at` BIGINT NOT NULL COMMENT '穿仓发生时间',
    `processing_at` BIGINT COMMENT '开始处理时间',
    `completed_at` BIGINT COMMENT '完成时间',
    `created_at` BIGINT NOT NULL COMMENT '记录创建时间',
    `updated_at` BIGINT NOT NULL COMMENT '记录更新时间',

    UNIQUE KEY `uk_liquidation` (`liquidation_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_symbol` (`symbol`),
    KEY `idx_status` (`status`),
    KEY `idx_bankrupt_at` (`bankrupt_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='穿仓记录表';

-- =====================================================
-- 初始化数据
-- =====================================================

-- 初始化保险基金（示例）
INSERT INTO `insurance_fund` (
    `symbol`, `currency`, `balance`, `available_balance`, `frozen_amount`,
    `total_income`, `total_expense`, `total_liquidation_fee_income`,
    `total_liquidation_profit_injection`, `total_debt_loss_expense`, `total_adl_compensation`,
    `today_income`, `today_expense`, `today_date`,
    `max_balance`, `min_balance`,
    `safe_threshold`, `warning_threshold`, `danger_threshold`, `status`,
    `version`, `created_at`, `updated_at`
) VALUES
(
    'BTCUSDT', 'USDT', 1000000.00000000, 1000000.00000000, 0.00000000,
    1000000.00000000, 0.00000000, 0.00000000,
    0.00000000, 0.00000000, 0.00000000,
    0.00000000, 0.00000000, 20260218,
    1000000.00000000, 1000000.00000000,
    1000000.00000000, 500000.00000000, 100000.00000000, 'SAFE',
    0, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000
)
ON DUPLICATE KEY UPDATE `updated_at` = VALUES(`updated_at`);
