-- =====================================================
-- User Core 数据库表结构
-- 数据库: exchange_user
-- 职责: 用户注册登录、账户初始化、身份认证
-- =====================================================

CREATE DATABASE IF NOT EXISTS exchange_user 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE exchange_user;

-- =====================================================
-- 1. 用户主表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    
    -- 基本信息
    username VARCHAR(32) NOT NULL COMMENT '用户名',
    password_hash VARCHAR(128) NOT NULL COMMENT '密码哈希(bcrypt)',
    email VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    phone VARCHAR(32) DEFAULT NULL COMMENT '手机号',
    
    -- 用户类型与状态
    user_type VARCHAR(16) DEFAULT 'RETAIL' COMMENT '用户类型: RETAIL-零售 INSTITUTIONAL-机构 MARKET_MAKER-做市商',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-禁用 1-正常 2-待激活 3-冻结',
    
    -- 安全相关
    google_secret VARCHAR(64) COMMENT 'Google Authenticator密钥',
    email_verified TINYINT DEFAULT 0 COMMENT '邮箱是否验证: 0-否 1-是',
    phone_verified TINYINT DEFAULT 0 COMMENT '手机是否验证: 0-否 1-是',
    kyc_level TINYINT DEFAULT 0 COMMENT 'KYC等级: 0-未认证 1-基础 2-高级',
    
    -- 登录信息
    register_ip VARCHAR(64) DEFAULT NULL COMMENT '注册IP',
    register_source VARCHAR(32) COMMENT '注册来源: WEB/APP/API',
    last_login_time TIMESTAMP NULL DEFAULT NULL COMMENT '最后登录时间',
    last_login_ip VARCHAR(64) DEFAULT NULL COMMENT '最后登录IP',
    login_fail_count INT DEFAULT 0 COMMENT '连续登录失败次数',
    lock_until TIMESTAMP NULL COMMENT '锁定截止时间',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除: 0-正常 1-已删除',
    
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_email (email),
    UNIQUE KEY uk_phone (phone),
    KEY idx_status (status),
    KEY idx_kyc_level (kyc_level),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户主表';

-- =====================================================
-- 2. 交易账户表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_trading_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '账户ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    
    -- 账户配置
    account_type VARCHAR(16) DEFAULT 'STANDARD' COMMENT '账户类型: STANDARD-标准 MARGIN-保证金 FUTURES-合约',
    margin_mode VARCHAR(16) DEFAULT 'CROSS' COMMENT '默认保证金模式: CROSS-全仓 ISOLATED-逐仓',
    default_leverage INT DEFAULT 10 COMMENT '默认杠杆倍数',
    
    -- 状态
    status TINYINT DEFAULT 1 COMMENT '状态: 0-冻结 1-正常 2-限制交易',
    is_funded TINYINT DEFAULT 0 COMMENT '是否已初始化资金: 0-否 1-是',
    
    -- API限制
    api_key_limit INT DEFAULT 10 COMMENT 'API Key数量限制',
    api_rate_limit INT DEFAULT 1000 COMMENT 'API频率限制(每分钟)',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    UNIQUE KEY uk_user_type (user_id, account_type),
    KEY idx_user_id (user_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易账户表';

-- =====================================================
-- 3. 用户API密钥表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_user_api_key (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    
    -- API密钥
    api_key VARCHAR(64) NOT NULL COMMENT 'API Key',
    api_secret_hash VARCHAR(128) NOT NULL COMMENT 'API Secret哈希',
    
    -- 权限配置
    permissions VARCHAR(256) DEFAULT 'READ' COMMENT '权限: READ/SPOT/MARGIN/FUTURES/WITHDRAW',
    ip_whitelist VARCHAR(512) COMMENT 'IP白名单',
    
    -- 限制
    rate_limit INT DEFAULT 1000 COMMENT '频率限制(每分钟)',
    
    -- 状态
    status TINYINT DEFAULT 1 COMMENT '状态: 0-禁用 1-启用',
    last_used_at TIMESTAMP NULL COMMENT '最后使用时间',
    expires_at TIMESTAMP NULL COMMENT '过期时间',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    UNIQUE KEY uk_api_key (api_key),
    KEY idx_user_id (user_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户API密钥表';

-- =====================================================
-- 4. 登录日志表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_login_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    
    -- 登录信息
    login_type VARCHAR(16) NOT NULL COMMENT '登录类型: PASSWORD/GOOGLE/API_KEY',
    login_ip VARCHAR(64) NOT NULL COMMENT '登录IP',
    user_agent VARCHAR(512) COMMENT 'User Agent',
    device_id VARCHAR(64) COMMENT '设备ID',
    
    -- 结果
    success TINYINT NOT NULL COMMENT '是否成功: 0-否 1-是',
    fail_reason VARCHAR(256) COMMENT '失败原因',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    KEY idx_user_id (user_id),
    KEY idx_login_ip (login_ip),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志表';

-- =====================================================
-- 5. 资金调整记录表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_funding_adjustment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '记录ID',
    account_id BIGINT NOT NULL COMMENT '账户ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    
    -- 调整信息
    asset VARCHAR(16) NOT NULL COMMENT '资产类型',
    amount DECIMAL(32,8) NOT NULL COMMENT '调整金额(正数增加,负数减少)',
    balance_before DECIMAL(32,8) DEFAULT NULL COMMENT '调整前余额',
    balance_after DECIMAL(32,8) DEFAULT NULL COMMENT '调整后余额',
    
    -- 调整原因
    reason VARCHAR(32) NOT NULL COMMENT '调整原因: INITIAL_FUNDING-初始资金 MANUAL_ADJUST-手动调整 ACTIVITY-活动奖励',
    operator_id BIGINT DEFAULT NULL COMMENT '操作人ID(系统操作为NULL)',
    remark VARCHAR(256) DEFAULT NULL COMMENT '操作备注',
    
    -- 关联信息
    ledger_entry_id VARCHAR(64) DEFAULT NULL COMMENT '关联的Ledger Entry ID',
    
    -- 时间戳
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    KEY idx_account_id (account_id),
    KEY idx_user_id (user_id),
    KEY idx_reason (reason),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金调整记录表';

-- =====================================================
-- 初始化数据
-- =====================================================

-- 插入测试用户 (密码: 123456)
-- bcrypt hash for "123456": $2a$10$N9qo8uLOickgx2ZMRZoMy.MqrqXmZgKcE1Q7L3l6z9R3XqW4e8y2O
-- INSERT INTO t_user (username, password_hash, email, phone, status, user_type) VALUES
-- ('test001', '$2a$10$N9qo8uLOickgx2ZMRZoMy.MqrqXmZgKcE1Q7L3l6z9R3XqW4e8y2O', 'test001@example.com', '+8613800000001', 1, 'RETAIL'),
-- ('test002', '$2a$10$N9qo8uLOickgx2ZMRZoMy.MqrqXmZgKcE1Q7L3l6z9R3XqW4e8y2O', 'test002@example.com', '+8613800000002', 1, 'RETAIL');

-- =====================================================
-- 表注释说明
-- =====================================================
-- t_user: 存储用户基本信息，注册时自动创建
-- t_trading_account: 每个用户至少有一个交易账户，用于隔离不同业务线
-- t_user_api_key: 用户API访问凭证，支持权限控制和IP白名单
-- t_login_log: 安全审计，记录所有登录行为
-- t_funding_adjustment: 审计日志，记录所有资金变动来源
