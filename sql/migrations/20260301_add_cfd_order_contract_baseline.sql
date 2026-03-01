-- CFD mode C1 baseline migration
-- Scope:
-- 1) t_order: add execution/reference metadata columns
-- 2) t_order: add execution-mode query index
-- 3) create t_cfd_working_order with hot-path indexes

USE exchange_oms;

SET @schema_name = 'exchange_oms';

-- -----------------------------
-- t_order: execution_mode
-- -----------------------------
SET @ddl = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_order ADD COLUMN execution_mode VARCHAR(32) NOT NULL DEFAULT ''MATCH_ENGINE'' COMMENT ''执行模式: MATCH_ENGINE/CFD_DEALER'' AFTER leverage',
    'SELECT ''execution_mode column already exists'' AS message'
  )
  FROM information_schema.columns
  WHERE table_schema = @schema_name
    AND table_name = 't_order'
    AND column_name = 'execution_mode'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- t_order: liquidity_source
SET @ddl = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_order ADD COLUMN liquidity_source VARCHAR(32) DEFAULT NULL COMMENT ''流动性来源: BINANCE_REF 等'' AFTER execution_mode',
    'SELECT ''liquidity_source column already exists'' AS message'
  )
  FROM information_schema.columns
  WHERE table_schema = @schema_name
    AND table_name = 't_order'
    AND column_name = 'liquidity_source'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- t_order: reference_topic
SET @ddl = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_order ADD COLUMN reference_topic VARCHAR(128) DEFAULT NULL COMMENT ''参考行情来源 Topic'' AFTER liquidity_source',
    'SELECT ''reference_topic column already exists'' AS message'
  )
  FROM information_schema.columns
  WHERE table_schema = @schema_name
    AND table_name = 't_order'
    AND column_name = 'reference_topic'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- t_order: reference_offset
SET @ddl = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_order ADD COLUMN reference_offset BIGINT DEFAULT NULL COMMENT ''参考行情来源 offset'' AFTER reference_topic',
    'SELECT ''reference_offset column already exists'' AS message'
  )
  FROM information_schema.columns
  WHERE table_schema = @schema_name
    AND table_name = 't_order'
    AND column_name = 'reference_offset'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- t_order: reference_event_time
SET @ddl = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_order ADD COLUMN reference_event_time BIGINT DEFAULT NULL COMMENT ''参考行情事件时间（毫秒）'' AFTER reference_offset',
    'SELECT ''reference_event_time column already exists'' AS message'
  )
  FROM information_schema.columns
  WHERE table_schema = @schema_name
    AND table_name = 't_order'
    AND column_name = 'reference_event_time'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- t_order: reference_best_bid
SET @ddl = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_order ADD COLUMN reference_best_bid DECIMAL(30,8) DEFAULT NULL COMMENT ''参考最优买价'' AFTER reference_event_time',
    'SELECT ''reference_best_bid column already exists'' AS message'
  )
  FROM information_schema.columns
  WHERE table_schema = @schema_name
    AND table_name = 't_order'
    AND column_name = 'reference_best_bid'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- t_order: reference_best_ask
SET @ddl = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_order ADD COLUMN reference_best_ask DECIMAL(30,8) DEFAULT NULL COMMENT ''参考最优卖价'' AFTER reference_best_bid',
    'SELECT ''reference_best_ask column already exists'' AS message'
  )
  FROM information_schema.columns
  WHERE table_schema = @schema_name
    AND table_name = 't_order'
    AND column_name = 'reference_best_ask'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- t_order: reference_vwap_price
SET @ddl = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_order ADD COLUMN reference_vwap_price DECIMAL(30,8) DEFAULT NULL COMMENT ''参考成交均价'' AFTER reference_best_ask',
    'SELECT ''reference_vwap_price column already exists'' AS message'
  )
  FROM information_schema.columns
  WHERE table_schema = @schema_name
    AND table_name = 't_order'
    AND column_name = 'reference_vwap_price'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- t_order: slippage_bps
SET @ddl = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_order ADD COLUMN slippage_bps INT DEFAULT NULL COMMENT ''滑点（bps）'' AFTER reference_vwap_price',
    'SELECT ''slippage_bps column already exists'' AS message'
  )
  FROM information_schema.columns
  WHERE table_schema = @schema_name
    AND table_name = 't_order'
    AND column_name = 'slippage_bps'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- t_order: execution query index
SET @ddl = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_order ADD INDEX idx_exec_symbol_status_created (execution_mode, symbol, status, created_at)',
    'SELECT ''idx_exec_symbol_status_created already exists'' AS message'
  )
  FROM information_schema.statistics
  WHERE table_schema = @schema_name
    AND table_name = 't_order'
    AND index_name = 'idx_exec_symbol_status_created'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------
-- CFD working order table
-- -----------------------------
CREATE TABLE IF NOT EXISTS t_cfd_working_order (
  order_id BIGINT NOT NULL COMMENT 'OMS订单ID',
  user_id BIGINT NOT NULL COMMENT '用户ID',
  symbol VARCHAR(32) NOT NULL COMMENT '交易对',
  side TINYINT NOT NULL COMMENT '方向: 0=BUY 1=SELL',
  limit_price DECIMAL(30,8) DEFAULT NULL COMMENT '限价（可为空）',
  quantity DECIMAL(32,16) NOT NULL COMMENT '原始数量',
  remaining_quantity DECIMAL(32,16) NOT NULL COMMENT '剩余数量',
  status VARCHAR(16) NOT NULL COMMENT '状态: WORKING/FILLED/CANCELED/EXPIRED',
  trigger_source VARCHAR(64) DEFAULT NULL COMMENT '触发来源',
  trigger_event_time BIGINT DEFAULT NULL COMMENT '最近触发事件时间（毫秒）',
  created_at BIGINT NOT NULL COMMENT '创建时间（毫秒）',
  updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒）',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CFD工作订单表';

SET @ddl = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_cfd_working_order ADD INDEX idx_cfd_symbol_status_updated (symbol, status, updated_at)',
    'SELECT ''idx_cfd_symbol_status_updated already exists'' AS message'
  )
  FROM information_schema.statistics
  WHERE table_schema = @schema_name
    AND table_name = 't_cfd_working_order'
    AND index_name = 'idx_cfd_symbol_status_updated'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_cfd_working_order ADD INDEX idx_cfd_user_symbol_status (user_id, symbol, status)',
    'SELECT ''idx_cfd_user_symbol_status already exists'' AS message'
  )
  FROM information_schema.statistics
  WHERE table_schema = @schema_name
    AND table_name = 't_cfd_working_order'
    AND index_name = 'idx_cfd_user_symbol_status'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_cfd_working_order ADD INDEX idx_cfd_status_trigger_time (status, trigger_event_time)',
    'SELECT ''idx_cfd_status_trigger_time already exists'' AS message'
  )
  FROM information_schema.statistics
  WHERE table_schema = @schema_name
    AND table_name = 't_cfd_working_order'
    AND index_name = 'idx_cfd_status_trigger_time'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
