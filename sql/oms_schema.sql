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
  `client_order_id` VARCHAR(64) DEFAULT NULL COMMENT '客户端订单ID',
  `create_time` BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
  `update_time` BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
  PRIMARY KEY (`order_id`),
  KEY `idx_user_symbol` (`user_id`, `symbol`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`),
  UNIQUE KEY `uk_client_order_id` (`client_order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

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


