-- 账本分录表
CREATE TABLE IF NOT EXISTS `t_ledger_entry` (
  `entry_id` BIGINT NOT NULL COMMENT '分录ID',
  `account_id` BIGINT NOT NULL COMMENT '账户ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `asset` VARCHAR(20) NOT NULL COMMENT '资产类型',
  `direction` TINYINT NOT NULL COMMENT '记账方向 1=借 2=贷',
  `amount` BIGINT NOT NULL COMMENT '金额',
  `biz_type` TINYINT NOT NULL COMMENT '业务类型',
  `ref_id` VARCHAR(64) NOT NULL COMMENT '关联业务ID',
  `memo` VARCHAR(255) DEFAULT NULL COMMENT '备注',
  `create_time` BIGINT NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`entry_id`),
  KEY `idx_account_time` (`account_id`, `create_time`),
  KEY `idx_user_time` (`user_id`, `create_time`),
  KEY `idx_ref_id` (`ref_id`),
  KEY `idx_biz_type` (`biz_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账本分录表';

-- 账户余额表（快照）
CREATE TABLE IF NOT EXISTS `t_account_balance` (
  `account_id` BIGINT NOT NULL COMMENT '账户ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `asset` VARCHAR(20) NOT NULL COMMENT '资产类型',
  `available` BIGINT NOT NULL DEFAULT 0 COMMENT '可用余额',
  `frozen` BIGINT NOT NULL DEFAULT 0 COMMENT '冻结余额',
  `create_time` BIGINT NOT NULL COMMENT '创建时间',
  `update_time` BIGINT NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`account_id`),
  UNIQUE KEY `uk_user_asset` (`user_id`, `asset`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户余额表';

-- 手续费记录表
CREATE TABLE IF NOT EXISTS `t_fee_record` (
  `fee_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '手续费ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `asset` VARCHAR(20) NOT NULL COMMENT '资产类型',
  `amount` BIGINT NOT NULL COMMENT '手续费金额',
  `fee_type` VARCHAR(32) NOT NULL COMMENT '手续费类型',
  `ref_id` VARCHAR(64) NOT NULL COMMENT '关联业务ID',
  `create_time` BIGINT NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`fee_id`),
  KEY `idx_user_time` (`user_id`, `create_time`),
  KEY `idx_ref_id` (`ref_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手续费记录表';

-- 持仓表
CREATE TABLE IF NOT EXISTS `t_position` (
  `position_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '持仓ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `symbol` VARCHAR(20) NOT NULL COMMENT '交易对',
  `side` TINYINT NOT NULL COMMENT '持仓方向 1=多 2=空',
  `quantity` BIGINT NOT NULL COMMENT '持仓数量',
  `entry_price` BIGINT NOT NULL COMMENT '开仓均价',
  `margin` BIGINT NOT NULL COMMENT '保证金',
  `leverage` INT NOT NULL COMMENT '杠杆倍数',
  `unrealized_pnl` BIGINT DEFAULT 0 COMMENT '未实现盈亏',
  `realized_pnl` BIGINT DEFAULT 0 COMMENT '已实现盈亏',
  `liquidation_price` BIGINT DEFAULT 0 COMMENT '强平价格',
  `create_time` BIGINT NOT NULL COMMENT '创建时间',
  `update_time` BIGINT NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`position_id`),
  UNIQUE KEY `uk_user_symbol` (`user_id`, `symbol`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓表';

-- 初始化测试数据（可选）
-- 为用户1和用户2初始化10000 USDT余额
INSERT INTO `t_account_balance` 
  (`account_id`, `user_id`, `asset`, `available`, `frozen`, `create_time`, `update_time`)
VALUES
  (1, 1, 'USDT', 1000000000000, 0, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000),
  (2, 2, 'USDT', 1000000000000, 0, UNIX_TIMESTAMP(NOW()) * 1000, UNIX_TIMESTAMP(NOW()) * 1000)
ON DUPLICATE KEY UPDATE 
  `available` = VALUES(`available`),
  `update_time` = VALUES(`update_time`);




