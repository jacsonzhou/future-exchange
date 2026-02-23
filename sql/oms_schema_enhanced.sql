-- OMS核心数据库表结构
-- 数据库：exchange_oms

-- 1. 订单表（订单当前态）
CREATE TABLE IF NOT EXISTS `oms_order` (
  `id` BIGINT NOT NULL COMMENT '订单ID（雪花算法）',
  
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `client_order_id` VARCHAR(64) NOT NULL COMMENT '客户端订单ID',
  
  `symbol` VARCHAR(32) NOT NULL COMMENT '交易对',
  `side` TINYINT NOT NULL COMMENT '买卖方向 0=BUY 1=SELL',
  `type` TINYINT NOT NULL COMMENT '订单类型 0=LIMIT 1=MARKET',
  
  `price` DECIMAL(32,16) NULL COMMENT '价格（限价单必填）',
  `quantity` DECIMAL(32,16) NOT NULL COMMENT '数量',
  `filled_quantity` DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '已成交数量',
  
  `status` TINYINT NOT NULL COMMENT '订单状态 0=NEW 1=PENDING_RISK 2=FROZEN 3=PARTIALLY_FILLED 4=FILLED 5=CANCELED 6=REJECTED',
  `time_in_force` VARCHAR(16) NOT NULL DEFAULT 'GTC' COMMENT '有效期类型',
  
  `risk_check_status` TINYINT NOT NULL DEFAULT 0 COMMENT '风控检查状态 0=未检查 1=通过 2=拒绝',
  `freeze_status` TINYINT NOT NULL DEFAULT 0 COMMENT '冻结状态 0=未冻结 1=已冻结',
  
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  
  `created_at` BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
  `updated_at` BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
  
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_client` (`user_id`, `client_order_id`),
  KEY `idx_user_symbol` (`user_id`, `symbol`),
  KEY `idx_status` (`status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 2. 订单事件表（事件流）
CREATE TABLE IF NOT EXISTS `oms_order_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '事件ID',
  
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `symbol` VARCHAR(32) NOT NULL COMMENT '交易对',
  
  `event_type` VARCHAR(32) NOT NULL COMMENT '事件类型',
  `event_source` VARCHAR(32) NOT NULL COMMENT '事件来源',
  
  `event_payload` JSON NOT NULL COMMENT '事件数据',
  `created_at` BIGINT NOT NULL COMMENT '创建时间',
  
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_symbol` (`symbol`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单事件表';

-- 3. 订单状态日志表（审计链）
CREATE TABLE IF NOT EXISTS `oms_order_state_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  
  `from_status` TINYINT NOT NULL COMMENT '原状态',
  `to_status` TINYINT NOT NULL COMMENT '新状态',
  
  `reason_code` VARCHAR(32) NOT NULL COMMENT '原因码',
  `reason_msg` VARCHAR(255) NULL COMMENT '原因描述',
  
  `operator` VARCHAR(32) NOT NULL COMMENT '操作者',
  `trace_id` VARCHAR(64) NULL COMMENT '追踪ID',
  
  `created_at` BIGINT NOT NULL COMMENT '创建时间',
  
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单状态日志表';

-- 4. 幂等key表
CREATE TABLE IF NOT EXISTS `oms_idempotent_key` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `idem_key` VARCHAR(64) NOT NULL COMMENT '幂等key',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `request_hash` VARCHAR(64) NOT NULL COMMENT '请求hash',
  
  `created_at` BIGINT NOT NULL COMMENT '创建时间',
  
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_key` (`user_id`, `idem_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='幂等key表';

