-- =====================================================
-- Replay Core 数据库表结构
-- 数据库: exchange_replay
-- 职责: WAL日志重放、灾备恢复、对账审计
-- =====================================================

CREATE DATABASE IF NOT EXISTS exchange_replay 
    DEFAULT CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

USE exchange_replay;

-- =====================================================
-- 1. 重放任务表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_replay_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    task_id VARCHAR(64) NOT NULL COMMENT '任务ID（全局唯一）',
    
    -- 任务信息
    task_name VARCHAR(128) NOT NULL COMMENT '任务名称',
    task_type VARCHAR(32) NOT NULL COMMENT '任务类型: FULL/INCREMENTAL/SYMBOL/USER',
    description VARCHAR(512) COMMENT '任务描述',
    
    -- 重放范围
    start_biz_seq BIGINT NOT NULL COMMENT '起始biz_seq',
    end_biz_seq BIGINT COMMENT '结束biz_seq（为空表示到最新）',
    start_time BIGINT COMMENT '起始时间（毫秒）',
    end_time BIGINT COMMENT '结束时间（毫秒）',
    
    -- 筛选条件（可选）
    filter_symbol VARCHAR(32) COMMENT '筛选交易对',
    filter_user_id BIGINT COMMENT '筛选用户ID',
    filter_business_type VARCHAR(32) COMMENT '筛选业务类型',
    
    -- 统计
    total_entries BIGINT DEFAULT 0 COMMENT '总分录数',
    processed_entries BIGINT DEFAULT 0 COMMENT '已处理分录数',
    success_count BIGINT DEFAULT 0 COMMENT '成功数',
    fail_count BIGINT DEFAULT 0 COMMENT '失败数',
    affected_users INT DEFAULT 0 COMMENT '影响用户数',
    
    -- 状态
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RUNNING/PAUSED/COMPLETED/FAILED/CANCELLED',
    progress_pct INT DEFAULT 0 COMMENT '进度百分比',
    
    -- 错误信息
    error_msg TEXT COMMENT '错误信息',
    last_error_seq BIGINT COMMENT '最后错误seq',
    
    -- 时间戳
    start_at BIGINT COMMENT '实际开始时间（毫秒）',
    end_at BIGINT COMMENT '实际结束时间（毫秒）',
    duration_ms BIGINT COMMENT '耗时（毫秒）',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    created_by VARCHAR(64) COMMENT '创建人',
    
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_status (status),
    KEY idx_created_at (created_at),
    KEY idx_task_type (task_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='重放任务表';

-- =====================================================
-- 2. 重放明细表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_replay_detail (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    task_id VARCHAR(64) NOT NULL COMMENT '任务ID',
    
    -- 分录信息
    biz_seq BIGINT NOT NULL COMMENT '业务序列号',
    entry_id BIGINT COMMENT '分录ID',
    user_id BIGINT COMMENT '用户ID',
    business_type VARCHAR(32) COMMENT '业务类型',
    
    -- 重放状态
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0=待处理 1=成功 2=失败 3=跳过',
    
    -- 处理信息
    process_time_ms INT COMMENT '处理耗时（毫秒）',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    error_msg VARCHAR(512) COMMENT '错误信息',
    
    -- 数据快照
    before_data JSON COMMENT '处理前数据',
    after_data JSON COMMENT '处理后数据',
    
    -- 时间戳
    processed_at BIGINT COMMENT '处理时间（毫秒）',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    
    KEY idx_task_id (task_id),
    KEY idx_biz_seq (biz_seq),
    KEY idx_user_id (user_id),
    KEY idx_status (status),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='重放明细表';

-- =====================================================
-- 3. WAL文件索引表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_wal_file_index (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    
    -- 文件信息
    file_name VARCHAR(128) NOT NULL COMMENT '文件名',
    file_path VARCHAR(512) NOT NULL COMMENT '文件路径',
    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
    
    -- 内容范围
    start_biz_seq BIGINT NOT NULL COMMENT '起始biz_seq',
    end_biz_seq BIGINT NOT NULL COMMENT '结束biz_seq',
    start_time BIGINT NOT NULL COMMENT '起始时间（毫秒）',
    end_time BIGINT NOT NULL COMMENT '结束时间（毫秒）',
    entry_count INT NOT NULL DEFAULT 0 COMMENT '条目数',
    
    -- 文件状态
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1=ACTIVE 2=ARCHIVED 3=DELETED',
    archive_path VARCHAR(512) COMMENT '归档路径',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    archived_at BIGINT COMMENT '归档时间（毫秒）',
    
    UNIQUE KEY uk_file_name (file_name),
    KEY idx_start_seq (start_biz_seq),
    KEY idx_end_seq (end_biz_seq),
    KEY idx_time_range (start_time, end_time),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WAL文件索引表';

-- =====================================================
-- 4. 数据校验任务表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_verify_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    task_id VARCHAR(64) NOT NULL COMMENT '任务ID（全局唯一）',
    
    -- 任务信息
    task_name VARCHAR(128) NOT NULL COMMENT '任务名称',
    verify_type VARCHAR(32) NOT NULL COMMENT '校验类型: LEDGER_SNAPSHOT/ORDER_TRADE/BALANCE_PNL',
    
    -- 校验范围
    start_time BIGINT COMMENT '起始时间（毫秒）',
    end_time BIGINT COMMENT '结束时间（毫秒）',
    
    -- 统计
    total_check_count BIGINT DEFAULT 0 COMMENT '总检查数',
    match_count BIGINT DEFAULT 0 COMMENT '一致数',
    mismatch_count BIGINT DEFAULT 0 COMMENT '不一致数',
    
    -- 状态
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RUNNING/COMPLETED/FAILED',
    
    -- 时间戳
    start_at BIGINT COMMENT '开始时间（毫秒）',
    end_at BIGINT COMMENT '结束时间（毫秒）',
    duration_ms BIGINT COMMENT '耗时（毫秒）',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    created_by VARCHAR(64) COMMENT '创建人',
    
    UNIQUE KEY uk_task_id (task_id),
    KEY idx_status (status),
    KEY idx_verify_type (verify_type),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据校验任务表';

-- =====================================================
-- 5. 数据校验结果表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_verify_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    task_id VARCHAR(64) NOT NULL COMMENT '任务ID',
    
    -- 校验对象
    verify_target VARCHAR(64) NOT NULL COMMENT '校验目标: 如 user_12345',
    target_type VARCHAR(32) NOT NULL COMMENT '目标类型: USER/SYMBOL/ORDER',
    
    -- 校验结果
    is_match TINYINT NOT NULL COMMENT '是否一致: 0-否 1-是',
    
    -- 预期值
    expected_value VARCHAR(512) COMMENT '预期值JSON',
    actual_value VARCHAR(512) COMMENT '实际值JSON',
    diff_detail TEXT COMMENT '差异详情',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
    
    KEY idx_task_id (task_id),
    KEY idx_verify_target (verify_target),
    KEY idx_is_match (is_match),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据校验结果表';

-- =====================================================
-- 6. 业务序列号追踪表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_biz_seq_tracking (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    
    -- 序列信息
    seq_name VARCHAR(64) NOT NULL COMMENT '序列名称',
    current_value BIGINT NOT NULL DEFAULT 0 COMMENT '当前值',
    
    -- 最后写入信息
    last_writer VARCHAR(64) COMMENT '最后写入者',
    last_write_time BIGINT COMMENT '最后写入时间',
    
    -- 时间戳
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    UNIQUE KEY uk_seq_name (seq_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务序列号追踪表';

-- =====================================================
-- 7. 重放配置表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_replay_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
    
    config_key VARCHAR(64) NOT NULL COMMENT '配置键',
    config_value VARCHAR(512) NOT NULL COMMENT '配置值',
    description VARCHAR(256) COMMENT '描述',
    
    -- 时间戳
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
    
    UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='重放配置表';

-- =====================================================
-- 初始化数据
-- =====================================================
INSERT INTO t_biz_seq_tracking (seq_name, current_value, updated_at) VALUES
('ledger_biz_seq', 0, UNIX_TIMESTAMP() * 1000),
('match_sequence_BTCUSDT', 0, UNIX_TIMESTAMP() * 1000),
('match_sequence_ETHUSDT', 0, UNIX_TIMESTAMP() * 1000)
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);

INSERT INTO t_replay_config (config_key, config_value, description, updated_at) VALUES
('batch_size', '1000', '重放批次大小', UNIX_TIMESTAMP() * 1000),
('retry_times', '3', '失败重试次数', UNIX_TIMESTAMP() * 1000),
('retry_interval_ms', '1000', '重试间隔（毫秒）', UNIX_TIMESTAMP() * 1000),
('max_concurrent_tasks', '5', '最大并发任务数', UNIX_TIMESTAMP() * 1000),
('wal_retention_days', '90', 'WAL文件保留天数', UNIX_TIMESTAMP() * 1000)
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);
