-- =====================================================
-- ADL自动减仓系统 SQL Schema
-- =====================================================

-- ADL排名队列表
CREATE TABLE IF NOT EXISTS t_adl_ranking_queue (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    position_id         BIGINT NOT NULL COMMENT '仓位ID',
    
    -- 仓位信息
    side                VARCHAR(8) NOT NULL COMMENT '方向: LONG/SHORT',
    qty                 BIGINT NOT NULL COMMENT '持仓数量',
    entry_price         BIGINT NOT NULL COMMENT '开仓均价',
    position_value      BIGINT NOT NULL COMMENT '持仓价值',
    
    -- 排名指标
    margin              BIGINT NOT NULL COMMENT '仓位保证金',
    unrealized_pnl      BIGINT NOT NULL COMMENT '未实现盈亏',
    pnl_ratio           BIGINT NOT NULL COMMENT '盈亏比例(如1000=10%)',
    effective_leverage  INT NOT NULL COMMENT '有效杠杆',
    adl_score           BIGINT NOT NULL COMMENT 'ADL得分',
    adl_rank            INT NOT NULL COMMENT 'ADL排名',
    
    -- 状态
    status              VARCHAR(16) DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/EXECUTED/REMOVED',
    
    calc_time           BIGINT NOT NULL COMMENT '计算时间',
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_position (position_id),
    KEY idx_symbol_side (symbol, side),
    KEY idx_adl_score (symbol, side, adl_score DESC),
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='ADL排名队列表';

-- ADL执行记录表
CREATE TABLE IF NOT EXISTS t_adl_execution (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    adl_id                  BIGINT NOT NULL COMMENT 'ADL执行ID',
    
    -- 被ADL用户(盈利方)
    target_user_id          BIGINT NOT NULL COMMENT '被ADL用户ID',
    target_position_id      BIGINT NOT NULL COMMENT '被ADL仓位ID',
    target_side             VARCHAR(8) NOT NULL COMMENT '被ADL方向',
    
    -- 触发ADL的穿仓用户
    source_user_id          BIGINT NOT NULL COMMENT '穿仓用户ID',
    source_liquidation_id   BIGINT NOT NULL COMMENT '强平记录ID',
    
    -- 交易对
    symbol                  VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- ADL执行详情
    adl_price               BIGINT NOT NULL COMMENT 'ADL执行价格(破产价)',
    adl_qty                 BIGINT NOT NULL COMMENT 'ADL数量',
    adl_amount              BIGINT NOT NULL COMMENT 'ADL金额',
    
    -- 盈亏计算
    target_pnl              BIGINT NOT NULL COMMENT '被ADL用户盈亏',
    source_bankrupt_loss    BIGINT NOT NULL COMMENT '穿仓用户破产损失',
    insurance_cover         BIGINT COMMENT '保险基金赔付金额',
    
    -- 状态
    status                  VARCHAR(16) DEFAULT 'COMPLETED' COMMENT '状态',
    
    executed_at             BIGINT NOT NULL COMMENT '执行时间',
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_adl_id (adl_id),
    KEY idx_target_user (target_user_id),
    KEY idx_source_user (source_user_id),
    KEY idx_symbol (symbol),
    KEY idx_executed_at (executed_at)
) ENGINE=InnoDB COMMENT='ADL执行记录表';

-- 保险基金表
CREATE TABLE IF NOT EXISTS t_insurance_fund (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol              VARCHAR(32) COMMENT '交易对(全局则为空)',
    currency            VARCHAR(16) NOT NULL COMMENT '币种',
    
    -- 余额
    balance             BIGINT NOT NULL COMMENT '当前余额',
    total_income        BIGINT NOT NULL COMMENT '累计收入',
    total_expense       BIGINT NOT NULL COMMENT '累计支出',
    
    -- 统计
    cover_count         INT DEFAULT 0 COMMENT '赔付次数',
    adl_trigger_count   INT DEFAULT 0 COMMENT '触发ADL次数',
    
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_symbol_currency (symbol, currency)
) ENGINE=InnoDB COMMENT='保险基金表';

-- 保险基金流水表
CREATE TABLE IF NOT EXISTS t_insurance_fund_log (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol              VARCHAR(32) COMMENT '交易对',
    currency            VARCHAR(16) NOT NULL COMMENT '币种',
    
    -- 变动信息
    change_type         VARCHAR(32) NOT NULL COMMENT '变动类型: LIQUIDATION_SURPLUS/PLATFORM_SUBSIDY/FEE_CONTRIBUTION/COVER_BANKRUPT/ADL_COMPENSATION',
    amount              BIGINT NOT NULL COMMENT '变动金额(正=收入,负=支出)',
    balance_before      BIGINT NOT NULL COMMENT '变动前余额',
    balance_after       BIGINT NOT NULL COMMENT '变动后余额',
    
    -- 关联信息
    ref_id              BIGINT COMMENT '关联ID(强平ID等)',
    ref_type            VARCHAR(32) COMMENT '关联类型',
    description         VARCHAR(512) COMMENT '说明',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    KEY idx_symbol (symbol),
    KEY idx_type (change_type),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB COMMENT='保险基金流水表';

-- 穿仓记录表
CREATE TABLE IF NOT EXISTS t_bankruptcy_record (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    liquidation_id      BIGINT NOT NULL COMMENT '强平记录ID',
    user_id             BIGINT NOT NULL COMMENT '穿仓用户ID',
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 穿仓信息
    side                VARCHAR(8) NOT NULL COMMENT '方向',
    bankrupt_price      BIGINT NOT NULL COMMENT '破产价格',
    bankrupt_qty        BIGINT NOT NULL COMMENT '穿仓数量',
    bankrupt_loss       BIGINT NOT NULL COMMENT '穿仓损失金额',
    
    -- 处理状态
    handle_type         VARCHAR(16) COMMENT '处理方式: INSURANCE/ADL/PENDING',
    insurance_cover     BIGINT COMMENT '保险基金赔付金额',
    adl_cover           BIGINT COMMENT 'ADL分摊金额',
    
    status              VARCHAR(16) DEFAULT 'PENDING' COMMENT '状态: PENDING/PROCESSING/COMPLETED/FAILED',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMP COMMENT '完成时间',
    
    UNIQUE KEY uk_liquidation (liquidation_id),
    KEY idx_user_id (user_id),
    KEY idx_symbol (symbol),
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='穿仓记录表';

-- 初始化保险基金
INSERT INTO t_insurance_fund (symbol, currency, balance, total_income, total_expense) VALUES
(NULL, 'USDT', 100000000000000, 100000000000000, 0),
('BTCUSDT', 'USDT', 50000000000000, 50000000000000, 0),
('ETHUSDT', 'USDT', 30000000000000, 30000000000000, 0)
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP;
