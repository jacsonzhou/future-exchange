-- OMS 订单表
CREATE TABLE IF NOT EXISTS `t_order` (
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `symbol` VARCHAR(20) NOT NULL COMMENT '交易对',
  `side` TINYINT NOT NULL COMMENT '买卖方向 1=买 2=卖',
  `order_type` TINYINT NOT NULL COMMENT '订单类型 1=限价 2=市价',
  `price` BIGINT DEFAULT NULL COMMENT '价格（long格式）',
  `quantity` BIGINT NOT NULL COMMENT '数量（long格式）',
  `filled_quantity` BIGINT NOT NULL DEFAULT 0 COMMENT '已成交数量',
  `status` TINYINT NOT NULL COMMENT '订单状态',
  `leverage` INT DEFAULT 10 COMMENT '杠杆倍数',
  `execution_mode` VARCHAR(32) NOT NULL DEFAULT 'MATCH_ENGINE' COMMENT '执行模式: MATCH_ENGINE/CFD_DEALER',
  `liquidity_source` VARCHAR(32) DEFAULT NULL COMMENT '流动性来源: BINANCE_REF 等',
  `reference_topic` VARCHAR(128) DEFAULT NULL COMMENT '参考行情来源 Topic',
  `reference_offset` BIGINT DEFAULT NULL COMMENT '参考行情来源 offset',
  `reference_event_time` BIGINT DEFAULT NULL COMMENT '参考行情事件时间（毫秒）',
  `reference_best_bid` DECIMAL(30,8) DEFAULT NULL COMMENT '参考最优买价',
  `reference_best_ask` DECIMAL(30,8) DEFAULT NULL COMMENT '参考最优卖价',
  `reference_vwap_price` DECIMAL(30,8) DEFAULT NULL COMMENT '参考成交均价',
  `slippage_bps` INT DEFAULT NULL COMMENT '滑点（bps）',
  `client_order_id` VARCHAR(64) DEFAULT NULL COMMENT '客户端订单ID',
  `create_time` BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
  `update_time` BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
  PRIMARY KEY (`order_id`),
  KEY `idx_user_symbol` (`user_id`, `symbol`),
  KEY `idx_exec_symbol_status_create` (`execution_mode`, `symbol`, `status`, `create_time`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`),
  UNIQUE KEY `uk_client_order_id` (`client_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- CFD 工作订单表（CFD_LIMIT 挂单）
CREATE TABLE IF NOT EXISTS `t_cfd_working_order` (
  `order_id` BIGINT NOT NULL COMMENT 'OMS订单ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `symbol` VARCHAR(20) NOT NULL COMMENT '交易对',
  `side` TINYINT NOT NULL COMMENT '方向: 1=BUY 2=SELL',
  `limit_price` BIGINT DEFAULT NULL COMMENT '限价（long格式）',
  `quantity` BIGINT NOT NULL COMMENT '原始数量（long格式）',
  `remaining_quantity` BIGINT NOT NULL COMMENT '剩余数量（long格式）',
  `status` VARCHAR(16) NOT NULL COMMENT '状态: WORKING/FILLED/CANCELED/EXPIRED',
  `trigger_source` VARCHAR(64) DEFAULT NULL COMMENT '触发来源',
  `trigger_event_time` BIGINT DEFAULT NULL COMMENT '最近触发事件时间（毫秒）',
  `create_time` BIGINT NOT NULL COMMENT '创建时间（毫秒）',
  `update_time` BIGINT NOT NULL COMMENT '更新时间（毫秒）',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`order_id`),
  KEY `idx_cfd_symbol_status_update` (`symbol`, `status`, `update_time`),
  KEY `idx_cfd_user_symbol_status` (`user_id`, `symbol`, `status`),
  KEY `idx_cfd_status_trigger_time` (`status`, `trigger_event_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CFD工作订单表';

-- 订单事件表（用于审计和追踪）
CREATE TABLE IF NOT EXISTS `t_order_event` (
  `event_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '事件ID',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `event_type` VARCHAR(32) NOT NULL COMMENT '事件类型',
  `old_status` TINYINT DEFAULT NULL COMMENT '旧状态',
  `new_status` TINYINT DEFAULT NULL COMMENT '新状态',
  `data` TEXT COMMENT '事件数据（JSON）',
  `create_time` BIGINT NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`event_id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单事件表';

-- 成交记录表
CREATE TABLE IF NOT EXISTS `t_trade` (
  `trade_id` BIGINT NOT NULL COMMENT '成交ID',
  `symbol` VARCHAR(20) NOT NULL COMMENT '交易对',
  `price` BIGINT NOT NULL COMMENT '成交价格',
  `quantity` BIGINT NOT NULL COMMENT '成交数量',
  `buy_order_id` BIGINT NOT NULL COMMENT '买方订单ID',
  `buy_user_id` BIGINT NOT NULL COMMENT '买方用户ID',
  `sell_order_id` BIGINT NOT NULL COMMENT '卖方订单ID',
  `sell_user_id` BIGINT NOT NULL COMMENT '卖方用户ID',
  `taker_side` TINYINT NOT NULL COMMENT '主动方',
  `create_time` BIGINT NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`trade_id`),
  KEY `idx_symbol_time` (`symbol`, `create_time`),
  KEY `idx_buy_user` (`buy_user_id`, `create_time`),
  KEY `idx_sell_user` (`sell_user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成交记录表';

