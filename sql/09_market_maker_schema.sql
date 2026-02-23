-- =====================================================
-- 做市商系统 SQL Schema
-- =====================================================

-- 做市商申请表
CREATE TABLE IF NOT EXISTS t_market_maker_application (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    
    -- 申请信息
    company_name        VARCHAR(128) COMMENT '公司名',
    contact_name        VARCHAR(64) NOT NULL COMMENT '联系人',
    contact_email       VARCHAR(128) NOT NULL COMMENT '联系邮箱',
    contact_phone       VARCHAR(32) COMMENT '联系电话',
    
    -- 资质证明
    license_no          VARCHAR(64) COMMENT '牌照号码',
    license_doc         VARCHAR(256) COMMENT '牌照文件URL',
    
    -- 历史业绩
    other_exchanges     VARCHAR(512) COMMENT '其他交易所做市经历',
    monthly_volume      BIGINT COMMENT '月均交易量(USD)',
    
    -- 申请状态
    status              VARCHAR(16) DEFAULT 'PENDING' COMMENT '状态: PENDING/APPROVED/REJECTED/REVOKED',
    level               INT COMMENT '获批等级',
    approved_by         BIGINT COMMENT '审核人ID',
    approved_at         TIMESTAMP COMMENT '审核时间',
    reject_reason       VARCHAR(512) COMMENT '拒绝原因',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_user_id (user_id),
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='做市商申请表';

-- 做市商信息表
CREATE TABLE IF NOT EXISTS t_market_maker (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    
    -- 等级信息
    level               INT NOT NULL DEFAULT 1 COMMENT '做市商等级',
    status              VARCHAR(16) DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/SUSPENDED/TERMINATED',
    
    -- 费率配置
    maker_fee_rate      BIGINT NOT NULL COMMENT 'Maker费率(如-2=-0.02%)',
    taker_fee_rate      BIGINT NOT NULL COMMENT 'Taker费率',
    
    -- 限制配置
    api_limit_per_sec   INT NOT NULL COMMENT 'API频率限制(每秒)',
    max_order_count     INT NOT NULL COMMENT '最大挂单数量',
    max_position_value  BIGINT COMMENT '最大持仓价值',
    
    -- 考核周期
    eval_period_start   DATE COMMENT '考核周期开始',
    eval_period_end     DATE COMMENT '考核周期结束',
    
    -- 账户信息
    mm_account_id       BIGINT COMMENT '做市商专用账户ID',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_user_id (user_id),
    KEY idx_level (level),
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='做市商信息表';

-- 做市商考核指标表
CREATE TABLE IF NOT EXISTS t_mm_performance (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id                 BIGINT NOT NULL COMMENT '用户ID',
    symbol                  VARCHAR(32) NOT NULL COMMENT '交易对',
    period_date             DATE NOT NULL COMMENT '考核日期',
    
    -- 交易统计
    total_order_count       INT DEFAULT 0 COMMENT '总下单数',
    filled_order_count      INT DEFAULT 0 COMMENT '成交订单数',
    cancelled_order_count   INT DEFAULT 0 COMMENT '撤单数',
    maker_volume            BIGINT DEFAULT 0 COMMENT 'Maker成交量',
    taker_volume            BIGINT DEFAULT 0 COMMENT 'Taker成交量',
    
    -- 质量指标
    quote_time_ratio        BIGINT COMMENT '挂单时间占比(如8000=80%)',
    avg_spread              BIGINT COMMENT '平均价差',
    avg_depth_bid           BIGINT COMMENT '平均买方深度',
    avg_depth_ask           BIGINT COMMENT '平均卖方深度',
    
    -- 计算结果
    score                   INT COMMENT '综合得分',
    is_qualified            TINYINT DEFAULT 0 COMMENT '是否达标',
    
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_user_symbol_date (user_id, symbol, period_date),
    KEY idx_user_id (user_id),
    KEY idx_period_date (period_date)
) ENGINE=InnoDB COMMENT='做市商考核指标表';

-- 做市商费率流水表
CREATE TABLE IF NOT EXISTS t_mm_fee_log (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    trade_id            BIGINT NOT NULL COMMENT '成交ID',
    
    -- 费率信息
    fee_type            VARCHAR(16) NOT NULL COMMENT '费率类型: MAKER_REBATE/MAKER_FEE/TAKER_FEE',
    fee_amount          BIGINT NOT NULL COMMENT '手续费金额(负=返佣)',
    fee_rate            BIGINT NOT NULL COMMENT '费率',
    
    -- 成交信息
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    side                VARCHAR(8) NOT NULL COMMENT '方向',
    price               BIGINT NOT NULL COMMENT '价格',
    quantity            BIGINT NOT NULL COMMENT '数量',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    KEY idx_user_id (user_id),
    KEY idx_trade_id (trade_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB COMMENT='做市商费率流水表';

-- 批量订单记录表
CREATE TABLE IF NOT EXISTS t_mm_batch_order (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id            VARCHAR(64) NOT NULL COMMENT '批次ID',
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    
    -- 批次信息
    order_count         INT NOT NULL COMMENT '订单数量',
    success_count       INT NOT NULL COMMENT '成功数量',
    fail_count          INT NOT NULL COMMENT '失败数量',
    
    -- 状态
    status              VARCHAR(16) DEFAULT 'PROCESSING' COMMENT '状态: PROCESSING/COMPLETED',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMP COMMENT '完成时间',
    
    UNIQUE KEY uk_batch_id (batch_id),
    KEY idx_user_id (user_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB COMMENT='批量订单记录表';

-- 初始化做市商等级配置
INSERT INTO t_market_maker (user_id, level, status, maker_fee_rate, taker_fee_rate, api_limit_per_sec, max_order_count) VALUES
(88888, 3, 'ACTIVE', -5, 3, 5000, 500)
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP;
