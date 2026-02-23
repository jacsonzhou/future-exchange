-- =====================================================
-- Hard Risk Core 数据库表结构
-- 数据库: exchange_risk
-- 职责: 同步硬风控、余额检查、杠杆校验、黑白名单
-- =====================================================

CREATE DATABASE IF NOT EXISTS exchange_risk 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE exchange_risk;

-- =====================================================
-- 1. 账户风险快照表（供风控快速查询）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_risk_account_snapshot (
    id BIGINT NOT NULL COMMENT '快照ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    
    -- 账户资金
    wallet_balance DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '钱包余额',
    available_balance DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '可用余额',
    frozen_balance DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '冻结余额',
    
    -- 保证金
    position_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '持仓占用保证金',
    order_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '挂单占用保证金',
    
    -- 盈亏
    unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '未实现盈亏',
    realized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '已实现盈亏',
    
    -- 权益与风险
    equity DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '账户权益 = 钱包余额 + 未实现盈亏',
    margin_ratio DECIMAL(16,8) NOT NULL DEFAULT 0 COMMENT '保证金率',
    available_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '可用保证金',
    
    -- 风控状态
    account_status TINYINT NOT NULL DEFAULT 0 COMMENT '账户状态: 0=NORMAL 1=FROZEN 2=LIQUIDATING 3=BANNED',
    risk_level TINYINT NOT NULL DEFAULT 1 COMMENT '风险等级: 1=SAFE 2=WARNING 3=DANGER 4=LIQUIDATION',
    
    -- 同步位点
    last_update_seq BIGINT NOT NULL DEFAULT 0 COMMENT '最后更新序列号',
    
    -- 时间戳
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_id (user_id),
    KEY idx_risk_level (risk_level),
    KEY idx_account_status (account_status),
    KEY idx_last_update (last_update_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控账户快照表';

-- =====================================================
-- 2. 持仓风险快照表（供风控快速查询）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_risk_position_snapshot (
    id BIGINT NOT NULL COMMENT '持仓ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 持仓方向
    side TINYINT NOT NULL COMMENT '方向: 1=LONG 2=SHORT',
    
    -- 持仓数量
    quantity DECIMAL(32,16) NOT NULL COMMENT '持仓数量',
    available_qty DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '可平仓数量',
    
    -- 价格信息
    entry_price DECIMAL(32,16) NOT NULL COMMENT '开仓均价',
    mark_price DECIMAL(32,16) NOT NULL COMMENT '标记价格',
    liquidation_price DECIMAL(32,16) COMMENT '预估强平价',
    bankruptcy_price DECIMAL(32,16) COMMENT '破产价格',
    
    -- 盈亏
    unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '未实现盈亏',
    
    -- 保证金
    position_margin DECIMAL(32,16) NOT NULL COMMENT '占用保证金',
    maintenance_margin DECIMAL(32,16) NOT NULL COMMENT '维持保证金',
    leverage INT NOT NULL COMMENT '杠杆倍数',
    margin_mode VARCHAR(16) NOT NULL DEFAULT 'CROSS' COMMENT '保证金模式: CROSS/ISOLATED',
    
    -- 风险指标
    margin_ratio DECIMAL(16,8) COMMENT '保证金率',
    risk_level TINYINT DEFAULT 1 COMMENT '风险等级',
    
    -- 状态
    position_status TINYINT NOT NULL DEFAULT 0 COMMENT '持仓状态: 0=NORMAL 1=LIQUIDATING 2=CLOSED',
    
    -- 时间戳
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_symbol (user_id, symbol),
    KEY idx_user_id (user_id),
    KEY idx_symbol (symbol),
    KEY idx_risk_level (risk_level),
    KEY idx_position_status (position_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控持仓快照表';

-- =====================================================
-- 3. 风控黑白名单表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_risk_user_list (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    
    -- 名单类型
    list_type TINYINT NOT NULL COMMENT '名单类型: 0=BLACK 1=WHITE 2=GRAY',
    
    -- 限制范围
    restrict_trade TINYINT DEFAULT 1 COMMENT '限制交易: 0-否 1-是',
    restrict_withdraw TINYINT DEFAULT 1 COMMENT '限制提现: 0-否 1-是',
    restrict_deposit TINYINT DEFAULT 0 COMMENT '限制充值: 0-否 1-是',
    
    -- 原因与备注
    reason VARCHAR(128) NULL COMMENT '原因',
    operator_id BIGINT COMMENT '操作人ID',
    operator_name VARCHAR(64) COMMENT '操作人名称',
    
    -- 有效期
    expire_at BIGINT COMMENT '过期时间（毫秒）',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_list (user_id, list_type),
    KEY idx_list_type (list_type),
    KEY idx_expire_at (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控黑白名单表';

-- =====================================================
-- 4. 风控审计日志表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_risk_check_log (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    
    -- 请求信息
    order_id BIGINT NOT NULL COMMENT '订单ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 检查结果
    result TINYINT NOT NULL COMMENT '风控结果: 0=PASS 1=REJECT',
    reject_reason VARCHAR(32) COMMENT '拒绝原因码',
    reject_message VARCHAR(255) COMMENT '拒绝信息',
    
    -- 检查详情
    check_items JSON COMMENT '检查项目详情',
    
    -- 资金信息
    required_margin DECIMAL(32,16) COMMENT '所需保证金',
    available_margin DECIMAL(32,16) COMMENT '可用保证金',
    
    -- 追踪
    trace_id VARCHAR(64) COMMENT '追踪ID',
    client_ip VARCHAR(64) COMMENT '客户端IP',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    
    PRIMARY KEY (id),
    KEY idx_user (user_id),
    KEY idx_order (order_id),
    KEY idx_symbol (symbol),
    KEY idx_result (result),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控审计日志表';

-- =====================================================
-- 5. 交易对风控配置表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_risk_symbol_config (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 杠杆限制
    max_leverage INT NOT NULL DEFAULT 125 COMMENT '最大杠杆',
    min_leverage INT NOT NULL DEFAULT 1 COMMENT '最小杠杆',
    default_leverage INT NOT NULL DEFAULT 10 COMMENT '默认杠杆',
    
    -- 持仓限制
    max_position_qty DECIMAL(32,16) NOT NULL COMMENT '最大持仓数量',
    max_position_value DECIMAL(32,16) NOT NULL COMMENT '最大持仓价值',
    
    -- 下单限制
    min_order_qty DECIMAL(32,16) NOT NULL COMMENT '最小下单数量',
    max_order_qty DECIMAL(32,16) NOT NULL COMMENT '最大下单数量',
    
    -- 价格限制
    max_price_deviation_pct DECIMAL(16,8) NOT NULL DEFAULT 0.10 COMMENT '最大价格偏离百分比',
    price_limit_up DECIMAL(16,8) DEFAULT 0.10 COMMENT '涨停幅度',
    price_limit_down DECIMAL(16,8) DEFAULT 0.10 COMMENT '跌停幅度',
    
    -- 保证金率
    initial_margin_rate DECIMAL(16,8) NOT NULL DEFAULT 0.01 COMMENT '初始保证金率(1/杠杆)',
    maintenance_margin_rate DECIMAL(16,8) NOT NULL DEFAULT 0.005 COMMENT '维持保证金率',
    
    -- 状态
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 0=禁用 1=启用 2=仅平仓',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    PRIMARY KEY (id),
    UNIQUE KEY uk_symbol (symbol),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易对风控配置表';

-- =====================================================
-- 6. 用户限流配置表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_risk_rate_limit (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    
    -- 限流配置
    order_limit_per_second INT DEFAULT 10 COMMENT '每秒下单限制',
    order_limit_per_minute INT DEFAULT 100 COMMENT '每分钟下单限制',
    order_limit_per_hour INT DEFAULT 1000 COMMENT '每小时下单限制',
    cancel_limit_per_second INT DEFAULT 20 COMMENT '每秒撤单限制',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户限流配置表';

-- =====================================================
-- 初始化数据
-- =====================================================
INSERT INTO t_risk_symbol_config (symbol, max_leverage, max_position_qty, max_position_value, max_price_deviation_pct, min_order_qty, max_order_qty, status, created_at, updated_at)
VALUES 
('BTCUSDT', 125, 1000.00000000, 50000000.00000000, 0.10000000, 0.00100000, 100.00000000, 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
('ETHUSDT', 100, 10000.00000000, 50000000.00000000, 0.10000000, 0.01000000, 1000.00000000, 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
('SOLUSDT', 50, 50000.00000000, 10000000.00000000, 0.10000000, 0.10000000, 5000.00000000, 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000)
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);
