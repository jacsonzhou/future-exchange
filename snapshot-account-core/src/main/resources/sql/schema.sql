    -- =====================================================
    -- Snapshot Account Core 数据库表结构
    -- 数据库: exchange_snapshot
    -- 职责: 账户快照维护、为风控提供查询接口
    -- =====================================================

    CREATE DATABASE IF NOT EXISTS exchange_snapshot
        DEFAULT CHARACTER SET utf8mb4
        COLLATE utf8mb4_unicode_ci;

    USE exchange_snapshot;

    -- =====================================================
    -- 1. 账户快照主表
    -- =====================================================
    CREATE TABLE IF NOT EXISTS t_account_snapshot (
        user_id BIGINT PRIMARY KEY COMMENT '用户ID',
        currency VARCHAR(16) NOT NULL DEFAULT 'USDT' COMMENT '币种',

        -- 余额字段
        available DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '可用余额',
        frozen DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '冻结余额（下单占用）',
        position_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '持仓占用保证金',
        order_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '挂单占用保证金',

        -- 盈亏
        unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '未实现盈亏',
        realized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '已实现盈亏',

        -- 权益（风控核心）
        equity DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '账户权益',
        wallet_balance DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '钱包余额',

        -- 保证金率
        margin_ratio DECIMAL(16,8) COMMENT '保证金率',
        available_margin DECIMAL(32,16) COMMENT '可用保证金',

        -- 同步位点（幂等/断点续传）
        last_biz_seq BIGINT NOT NULL DEFAULT 0 COMMENT '最后同步的bizSeq',
        last_entry_id BIGINT COMMENT '最后同步的entryId',
        last_trade_id VARCHAR(64) COMMENT '最后同步的tradeId',

        -- 校验
        checksum VARCHAR(64) COMMENT '数据校验和',

        -- 并发控制
        version INT NOT NULL DEFAULT 0 COMMENT '版本号（乐观锁）',

        -- 时间戳
        created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
        updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',

        UNIQUE KEY uk_user_currency (user_id, currency),
        INDEX idx_last_biz_seq (last_biz_seq),
        INDEX idx_updated (updated_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户快照主表';

    -- =====================================================
    -- 2. 账户快照历史表（按天归档）
    -- =====================================================
    CREATE TABLE IF NOT EXISTS t_account_snapshot_history (
        id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
        user_id BIGINT NOT NULL COMMENT '用户ID',
        currency VARCHAR(16) NOT NULL COMMENT '币种',
        snapshot_date INT NOT NULL COMMENT '快照日期(YYYYMMDD)',

        -- 余额字段
        available DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '可用余额',
        frozen DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '冻结余额',
        position_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '持仓保证金',

        -- 盈亏
        unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '未实现盈亏',
        realized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '已实现盈亏',
        equity DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '账户权益',

        -- 时间戳
        created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',

        UNIQUE KEY uk_user_date (user_id, currency, snapshot_date),
        INDEX idx_snapshot_date (snapshot_date),
        INDEX idx_user_id (user_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户快照历史表';

    -- =====================================================
    -- 3. 账户变更流水表（用于追踪资金变动）
    -- =====================================================
    CREATE TABLE IF NOT EXISTS t_account_change_log (
        id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
        user_id BIGINT NOT NULL COMMENT '用户ID',
        currency VARCHAR(16) NOT NULL COMMENT '币种',

        -- 变更信息
        change_type VARCHAR(32) NOT NULL COMMENT '变更类型: ORDER_FREEZE/ORDER_UNFREEZE/TRADE_SETTLE/FUNDING_FEE/LIQUIDATION/ADL/DEPOSIT/WITHDRAW',
        amount DECIMAL(32,16) NOT NULL COMMENT '变更金额（正=增加，负=减少）',

        -- 变更前后
        available_before DECIMAL(32,16) COMMENT '变更前可用余额',
        available_after DECIMAL(32,16) COMMENT '变更后可用余额',
        frozen_before DECIMAL(32,16) COMMENT '变更前冻结余额',
        frozen_after DECIMAL(32,16) COMMENT '变更后冻结余额',

        -- 关联信息
        ref_id VARCHAR(64) COMMENT '关联ID（订单ID/成交ID等）',
        ref_type VARCHAR(32) COMMENT '关联类型',
        biz_seq BIGINT COMMENT '业务序列号',

        -- 时间戳
        created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',

        INDEX idx_user (user_id),
        INDEX idx_change_type (change_type),
        INDEX idx_ref_id (ref_id),
        INDEX idx_biz_seq (biz_seq),
        INDEX idx_created_at (created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户变更流水表';

    -- =====================================================
    -- 4. 同步偏移表（用于断点续传）
    -- =====================================================
    CREATE TABLE IF NOT EXISTS t_sync_offset (
        id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
        consumer_group VARCHAR(64) NOT NULL COMMENT '消费者组',
        topic VARCHAR(64) NOT NULL COMMENT 'Topic',
        partition_num INT NOT NULL COMMENT '分区号',

        -- 偏移信息
        current_offset BIGINT NOT NULL DEFAULT 0 COMMENT '当前偏移量',
        last_biz_seq BIGINT NOT NULL DEFAULT 0 COMMENT '最后处理的bizSeq',

        -- 时间戳
        updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',

        UNIQUE KEY uk_consumer_topic_partition (consumer_group, topic, partition_num),
        INDEX idx_updated (updated_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步偏移表';

    -- =====================================================
    -- 5. 快照同步状态表
    -- =====================================================
    CREATE TABLE IF NOT EXISTS t_snapshot_sync_status (
        id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'ID',
        user_id BIGINT NOT NULL COMMENT '用户ID',

        -- 同步状态
        sync_status TINYINT NOT NULL DEFAULT 0 COMMENT '同步状态: 0=正常 1=延迟 2=异常',
        lag_ms BIGINT COMMENT '延迟毫秒数',

        -- 最后同步信息
        last_sync_time BIGINT COMMENT '最后同步时间',
        last_sync_biz_seq BIGINT COMMENT '最后同步bizSeq',

        -- 统计
        total_sync_count BIGINT DEFAULT 0 COMMENT '总同步次数',
        failed_sync_count BIGINT DEFAULT 0 COMMENT '失败次数',

        -- 时间戳
        updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',

        UNIQUE KEY uk_user_id (user_id),
        INDEX idx_sync_status (sync_status),
        INDEX idx_updated (updated_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='快照同步状态表';

    -- =====================================================
    -- 初始化数据
    -- =====================================================
    -- 初始化测试用户账户快照
    INSERT INTO t_account_snapshot (
        user_id, currency, available, frozen, position_margin,
        unrealized_pnl, realized_pnl, equity, wallet_balance,
        last_biz_seq, version, created_at, updated_at
    ) VALUES
    (10001, 'USDT', 100000.00000000, 0, 0, 0, 0, 100000.00000000, 100000.00000000, 0, 0, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
    (10002, 'USDT', 50000.00000000, 0, 0, 0, 0, 50000.00000000, 50000.00000000, 0, 0, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000)
    ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);
