-- 资金费率配置表
CREATE TABLE IF NOT EXISTS t_funding_rate_config (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol          VARCHAR(32) NOT NULL COMMENT '交易对',
    settlement_interval INT DEFAULT 28800 COMMENT '结算间隔(秒)，默认8小时',
    max_rate        BIGINT NOT NULL DEFAULT 750000 COMMENT '最大资金费率(如750000=0.75%)',
    min_rate        BIGINT NOT NULL DEFAULT -750000 COMMENT '最小资金费率(如-750000=-0.75%)',
    interest_rate   BIGINT DEFAULT 1000 COMMENT '利率差(如0.01%=1000)',
    status          TINYINT DEFAULT 1 COMMENT '状态: 1启用 0禁用',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金费率配置表';

-- 资金费率历史表
CREATE TABLE IF NOT EXISTS t_funding_rate_history (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol          VARCHAR(32) NOT NULL COMMENT '交易对',
    funding_time    BIGINT NOT NULL COMMENT '结算时间戳(毫秒)',
    funding_rate    BIGINT NOT NULL COMMENT '资金费率(如50000=0.005%)',
    mark_price      BIGINT NOT NULL COMMENT '标记价格',
    index_price     BIGINT NOT NULL COMMENT '指数价格',
    premium_index   BIGINT NOT NULL COMMENT '溢价指数',
    total_long_qty  BIGINT NOT NULL DEFAULT 0 COMMENT '多头总持仓量',
    total_short_qty BIGINT NOT NULL DEFAULT 0 COMMENT '空头总持仓量',
    settlement_amount BIGINT DEFAULT 0 COMMENT '本次结算总金额',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_symbol_time (symbol, funding_time),
    KEY idx_funding_time (funding_time),
    KEY idx_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金费率历史表';

-- 用户资金费用明细表
CREATE TABLE IF NOT EXISTS t_user_funding_fee (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL COMMENT '用户ID',
    symbol          VARCHAR(32) NOT NULL COMMENT '交易对',
    funding_time    BIGINT NOT NULL COMMENT '结算时间戳(毫秒)',
    side            VARCHAR(8) NOT NULL COMMENT '持仓方向: LONG/SHORT',
    position_qty    BIGINT NOT NULL COMMENT '持仓数量',
    mark_price      BIGINT NOT NULL COMMENT '标记价格',
    funding_rate    BIGINT NOT NULL COMMENT '资金费率',
    funding_fee     BIGINT NOT NULL COMMENT '资金费用(正=支付,负=收取)',
    margin_mode     VARCHAR(16) NOT NULL COMMENT '保证金模式: ISOLATED/CROSS',
    status          VARCHAR(16) DEFAULT 'SETTLED' COMMENT '状态',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_symbol_time (user_id, symbol, funding_time),
    KEY idx_funding_time (funding_time),
    KEY idx_user_id (user_id),
    KEY idx_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户资金费用明细表';

-- 预估资金费率实时表
CREATE TABLE IF NOT EXISTS t_funding_rate_estimate (
    symbol          VARCHAR(32) PRIMARY KEY COMMENT '交易对',
    next_funding_time BIGINT NOT NULL COMMENT '下次结算时间',
    estimated_rate  BIGINT NOT NULL COMMENT '预估资金费率',
    mark_price      BIGINT NOT NULL COMMENT '当前标记价格',
    index_price     BIGINT NOT NULL COMMENT '当前指数价格',
    premium_index   BIGINT NOT NULL COMMENT '当前溢价指数',
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_next_funding_time (next_funding_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预估资金费率实时表';

-- 初始化数据
INSERT INTO t_funding_rate_config (symbol, settlement_interval, max_rate, min_rate, interest_rate, status)
VALUES
    ('BTCUSDT', 28800, 750000, -750000, 1000, 1),
    ('ETHUSDT', 28800, 750000, -750000, 1000, 1)
ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP;
