-- =====================================================
-- Margin Mode Core 数据库表结构
-- 数据库: exchange_margin
-- 职责: 全仓/逐仓模式管理、保证金计算
-- =====================================================

CREATE DATABASE IF NOT EXISTS exchange_margin 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE exchange_margin;

-- =====================================================
-- 1. 用户保证金配置表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_user_margin_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 默认模式
    default_margin_mode VARCHAR(16) NOT NULL DEFAULT 'CROSS' COMMENT '默认模式: CROSS/ISOLATED',
    
    -- 全仓配置
    cross_leverage INT DEFAULT 10 COMMENT '全仓默认杠杆',
    cross_max_leverage INT DEFAULT 125 COMMENT '全仓最大杠杆',
    
    -- 逐仓配置
    isolated_leverage INT DEFAULT 10 COMMENT '逐仓默认杠杆',
    isolated_max_leverage INT DEFAULT 125 COMMENT '逐仓最大杠杆',
    
    -- 风控配置
    max_position_num INT DEFAULT 50 COMMENT '最大仓位数量',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    UNIQUE KEY uk_user_symbol (user_id, symbol),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户保证金配置表';

-- =====================================================
-- 2. 仓位保证金详情表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_position_margin_detail (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    position_id BIGINT NOT NULL COMMENT '仓位ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 持仓方向
    side TINYINT NOT NULL COMMENT '方向: 1=多 2=空',
    
    -- 保证金模式
    margin_mode VARCHAR(16) NOT NULL COMMENT '模式: CROSS/ISOLATED',
    leverage INT NOT NULL COMMENT '当前杠杆倍数',
    
    -- 逐仓专用
    isolated_margin BIGINT COMMENT '逐仓保证金（仅逐仓模式）',
    isolated_available BIGINT COMMENT '逐仓可用保证金（可取出）',
    added_margin BIGINT DEFAULT 0 COMMENT '追加的保证金总额',
    reduced_margin BIGINT DEFAULT 0 COMMENT '减少的保证金总额',
    
    -- 保证金计算
    position_margin BIGINT NOT NULL COMMENT '仓位保证金',
    maint_margin BIGINT NOT NULL COMMENT '维持保证金',
    maint_margin_rate BIGINT NOT NULL DEFAULT 50 COMMENT '维持保证金率（万分比，如500=5%）',
    
    -- 仓位信息
    position_value BIGINT COMMENT '仓位价值',
    position_qty BIGINT COMMENT '持仓数量',
    entry_price BIGINT COMMENT '开仓均价',
    mark_price BIGINT COMMENT '标记价格',
    
    -- 风险指标
    margin_ratio BIGINT COMMENT '保证金率（万分比，仅逐仓）',
    liquidation_price BIGINT COMMENT '预估强平价',
    bankruptcy_price BIGINT COMMENT '破产价格',
    
    -- 盈亏
    unrealized_pnl BIGINT COMMENT '未实现盈亏',
    realized_pnl BIGINT DEFAULT 0 COMMENT '已实现盈亏',
    
    -- 全仓专用
    cross_unrealized_pnl BIGINT COMMENT '全仓未实现盈亏',
    
    -- 并发控制
    version INT DEFAULT 0 COMMENT '版本号（乐观锁）',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    UNIQUE KEY uk_position_id (position_id),
    KEY idx_user_id (user_id),
    KEY idx_symbol (symbol),
    KEY idx_margin_mode (user_id, margin_mode),
    KEY idx_margin_ratio (margin_mode, margin_ratio)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓位保证金详情表';

-- =====================================================
-- 3. 保证金变动流水表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_margin_change_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    position_id BIGINT COMMENT '仓位ID',
    
    -- 变动信息
    change_type VARCHAR(32) NOT NULL COMMENT '变动类型: OPEN/CLOSE/ADD/REMOVE/MODE_CHANGE/LIQUIDATION/FUNDING_FEE/ADL',
    margin_mode VARCHAR(16) NOT NULL COMMENT '模式: CROSS/ISOLATED',
    amount BIGINT NOT NULL COMMENT '变动金额（正=增加，负=减少）',
    before_amount BIGINT NOT NULL COMMENT '变动前金额',
    after_amount BIGINT NOT NULL COMMENT '变动后金额',
    
    -- 关联信息
    biz_id BIGINT COMMENT '业务ID（订单ID等）',
    biz_type VARCHAR(32) COMMENT '业务类型',
    remark VARCHAR(255) COMMENT '备注信息',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    KEY idx_user_id (user_id),
    KEY idx_position_id (position_id),
    KEY idx_change_type (change_type),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='保证金变动流水表';

-- =====================================================
-- 4. 全仓账户风险快照表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_cross_margin_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    
    -- 账户资金
    wallet_balance BIGINT NOT NULL COMMENT '钱包余额',
    available_balance BIGINT NOT NULL COMMENT '可用余额',
    frozen_balance BIGINT DEFAULT 0 COMMENT '冻结余额（挂单冻结）',
    used_margin BIGINT DEFAULT 0 COMMENT '已用保证金',
    
    -- 仓位价值
    total_position_value BIGINT NOT NULL COMMENT '全仓仓位总价值',
    long_position_value BIGINT DEFAULT 0 COMMENT '多头仓位价值',
    short_position_value BIGINT DEFAULT 0 COMMENT '空头仓位价值',
    position_count INT DEFAULT 0 COMMENT '持仓数量统计',
    
    -- 盈亏统计
    total_unrealized_pnl BIGINT NOT NULL COMMENT '未实现盈亏',
    today_realized_pnl BIGINT DEFAULT 0 COMMENT '今日已实现盈亏',
    
    -- 保证金计算
    total_maintenance_margin BIGINT NOT NULL COMMENT '总维持保证金',
    initial_margin_requirement BIGINT DEFAULT 0 COMMENT '初始保证金要求',
    maintenance_margin_rate INT DEFAULT 50 COMMENT '维持保证金率（万分比）',
    
    -- 核心风险指标
    margin_balance BIGINT NOT NULL COMMENT '保证金余额 = 钱包余额 + 未实现盈亏',
    margin_ratio BIGINT NOT NULL COMMENT '保证金率（万分比）',
    available_margin BIGINT NOT NULL COMMENT '可用保证金',
    max_open_position_value BIGINT DEFAULT 0 COMMENT '最大可开仓价值',
    
    -- 风险等级
    risk_level INT NOT NULL DEFAULT 1 COMMENT '风险等级: 1=SAFE, 2=WARNING, 3=DANGER, 4=LIQUIDATION',
    risk_level_name VARCHAR(16) DEFAULT 'SAFE' COMMENT '风险等级名称',
    can_trade BOOLEAN DEFAULT TRUE COMMENT '是否可交易',
    can_withdraw BOOLEAN DEFAULT TRUE COMMENT '是否可提现',
    
    -- 强平相关
    estimated_liquidation_price BIGINT DEFAULT 0 COMMENT '预估总强平价格（加权平均）',
    liquidation_gap BIGINT DEFAULT 0 COMMENT '距离强平的盈亏缺口',
    liquidation_status INT DEFAULT 0 COMMENT '强平执行状态: 0=正常, 1=强平中, 2=强平完成',
    liquidation_start_time BIGINT DEFAULT 0 COMMENT '强平开始时间（毫秒）',
    
    -- 并发控制
    version INT DEFAULT 0 COMMENT '版本号（乐观锁）',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '快照创建时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '快照更新时间（毫秒）',
    
    UNIQUE KEY uk_user_id (user_id),
    KEY idx_risk_level (risk_level),
    KEY idx_margin_ratio (margin_ratio),
    KEY idx_liquidation_status (liquidation_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全仓账户风险快照表';

-- =====================================================
-- 5. 保证金模式切换记录表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_margin_mode_switch_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    position_id BIGINT COMMENT '仓位ID',
    
    -- 切换信息
    from_mode VARCHAR(16) NOT NULL COMMENT '原模式',
    to_mode VARCHAR(16) NOT NULL COMMENT '新模式',
    from_leverage INT COMMENT '原杠杆',
    to_leverage INT COMMENT '新杠杆',
    
    -- 保证金变更
    margin_before BIGINT COMMENT '变更前保证金',
    margin_after BIGINT COMMENT '变更后保证金',
    margin_delta BIGINT COMMENT '保证金变化',
    
    -- 状态
    status TINYINT DEFAULT 1 COMMENT '状态: 1=成功 0=失败',
    error_msg VARCHAR(512) COMMENT '错误信息',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    KEY idx_user_id (user_id),
    KEY idx_symbol (symbol),
    KEY idx_position_id (position_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='保证金模式切换记录表';

-- =====================================================
-- 初始化数据
-- =====================================================
INSERT INTO t_user_margin_config (user_id, symbol, default_margin_mode, cross_leverage, isolated_leverage) VALUES
(10001, 'BTCUSDT', 'CROSS', 10, 20),
(10001, 'ETHUSDT', 'ISOLATED', 10, 20),
(10002, 'BTCUSDT', 'CROSS', 5, 10)
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP;

INSERT INTO t_cross_margin_snapshot (
    user_id, wallet_balance, available_balance, frozen_balance, used_margin,
    total_position_value, long_position_value, short_position_value, position_count,
    total_unrealized_pnl, today_realized_pnl, total_maintenance_margin,
    margin_balance, margin_ratio, available_margin, risk_level, risk_level_name,
    can_trade, can_withdraw, created_at, updated_at
) VALUES
(10001, 1000000000000, 1000000000000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1000000000000, 10000, 1000000000000, 1, 'SAFE', TRUE, TRUE, UNIX_TIMESTAMP()*1000, UNIX_TIMESTAMP()*1000),
(10002, 500000000000, 500000000000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 500000000000, 10000, 500000000000, 1, 'SAFE', TRUE, TRUE, UNIX_TIMESTAMP()*1000, UNIX_TIMESTAMP()*1000)
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);
