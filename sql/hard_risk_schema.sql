-- Hard Risk Gate 数据库表结构
-- 数据库：exchange_risk

-- 1. 账户快照表
CREATE TABLE IF NOT EXISTS `risk_account_snapshot` (
  `account_id` BIGINT NOT NULL COMMENT '账户ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  
  `equity` DECIMAL(32,16) NOT NULL COMMENT '净资产',
  `balance` DECIMAL(32,16) NOT NULL COMMENT '余额',
  `available_margin` DECIMAL(32,16) NOT NULL COMMENT '可用保证金',
  `used_margin` DECIMAL(32,16) NOT NULL COMMENT '已用保证金',
  
  `margin_ratio` DECIMAL(16,8) NOT NULL COMMENT '保证金率',
  
  `account_status` TINYINT NOT NULL DEFAULT 0 COMMENT '账户状态 0=NORMAL 1=FROZEN 2=LIQUIDATING',
  
  `updated_at` BIGINT NOT NULL COMMENT '更新时间',
  
  PRIMARY KEY (`account_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Hard Risk账户快照';

-- 2. 持仓快照表
CREATE TABLE IF NOT EXISTS `risk_position_snapshot` (
  `position_id` BIGINT NOT NULL COMMENT '持仓ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `symbol` VARCHAR(32) NOT NULL COMMENT '交易对',
  
  `side` TINYINT NOT NULL COMMENT '方向 0=LONG 1=SHORT',
  `quantity` DECIMAL(32,16) NOT NULL COMMENT '数量',
  `entry_price` DECIMAL(32,16) NOT NULL COMMENT '开仓价',
  `mark_price` DECIMAL(32,16) NOT NULL COMMENT '标记价格',
  `unrealized_pnl` DECIMAL(32,16) NOT NULL COMMENT '未实现盈亏',
  `used_margin` DECIMAL(32,16) NOT NULL COMMENT '占用保证金',
  `leverage` INT NOT NULL COMMENT '杠杆倍数',
  `liquidation_price` DECIMAL(32,16) NULL COMMENT '强平价',
  `position_status` TINYINT NOT NULL DEFAULT 0 COMMENT '持仓状态 0=NORMAL 1=LIQUIDATING 2=CLOSED',
  
  `updated_at` BIGINT NOT NULL COMMENT '更新时间',
  
  PRIMARY KEY (`position_id`),
  UNIQUE KEY `uk_user_symbol` (`user_id`, `symbol`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Hard Risk持仓快照';

-- 3. 风控黑白名单
CREATE TABLE IF NOT EXISTS `risk_user_list` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  
  `list_type` TINYINT NOT NULL COMMENT '名单类型 0=BLACK 1=WHITE',
  
  `reason` VARCHAR(128) NULL COMMENT '原因',
  `created_at` BIGINT NOT NULL COMMENT '创建时间',
  
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控黑白名单';

-- 4. 风控审计日志
CREATE TABLE IF NOT EXISTS `risk_check_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `symbol` VARCHAR(32) NOT NULL COMMENT '交易对',
  
  `result` TINYINT NOT NULL COMMENT '风控结果 0=PASS 1=REJECT',
  `reject_reason` TINYINT NULL COMMENT '拒绝原因',
  `reject_message` VARCHAR(255) NULL COMMENT '拒绝信息',
  
  `required_margin` DECIMAL(32,16) NULL COMMENT '所需保证金',
  `available_margin` DECIMAL(32,16) NULL COMMENT '可用保证金',
  
  `trace_id` VARCHAR(64) NULL COMMENT '追踪ID',
  `created_at` BIGINT NOT NULL COMMENT '创建时间',
  
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Hard Risk风控审计日志';

-- 5. 交易对配置表
CREATE TABLE IF NOT EXISTS `risk_symbol_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `symbol` VARCHAR(32) NOT NULL COMMENT '交易对',
  
  `max_leverage` INT NOT NULL DEFAULT 100 COMMENT '最大杠杆',
  `max_position_qty` DECIMAL(32,16) NOT NULL COMMENT '最大持仓数量',
  `max_price_deviation_pct` DECIMAL(16,8) NOT NULL DEFAULT 0.10 COMMENT '最大价格偏离百分比',
  
  `min_order_qty` DECIMAL(32,16) NOT NULL COMMENT '最小下单数量',
  `max_order_qty` DECIMAL(32,16) NOT NULL COMMENT '最大下单数量',
  
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态 0=禁用 1=启用',
  
  `created_at` BIGINT NOT NULL COMMENT '创建时间',
  `updated_at` BIGINT NOT NULL COMMENT '更新时间',
  
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_symbol` (`symbol`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易对风控配置';

-- 初始化示例数据
INSERT INTO `risk_symbol_config` (`symbol`, `max_leverage`, `max_position_qty`, `max_price_deviation_pct`, `min_order_qty`, `max_order_qty`, `status`, `created_at`, `updated_at`)
VALUES 
('BTCUSDT', 125, 1000.00000000, 0.10000000, 0.00100000, 100.00000000, 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
('ETHUSDT', 100, 10000.00000000, 0.10000000, 0.01000000, 1000.00000000, 1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000);

