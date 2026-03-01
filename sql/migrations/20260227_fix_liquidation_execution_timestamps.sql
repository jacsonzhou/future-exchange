-- Liquidation t_liquidation_execution timestamp compatibility migration
-- Purpose:
-- 1) Ensure completed_at exists for runtime mapper fields
-- 2) Ensure validated_at exists for validation stage timestamps

USE exchange_liquidation;

SET @ddl_completed = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_liquidation_execution ADD COLUMN completed_at BIGINT NULL COMMENT ''完成时间'' AFTER filled_at',
    'SELECT ''completed_at column already exists'' AS message'
  )
  FROM information_schema.columns
  WHERE table_schema = 'exchange_liquidation'
    AND table_name = 't_liquidation_execution'
    AND column_name = 'completed_at'
);

PREPARE stmt_completed FROM @ddl_completed;
EXECUTE stmt_completed;
DEALLOCATE PREPARE stmt_completed;

SET @ddl_validated = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_liquidation_execution ADD COLUMN validated_at BIGINT NULL COMMENT ''验证完成时间'' AFTER triggered_at',
    'SELECT ''validated_at column already exists'' AS message'
  )
  FROM information_schema.columns
  WHERE table_schema = 'exchange_liquidation'
    AND table_name = 't_liquidation_execution'
    AND column_name = 'validated_at'
);

PREPARE stmt_validated FROM @ddl_validated;
EXECUTE stmt_validated;
DEALLOCATE PREPARE stmt_validated;
