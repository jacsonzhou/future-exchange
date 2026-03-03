-- ======================================
-- Skill Engine 数据库设计
-- ======================================

-- Skill 定义表
CREATE TABLE IF NOT EXISTS t_skill (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    skill_id VARCHAR(64) NOT NULL COMMENT 'Skill 唯一标识',
    version VARCHAR(20) NOT NULL COMMENT '版本号',
    owner VARCHAR(64) NOT NULL COMMENT '所有者 (codex-agent, claude-code, user_xxx)',

    -- 状态管理
    status TINYINT NOT NULL COMMENT '0:开发中,1:测试中,2:稳定,3:可晋级,4:已下线',

    -- 性能指标
    skill_score DECIMAL(5,2) COMMENT 'Skill评分 (0-100)',
    avg_return DECIMAL(5,2) COMMENT '平均回撤 (%)',
    consistency_score DECIMAL(5,2) COMMENT '执行一致性 (0-100)',
    review_loop_rate DECIMAL(5,2) COMMENT '复盘闭环率 (%)',

    -- 约束条件 (定义3)
    max_leverage INT COMMENT '最大杠杆',
    max_order_notional BIGINT COMMENT '最大订单价值 (8位精度)',
    daily_loss_limit BIGINT COMMENT '每日亏损限制 (8位精度)',

    -- 目标 (定义4)
    min_skill_score DECIMAL(5,2) COMMENT '最小 Skill 评分目标',
    max_drawdown DECIMAL(5,2) COMMENT '最大回撤目标 (%)',
    target_consistency DECIMAL(5,2) COMMENT '一致性目标',

    -- 输入定义 (定义1)
    input_schema TEXT COMMENT 'JSON: 所需的 market/account/risk 字段',

    -- 输出定义 (定义2)
    output_schema TEXT COMMENT 'JSON: DecisionIntent 模板',

    -- 元数据
    description TEXT COMMENT 'Skill 描述',
    created_at BIGINT NOT NULL COMMENT '创建时间 (毫秒时间戳)',
    updated_at BIGINT NOT NULL COMMENT '更新时间 (毫秒时间戳)',

    -- 索引
    UNIQUE KEY uk_skill_version (skill_id, version),
    KEY idx_owner (owner),
    KEY idx_status (status),
    KEY idx_skill_score (skill_score)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill 定义表';

-- Skill 执行记录表
CREATE TABLE IF NOT EXISTS t_skill_execution (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    execution_id VARCHAR(64) NOT NULL COMMENT '执行ID',
    skill_id VARCHAR(64) NOT NULL,
    version VARCHAR(20) NOT NULL,
    user_id BIGINT NOT NULL COMMENT '用户ID',

    -- 决策意图
    decision_time BIGINT NOT NULL COMMENT '决策时间 (毫秒时间戳)',
    symbol VARCHAR(20) NOT NULL,
    side TINYINT NOT NULL COMMENT '0:LONG,1:SHORT',
    order_type TINYINT NOT NULL COMMENT '0:LIMIT,1:MARKET',
    intent_price BIGINT COMMENT '意图价格 (8位精度)',
    intent_quantity BIGINT NOT NULL COMMENT '意图数量 (8位精度)',
    intent_leverage INT COMMENT '意图杠杆',

    -- 实际执行
    order_id VARCHAR(64) COMMENT '实际订单ID',
    actual_price BIGINT COMMENT '实际成交价格 (8位精度)',
    actual_quantity BIGINT COMMENT '实际成交数量 (8位精度)',
    execution_time BIGINT COMMENT '执行时间 (毫秒时间戳)',

    -- 一致性分析
    price_deviation DECIMAL(5,2) COMMENT '价格偏差 (%)',
    quantity_deviation DECIMAL(5,2) COMMENT '数量偏差 (%)',
    time_delay BIGINT COMMENT '时间延迟 (毫秒)',
    consistency_score DECIMAL(5,2) COMMENT '本次执行一致性 (0-100)',

    -- 风控检查
    risk_check_passed BOOLEAN COMMENT '是否通过风控',
    risk_reject_reason VARCHAR(255) COMMENT '风控拒绝原因',

    -- 决策依据 (用于复盘)
    decision_metadata TEXT COMMENT 'JSON: 决策时的市场数据、账户数据、风险数据',

    created_at BIGINT NOT NULL,

    -- 索引
    UNIQUE KEY uk_execution_id (execution_id),
    KEY idx_skill_version (skill_id, version),
    KEY idx_user_id (user_id),
    KEY idx_decision_time (decision_time),
    KEY idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill 执行记录表';

-- A/B 测试表
CREATE TABLE IF NOT EXISTS t_skill_ab_test (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    test_id VARCHAR(64) NOT NULL COMMENT 'A/B 测试ID',
    test_name VARCHAR(128) NOT NULL COMMENT '测试名称',

    -- A/B 组配置
    group_a_skill_id VARCHAR(64) NOT NULL COMMENT 'A组 Skill',
    group_a_version VARCHAR(20) NOT NULL,
    group_b_skill_id VARCHAR(64) NOT NULL COMMENT 'B组 Skill',
    group_b_version VARCHAR(20) NOT NULL,

    -- 测试状态
    status TINYINT NOT NULL COMMENT '0:准备中,1:进行中,2:已完成,3:已取消',
    start_time BIGINT COMMENT '开始时间',
    end_time BIGINT COMMENT '结束时间',

    -- 测试结果
    group_a_skill_score DECIMAL(5,2) COMMENT 'A组 Skill评分',
    group_a_avg_return DECIMAL(5,2) COMMENT 'A组平均回撤',
    group_b_skill_score DECIMAL(5,2) COMMENT 'B组 Skill评分',
    group_b_avg_return DECIMAL(5,2) COMMENT 'B组平均回撤',
    winner VARCHAR(1) COMMENT 'A/B/NULL',

    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,

    UNIQUE KEY uk_test_id (test_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill A/B 测试表';

-- Skill 性能快照表 (用于历史趋势分析)
CREATE TABLE IF NOT EXISTS t_skill_performance_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    skill_id VARCHAR(64) NOT NULL,
    version VARCHAR(20) NOT NULL,
    snapshot_time BIGINT NOT NULL COMMENT '快照时间 (每天/每周)',

    -- 性能指标
    skill_score DECIMAL(5,2),
    avg_return DECIMAL(5,2),
    max_drawdown DECIMAL(5,2),
    consistency_score DECIMAL(5,2),
    win_rate DECIMAL(5,2) COMMENT '胜率 (%)',

    -- 执行统计
    total_executions INT COMMENT '总执行次数',
    risk_passed_count INT COMMENT '通过风控次数',

    created_at BIGINT NOT NULL,

    KEY idx_skill_version (skill_id, version),
    KEY idx_snapshot_time (snapshot_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill 性能快照表';

-- Agent Training v1: Skill 明细表（前端 skills 页面使用）
CREATE TABLE IF NOT EXISTS t_agent_skill (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    skill_id VARCHAR(64) NOT NULL COMMENT 'Skill 唯一标识',
    version VARCHAR(32) NOT NULL COMMENT '版本',
    owner VARCHAR(64) NOT NULL COMMENT '所有者',
    owner_type VARCHAR(16) NOT NULL COMMENT 'human/agent',
    scenario VARCHAR(32) NOT NULL DEFAULT 'baseline' COMMENT 'baseline/volatile',
    skill_score DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT 'Skill Score',
    drawdown_pct DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '回撤%',
    status VARCHAR(32) NOT NULL DEFAULT 'stable' COMMENT 'stable/optimize/passed',
    label VARCHAR(32) NOT NULL DEFAULT '稳定' COMMENT '展示标签',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',

    UNIQUE KEY uk_skill_version_scenario (skill_id, version, scenario),
    KEY idx_scenario_score (scenario, skill_score),
    KEY idx_owner (owner),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent训练Skill定义';

-- Agent Training v1: 决策日志表（前端 ai-console/training 使用）
CREATE TABLE IF NOT EXISTS t_agent_decision_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    decision_id VARCHAR(64) NOT NULL COMMENT '决策ID',
    strategy_version VARCHAR(64) NOT NULL COMMENT '策略版本',
    action VARCHAR(32) NOT NULL COMMENT 'OPEN_LONG/OPEN_SHORT/WAIT',
    confidence_pct DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '置信度%',
    status VARCHAR(32) NOT NULL COMMENT 'accepted/rejected/expired',
    feedback VARCHAR(255) COMMENT '执行反馈',
    event_time BIGINT NOT NULL COMMENT '事件时间',

    KEY idx_event_time (event_time),
    KEY idx_decision_id (decision_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent决策日志';

-- Agent Training v1: 周计划表（前端 my-growth 使用）
CREATE TABLE IF NOT EXISTS t_agent_weekly_plan (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    week_code VARCHAR(16) NOT NULL COMMENT '周编码，如 2026-W10',
    item_index INT NOT NULL COMMENT '条目序号',
    item_text VARCHAR(255) NOT NULL COMMENT '计划内容',
    updated_at BIGINT NOT NULL COMMENT '更新时间',

    UNIQUE KEY uk_user_week_item (user_id, week_code, item_index),
    KEY idx_user_week (user_id, week_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent成长周计划';
