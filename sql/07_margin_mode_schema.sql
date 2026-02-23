-- =====================================================
-- 全仓/逐仓模式系统 SQL Schema
-- =====================================================

-- 用户保证金配置表
CREATE TABLE IF NOT EXISTS t_user_margin_config (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id                 BIGINT NOT NULL COMMENT '用户ID',
    symbol                  VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 默认模式
    default_margin_mode     VARCHAR(16) NOT NULL DEFAULT 'CROSS' COMMENT '默认模式: CROSS/ISOLATED',
    
    -- 全仓配置
    cross_leverage          INT DEFAULT 10 COMMENT '全仓默认杠杆',
    cross_max_leverage      INT DEFAULT 125 COMMENT '全仓最大杠杆',
    
    -- 逐仓配置
    isolated_leverage       INT DEFAULT 10 COMMENT '逐仓默认杠杆',
    isolated_max_leverage   INT DEFAULT 125 COMMENT '逐仓最大杠杆',
    
    -- 风控配置
    max_position_num        INT DEFAULT 50 COMMENT '最大仓位数量',
    
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_user_symbol (user_id, symbol),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB COMMENT='用户保证金配置表';

-- 仓位保证金详情表
CREATE TABLE IF NOT EXISTS t_position_margin_detail (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    position_id             BIGINT NOT NULL COMMENT '仓位ID',
    user_id                 BIGINT NOT NULL COMMENT '用户ID',
    symbol                  VARCHAR(32) NOT NULL COMMENT '交易对',
    
    -- 保证金模式
    margin_mode             VARCHAR(16) NOT NULL COMMENT '模式: CROSS/ISOLATED',
    leverage                INT NOT NULL COMMENT '当前杠杆倍数',
    
    -- 逐仓专用
    isolated_margin         BIGINT COMMENT '逐仓保证金(仅逐仓模式)',
    isolated_available      BIGINT COMMENT '逐仓可用保证金(可取出)',
    
    -- 保证金计算
    position_margin         BIGINT NOT NULL COMMENT '仓位保证金',
    maint_margin            BIGINT NOT NULL COMMENT '维持保证金',
    maint_margin_rate       BIGINT NOT NULL COMMENT '维持保证金率(如500=5%)',
    
    -- 风险指标
    margin_ratio            BIGINT COMMENT '保证金率(仅逐仓)',
    liquidation_price       BIGINT COMMENT '预估强平价',
    
    -- 全仓专用
    cross_unrealized_pnl    BIGINT COMMENT '全仓未实现盈亏',
    
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_position_id (position_id),
    KEY idx_user_id (user_id),
    KEY idx_symbol (symbol)
) ENGINE=InnoDB COMMENT='仓位保证金详情表';

-- 保证金变动流水表
CREATE TABLE IF NOT EXISTS t_margin_change_log (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT NOT NULL COMMENT '用户ID',
    symbol              VARCHAR(32) NOT NULL COMMENT '交易对',
    position_id         BIGINT COMMENT '仓位ID',
    
    -- 变动信息
    change_type         VARCHAR(32) NOT NULL COMMENT '变动类型: OPEN/ADD/REMOVE/MODE_CHANGE/LIQUIDATION',
    margin_mode         VARCHAR(16) NOT NULL COMMENT '模式: CROSS/ISOLATED',
    amount              BIGINT NOT NULL COMMENT '变动金额(正=增加,负=减少)',
    before_amount       BIGINT NOT NULL COMMENT '变动前金额',
    after_amount        BIGINT NOT NULL COMMENT '变动后金额',
    
    -- 关联信息
    biz_id              BIGINT COMMENT '业务ID(订单ID等)',
    biz_type            VARCHAR(32) COMMENT '业务类型',
    
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    KEY idx_user_id (user_id),
    KEY idx_position_id (position_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB COMMENT='保证金变动流水表';

-- 全仓账户风险快照表
CREATE TABLE IF NOT EXISTS t_cross_margin_snapshot (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id                 BIGINT NOT NULL COMMENT '用户ID',
    
    -- 账户资金
    wallet_balance          BIGINT NOT NULL COMMENT '钱包余额',
    available_balance       BIGINT NOT NULL COMMENT '可用余额',
    
    -- 全仓仓位汇总
    total_position_value    BIGINT NOT NULL COMMENT '全仓仓位总价值',
    total_unrealized_pnl    BIGINT NOT NULL COMMENT '全仓总未实现盈亏',
    total_maint_margin      BIGINT NOT NULL COMMENT '全仓总维持保证金',
    
    -- 风险指标
    margin_balance          BIGINT NOT NULL COMMENT '保证金余额(钱包+未实现盈亏)',
    margin_ratio            BIGINT NOT NULL COMMENT '保证金率',
    available_margin        BIGINT NOT NULL COMMENT '可用保证金',
    
    -- 风控状态
    risk_level              VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '风险等级: NORMAL/WARNING/DANGER/LIQUIDATION',
    
    snapshot_time           BIGINT NOT NULL COMMENT '快照时间',
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE KEY uk_user_time (user_id, snapshot_time),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB COMMENT='全仓账户风险快照表';
