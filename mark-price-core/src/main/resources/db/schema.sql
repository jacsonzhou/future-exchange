-- 标记价格服务数据库脚本

USE exchange_market;

-- 标记价格历史表
CREATE TABLE IF NOT EXISTS t_mark_price (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    mark_price BIGINT NOT NULL COMMENT '标记价格（8位精度）',
    index_price BIGINT NOT NULL COMMENT '指数价格（8位精度）',
    funding_rate BIGINT COMMENT '资金费率',
    next_funding_time BIGINT COMMENT '下次结算时间',
    timestamp BIGINT NOT NULL COMMENT '数据时间戳',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_symbol_time (symbol, timestamp),
    KEY idx_timestamp (timestamp)
) ENGINE=InnoDB COMMENT='标记价格历史表';
