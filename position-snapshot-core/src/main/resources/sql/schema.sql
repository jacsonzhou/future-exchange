-- =====================================================
-- Position Snapshot Core 数据库表结构
-- 数据库: exchange_position
-- 职责: 持仓快照维护、浮盈浮亏计算、强平价格计算
-- =====================================================

CREATE DATABASE IF NOT EXISTS exchange_position 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE exchange_position;

-- =====================================================
-- 1. 持仓快照主表
-- =====================================================
CREATE TABLE IF NOT EXISTS position_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '持仓ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 持仓方向
    position_side TINYINT NOT NULL COMMENT '方向: 1=LONG 2=SHORT',
    
    -- 持仓数量
    size DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '持仓数量（始终为正数，方向由position_side决定）',
    available_size DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '可平仓数量',
    
    -- 价格信息
    entry_price DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '开仓均价',
    mark_price DECIMAL(32,16) COMMENT '标记价格',
    index_price DECIMAL(32,16) COMMENT '指数价格',
    
    -- 强平相关
    liquidation_price DECIMAL(32,16) COMMENT '强平价',
    bankruptcy_price DECIMAL(32,16) COMMENT '破产价格',
    
    -- 盈亏信息
    unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '未实现盈亏',
    realized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '已实现盈亏',
    pnl_ratio DECIMAL(16,8) COMMENT '盈亏比例',
    
    -- 保证金信息
    position_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '仓位保证金',
    maintenance_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '维持保证金',
    maintenance_margin_rate DECIMAL(16,8) COMMENT '维持保证金率',
    leverage INT NOT NULL DEFAULT 1 COMMENT '杠杆倍数',
    margin_mode VARCHAR(16) NOT NULL DEFAULT 'CROSS' COMMENT '保证金模式: CROSS/ISOLATED',
    
    -- 风险指标
    margin_ratio DECIMAL(16,8) COMMENT '保证金率',
    risk_level TINYINT DEFAULT 1 COMMENT '风险等级: 1=SAFE 2=WARNING 3=DANGER 4=LIQUIDATION',
    
    -- 自动减仓排名
    adl_score DECIMAL(32,16) COMMENT 'ADL得分',
    adl_rank INT COMMENT 'ADL排名',
    
    -- 同步位点
    last_trade_id VARCHAR(64) COMMENT '最后处理的tradeId',
    last_mark_price_id VARCHAR(64) COMMENT '最后处理的markPriceId',
    last_update_seq BIGINT NOT NULL DEFAULT 0 COMMENT '最后更新序列号',
    
    -- 并发控制
    version INT NOT NULL DEFAULT 0 COMMENT '版本号（乐观锁）',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    UNIQUE KEY uk_user_symbol_side (user_id, symbol, position_side),
    INDEX idx_user_id (user_id),
    INDEX idx_symbol (symbol),
    INDEX idx_last_update_seq (last_update_seq),
    INDEX idx_margin_ratio (margin_ratio),
    INDEX idx_risk_level (risk_level),
    INDEX idx_adl_score (symbol, position_side, adl_score DESC),
    INDEX idx_liquidation_price (symbol, liquidation_price)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓快照主表';

-- =====================================================
-- 2. 持仓历史表（按天归档）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_position_snapshot_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    snapshot_date INT NOT NULL COMMENT '快照日期(YYYYMMDD)',
    
    -- 持仓信息
    side TINYINT NOT NULL COMMENT '方向',
    size DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '持仓数量',
    entry_price DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '开仓均价',
    
    -- 盈亏
    unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '未实现盈亏',
    realized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '已实现盈亏',
    
    -- 保证金
    position_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '仓位保证金',
    margin_mode VARCHAR(16) NOT NULL DEFAULT 'CROSS' COMMENT '保证金模式',
    leverage INT NOT NULL DEFAULT 1 COMMENT '杠杆倍数',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    
    UNIQUE KEY uk_user_symbol_date (user_id, symbol, snapshot_date),
    INDEX idx_snapshot_date (snapshot_date),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓快照历史表';

-- =====================================================
-- 3. 持仓变更流水表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_position_change_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    position_id BIGINT NOT NULL COMMENT '持仓ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 变更信息
    change_type VARCHAR(32) NOT NULL COMMENT '变更类型: OPEN/INCREASE/DECREASE/CLOSE/LIQUIDATION/ADL/FUNDING_FEE',
    
    -- 数量变更
    size_before DECIMAL(32,16) COMMENT '变更前数量',
    size_after DECIMAL(32,16) COMMENT '变更后数量',
    size_change DECIMAL(32,16) NOT NULL COMMENT '数量变更',
    
    -- 价格信息
    entry_price_before DECIMAL(32,16) COMMENT '变更前开仓均价',
    entry_price_after DECIMAL(32,16) COMMENT '变更后开仓均价',
    trade_price DECIMAL(32,16) COMMENT '成交价格',
    
    -- 盈亏
    realized_pnl DECIMAL(32,16) DEFAULT 0 COMMENT '实现盈亏',
    funding_fee DECIMAL(32,16) DEFAULT 0 COMMENT '资金费用',
    
    -- 关联信息
    ref_id VARCHAR(64) COMMENT '关联ID（成交ID等）',
    ref_type VARCHAR(32) COMMENT '关联类型',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    
    INDEX idx_position (position_id),
    INDEX idx_user (user_id),
    INDEX idx_symbol (symbol),
    INDEX idx_change_type (change_type),
    INDEX idx_ref_id (ref_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓变更流水表';

-- =====================================================
-- 4. 全仓账户汇总表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_cross_position_summary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    
    -- 仓位统计
    position_count INT NOT NULL DEFAULT 0 COMMENT '持仓数量',
    long_count INT NOT NULL DEFAULT 0 COMMENT '多头数量',
    short_count INT NOT NULL DEFAULT 0 COMMENT '空头数量',
    
    -- 价值统计
    total_position_value DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '总仓位价值',
    long_position_value DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '多头仓位价值',
    short_position_value DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '空头仓位价值',
    
    -- 盈亏统计
    total_unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '总未实现盈亏',
    long_unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '多头未实现盈亏',
    short_unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '空头未实现盈亏',
    
    -- 保证金统计
    total_maintenance_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '总维持保证金',
    total_position_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '总仓位保证金',
    
    -- 风险指标
    margin_balance DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '保证金余额',
    margin_ratio DECIMAL(16,8) COMMENT '保证金率',
    available_margin DECIMAL(32,16) COMMENT '可用保证金',
    
    -- 强平相关
    estimated_liquidation_price DECIMAL(32,16) COMMENT '预估总强平价格（加权平均）',
    liquidation_gap DECIMAL(32,16) COMMENT '距离强平的盈亏缺口',
    liquidation_status TINYINT DEFAULT 0 COMMENT '强平状态: 0=正常 1=强平中 2=强平完成',
    
    -- 同步位点
    last_update_seq BIGINT NOT NULL DEFAULT 0 COMMENT '最后更新序列号',
    
    -- 并发控制
    version INT NOT NULL DEFAULT 0 COMMENT '版本号',
    
    -- 时间戳
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    UNIQUE KEY uk_user_id (user_id),
    INDEX idx_margin_ratio (margin_ratio),
    INDEX idx_liquidation_status (liquidation_status),
    INDEX idx_last_update (last_update_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全仓账户汇总表';

-- =====================================================
-- 5. 持仓统计表（按symbol统计）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_position_statistics (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    stat_date INT NOT NULL COMMENT '统计日期(YYYYMMDD)',
    
    -- 持仓统计
    long_position_count INT NOT NULL DEFAULT 0 COMMENT '多头持仓数',
    short_position_count INT NOT NULL DEFAULT 0 COMMENT '空头持仓数',
    total_position_count INT NOT NULL DEFAULT 0 COMMENT '总持仓数',
    
    -- 数量统计
    long_total_qty DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '多头总数量',
    short_total_qty DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '空头总数量',
    net_position DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '净持仓（多-空）',
    
    -- 价值统计
    long_position_value DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '多头总价值',
    short_position_value DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '空头总价值',
    
    -- 盈亏统计
    total_unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '总未实现盈亏',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    UNIQUE KEY uk_symbol_date (symbol, stat_date),
    INDEX idx_stat_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓统计表';

-- =====================================================
-- 初始化数据
-- =====================================================
-- 初始化测试持仓
INSERT INTO position_snapshot (
    user_id, symbol, position_side, size, available_size, entry_price,
    unrealized_pnl, realized_pnl, margin_ratio, liquidation_price,
    leverage, margin_mode, last_update_seq, version, created_at, updated_at
) VALUES 
(10001, 'BTCUSDT', 1, 1.5, 1.5, 50000.00, 0, 0, 2.50, 48000.00, 10, 'ISOLATED', 0, 0, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
(10002, 'ETHUSDT', 2, 10.0, 10.0, 3000.00, 0, 0, 2.80, 3100.00, 10, 'ISOLATED', 0, 0, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000)
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);
