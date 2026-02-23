-- ========================================================
-- User Core 数据库表结构
-- 用户服务 - 注册登录与账户初始化
-- ========================================================

CREATE DATABASE IF NOT EXISTS exchange_user 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE exchange_user;

-- --------------------------------------------------------
-- 用户表
-- --------------------------------------------------------
DROP TABLE IF EXISTS t_user;
CREATE TABLE t_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    username VARCHAR(32) NOT NULL COMMENT '用户名',
    password_hash VARCHAR(128) NOT NULL COMMENT '密码哈希(bcrypt)',
    email VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    phone VARCHAR(32) DEFAULT NULL COMMENT '手机号',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-禁用 1-正常 2-待激活',
    user_type VARCHAR(16) DEFAULT 'RETAIL' COMMENT '用户类型: RETAIL-零售 INSTITUTIONAL-机构',
    register_ip VARCHAR(64) DEFAULT NULL COMMENT '注册IP',
    last_login_time TIMESTAMP NULL DEFAULT NULL COMMENT '最后登录时间',
    last_login_ip VARCHAR(64) DEFAULT NULL COMMENT '最后登录IP',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除: 0-正常 1-已删除',
    
    UNIQUE KEY uk_username (username),
    KEY idx_status (status),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- --------------------------------------------------------
-- 交易账户表
-- --------------------------------------------------------
DROP TABLE IF EXISTS t_trading_account;
CREATE TABLE t_trading_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '账户ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    account_type VARCHAR(16) DEFAULT 'STANDARD' COMMENT '账户类型: STANDARD-标准 MARGIN-保证金 FUTURES-合约',
    margin_mode VARCHAR(16) DEFAULT 'CROSS' COMMENT '保证金模式: CROSS-全仓 ISOLATED-逐仓',
    default_leverage INT DEFAULT 10 COMMENT '默认杠杆倍数',
    status TINYINT DEFAULT 1 COMMENT '状态: 0-冻结 1-正常',
    is_funded TINYINT DEFAULT 0 COMMENT '是否已初始化资金: 0-否 1-是',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    KEY idx_user_id (user_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易账户表';

-- --------------------------------------------------------
-- 资金调整记录表
-- --------------------------------------------------------
DROP TABLE IF EXISTS t_funding_adjustment;
CREATE TABLE t_funding_adjustment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '记录ID',
    account_id BIGINT NOT NULL COMMENT '账户ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    asset VARCHAR(16) NOT NULL COMMENT '资产类型',
    amount DECIMAL(32,8) NOT NULL COMMENT '调整金额(正数增加,负数减少)',
    balance_before DECIMAL(32,8) DEFAULT NULL COMMENT '调整前余额',
    balance_after DECIMAL(32,8) DEFAULT NULL COMMENT '调整后余额',
    reason VARCHAR(32) NOT NULL COMMENT '调整原因: INITIAL_FUNDING-初始资金 MANUAL_ADJUST-手动调整',
    operator_id BIGINT DEFAULT NULL COMMENT '操作人ID(系统操作为NULL)',
    remark VARCHAR(256) DEFAULT NULL COMMENT '操作备注',
    ledger_entry_id VARCHAR(64) DEFAULT NULL COMMENT '关联的Ledger Entry ID',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    KEY idx_account_id (account_id),
    KEY idx_user_id (user_id),
    KEY idx_reason (reason),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金调整记录表';

-- --------------------------------------------------------
-- 初始化测试用户 (密码: 123456)
-- bcrypt hash for "123456": $2a$10$N9qo8uLOickgx2ZMRZoMy.MqrqXmZgKcE1Q7L3l6z9R3XqW4e8y2O
-- --------------------------------------------------------
-- INSERT INTO t_user (username, password_hash, status, user_type) VALUES
-- ('test001', '$2a$10$N9qo8uLOickgx2ZMRZoMy.MqrqXmZgKcE1Q7L3l6z9R3XqW4e8y2O', 1, 'RETAIL');

-- --------------------------------------------------------
-- 表注释
-- --------------------------------------------------------

-- 用户表: 存储用户基本信息，注册时自动创建
-- 交易账户表: 每个用户至少有一个交易账户，用于隔离不同业务线
-- 资金调整记录表: 审计日志，记录所有资金变动来源

-- ========================================================
-- 使用说明
-- ========================================================
-- 1. 执行此SQL创建数据库和表
-- 2. 启动user-core服务
-- 3. 调用POST /api/v1/user/register注册用户
-- 4. 注册成功后自动创建交易账户并注入初始资金
-- ========================================================
