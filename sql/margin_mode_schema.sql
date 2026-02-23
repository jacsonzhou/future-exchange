-- ============================================
-- Margin Mode Core 数据库 Schema
-- 全仓/逐仓保证金模式管理
-- 对标：Binance / OKX / Bybit级别
-- ============================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS exchange_margin 
    DEFAULT CHARACTER SET utf8mb4 
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE exchange_margin;

-- ============================================
-- 1. 仓位保证金详情表
-- ============================================
DROP TABLE IF EXISTS t_position_margin_detail;
CREATE TABLE t_position_margin_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    position_id BIGINT NOT NULL COMMENT '仓位ID（全局唯一）',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对（如：BTCUSDT）',
    side TINYINT NOT NULL COMMENT '持仓方向：1=多头(LONG), 2=空头(SHORT)',
    
    -- 保证金模式
    margin_mode VARCHAR(16) NOT NULL DEFAULT 'CROSS' COMMENT '保证金模式：CROSS=全仓, ISOLATED=逐仓',
    leverage INT NOT NULL DEFAULT 10 COMMENT '杠杆倍数（如：10, 20, 50）',
    
    -- 逐仓模式专用字段
    isolated_margin BIGINT NOT NULL DEFAULT 0 COMMENT '逐仓保证金金额（精度8位小数）',
    added_margin BIGINT NOT NULL DEFAULT 0 COMMENT '已追加的逐仓保证金（累计）',
    reduced_margin BIGINT NOT NULL DEFAULT 0 COMMENT '已减少的逐仓保证金（累计）',
    
    -- 仓位保证金计算
    position_value BIGINT NOT NULL DEFAULT 0 COMMENT '仓位名义价值（当前价格 * 持仓数量）',
    position_margin BIGINT NOT NULL DEFAULT 0 COMMENT '仓位保证金',
    unrealized_pnl BIGINT NOT NULL DEFAULT 0 COMMENT '未实现盈亏（正数=盈利，负数=亏损）',
    maintenance_margin BIGINT NOT NULL DEFAULT 0 COMMENT '维持保证金',
    maintenance_margin_rate INT NOT NULL DEFAULT 50 COMMENT '维持保证金率（万分比），默认0.5% = 50',
    
    -- 强平价格
    liquidation_price BIGINT NOT NULL DEFAULT 0 COMMENT '预估强平价格',
    bankruptcy_price BIGINT NOT NULL DEFAULT 0 COMMENT '破产价格（保证金归零的价格）',
    mark_price BIGINT NOT NULL DEFAULT 0 COMMENT '标记价格（用于计算强平）',
    
    -- 风险指标
    margin_ratio BIGINT NOT NULL DEFAULT 0 COMMENT '保证金率（万分比）',
    liquidation_distance BIGINT NOT NULL DEFAULT 0 COMMENT '距离强平价百分比（万分比）',
    max_add_position_qty BIGINT NOT NULL DEFAULT 0 COMMENT '最大可开仓数量',
    
    -- 仓位基础信息
    quantity BIGINT NOT NULL DEFAULT 0 COMMENT '持仓数量（精度8位小数）',
    entry_price BIGINT NOT NULL DEFAULT 0 COMMENT '开仓均价',
    last_price BIGINT NOT NULL DEFAULT 0 COMMENT '最新成交价格',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
    version INT NOT NULL DEFAULT 1 COMMENT '版本号（乐观锁）',
    
    -- 索引
    INDEX idx_user_id (user_id),
    INDEX idx_position_id (position_id),
    INDEX idx_symbol (symbol),
    INDEX idx_margin_mode (margin_mode),
    INDEX idx_user_margin_mode (user_id, margin_mode),
    INDEX idx_margin_ratio (margin_ratio),
    UNIQUE KEY uk_position_id (position_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓位保证金详情表';

-- ============================================
-- 2. 全仓账户风险快照表
-- ============================================
DROP TABLE IF EXISTS t_cross_margin_snapshot;
CREATE TABLE t_cross_margin_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    
    -- 账户资金
    wallet_balance BIGINT NOT NULL DEFAULT 0 COMMENT '钱包余额（可用余额 + 仓位保证金）',
    available_balance BIGINT NOT NULL DEFAULT 0 COMMENT '可用余额（可下单、可转出）',
    frozen_balance BIGINT NOT NULL DEFAULT 0 COMMENT '冻结余额（挂单冻结）',
    used_margin BIGINT NOT NULL DEFAULT 0 COMMENT '已用保证金（所有全仓仓位的保证金总和）',
    
    -- 仓位价值
    total_position_value BIGINT NOT NULL DEFAULT 0 COMMENT '全仓仓位总价值（名义价值总和）',
    long_position_value BIGINT NOT NULL DEFAULT 0 COMMENT '多头仓位价值',
    short_position_value BIGINT NOT NULL DEFAULT 0 COMMENT '空头仓位价值',
    position_count INT NOT NULL DEFAULT 0 COMMENT '持仓数量统计（全仓仓位数）',
    
    -- 盈亏统计
    total_unrealized_pnl BIGINT NOT NULL DEFAULT 0 COMMENT '未实现盈亏（所有全仓仓位的浮盈/浮亏总和）',
    today_realized_pnl BIGINT NOT NULL DEFAULT 0 COMMENT '已实现盈亏（当日已实现盈亏）',
    
    -- 保证金计算
    total_maintenance_margin BIGINT NOT NULL DEFAULT 0 COMMENT '总维持保证金（所有全仓仓位的维持保证金总和）',
    initial_margin_requirement BIGINT NOT NULL DEFAULT 0 COMMENT '初始保证金要求（新开仓所需最小保证金）',
    maintenance_margin_rate INT NOT NULL DEFAULT 0 COMMENT '维持保证金率（万分比）',
    
    -- 核心风险指标
    margin_balance BIGINT NOT NULL DEFAULT 0 COMMENT '保证金余额（wallet_balance + total_unrealized_pnl）',
    margin_ratio BIGINT NOT NULL DEFAULT 10000 COMMENT '保证金率（万分比），默认100% = 10000',
    available_margin BIGINT NOT NULL DEFAULT 0 COMMENT '可用保证金（可开新仓的保证金）',
    max_open_position_value BIGINT NOT NULL DEFAULT 0 COMMENT '最大可开仓价值（基于当前可用保证金）',
    
    -- 风险等级
    risk_level INT NOT NULL DEFAULT 1 COMMENT '风险等级：1=SAFE, 2=WARNING, 3=DANGER, 4=LIQUIDATION',
    risk_level_name VARCHAR(16) NOT NULL DEFAULT 'SAFE' COMMENT '风险等级名称',
    can_trade TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否可交易（风险等级为LIQUIDATION时不可交易）',
    can_withdraw TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否可提现（风险等级为DANGER以上时限制提现）',
    
    -- 强平相关
    estimated_liquidation_price BIGINT NOT NULL DEFAULT 0 COMMENT '预估总强平价格（加权平均）',
    liquidation_gap BIGINT NOT NULL DEFAULT 0 COMMENT '距离强平的盈亏缺口',
    liquidation_status INT NOT NULL DEFAULT 0 COMMENT '强平执行状态：0=正常, 1=强平中, 2=强平完成',
    liquidation_start_time BIGINT DEFAULT NULL COMMENT '强平开始时间',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '快照创建时间（毫秒时间戳）',
    updated_at BIGINT NOT NULL COMMENT '快照更新时间（毫秒时间戳）',
    version INT NOT NULL DEFAULT 1 COMMENT '版本号（乐观锁）',
    
    -- 索引
    INDEX idx_user_id (user_id),
    INDEX idx_risk_level (risk_level),
    INDEX idx_margin_ratio (margin_ratio),
    INDEX idx_liquidation_status (liquidation_status),
    UNIQUE KEY uk_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全仓账户风险快照表';

-- ============================================
-- 3. 保证金模式切换日志表
-- ============================================
DROP TABLE IF EXISTS t_margin_mode_switch_log;
CREATE TABLE t_margin_mode_switch_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    position_id BIGINT NOT NULL COMMENT '仓位ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    from_mode VARCHAR(16) NOT NULL COMMENT '原保证金模式',
    to_mode VARCHAR(16) NOT NULL COMMENT '目标保证金模式',
    from_leverage INT NOT NULL COMMENT '原杠杆倍数',
    to_leverage INT NOT NULL COMMENT '目标杠杆倍数',
    from_isolated_margin BIGINT NOT NULL DEFAULT 0 COMMENT '原逐仓保证金',
    to_isolated_margin BIGINT NOT NULL DEFAULT 0 COMMENT '目标逐仓保证金',
    reason VARCHAR(256) DEFAULT NULL COMMENT '切换原因',
    operator_id BIGINT DEFAULT NULL COMMENT '操作人ID（系统操作为NULL）',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    
    INDEX idx_user_id (user_id),
    INDEX idx_position_id (position_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='保证金模式切换日志表';

-- ============================================
-- 4. 逐仓保证金调整日志表
-- ============================================
DROP TABLE IF EXISTS t_isolated_margin_adjust_log;
CREATE TABLE t_isolated_margin_adjust_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    position_id BIGINT NOT NULL COMMENT '仓位ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    adjust_type VARCHAR(16) NOT NULL COMMENT '调整类型：ADD=追加, REDUCE=减少',
    amount BIGINT NOT NULL COMMENT '调整金额',
    before_margin BIGINT NOT NULL COMMENT '调整前保证金',
    after_margin BIGINT NOT NULL COMMENT '调整后保证金',
    reason VARCHAR(256) DEFAULT NULL COMMENT '调整原因',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    
    INDEX idx_user_id (user_id),
    INDEX idx_position_id (position_id),
    INDEX idx_adjust_type (adjust_type),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='逐仓保证金调整日志表';

-- ============================================
-- 5. 风险事件记录表
-- ============================================
DROP TABLE IF EXISTS t_risk_event;
CREATE TABLE t_risk_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：LIQUIDATION_WARNING=强平警告, LIQUIDATION=强平触发, MARGIN_CALL=追加保证金通知',
    risk_level INT NOT NULL COMMENT '风险等级',
    margin_ratio BIGINT NOT NULL COMMENT '触发时的保证金率',
    position_ids VARCHAR(512) DEFAULT NULL COMMENT '相关仓位ID列表（逗号分隔）',
    description TEXT DEFAULT NULL COMMENT '事件描述',
    is_processed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已处理',
    processed_at BIGINT DEFAULT NULL COMMENT '处理时间',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    
    INDEX idx_user_id (user_id),
    INDEX idx_event_type (event_type),
    INDEX idx_risk_level (risk_level),
    INDEX idx_is_processed (is_processed),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险事件记录表';

-- ============================================
-- 初始化数据
-- ============================================

-- 插入测试数据（可选）
-- INSERT INTO t_cross_margin_snapshot (user_id, wallet_balance, available_balance, margin_balance, margin_ratio, risk_level, risk_level_name, can_trade, can_withdraw, liquidation_status, created_at, updated_at) 
-- VALUES (1, 1000000000000, 1000000000000, 1000000000000, 10000, 1, 'SAFE', 1, 1, 0, UNIX_TIMESTAMP()*1000, UNIX_TIMESTAMP()*1000);
