-- OMS t_order leverage compatibility migration
-- Purpose:
-- 1) Ensure leverage is persisted on order records
-- 2) Avoid fallback-to-10 behavior on fill/cancel unfreeze

USE exchange_oms;

-- MySQL versions without "ADD COLUMN IF NOT EXISTS" compatibility:
-- run the check first, then ALTER only when missing.
SET @ddl = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE t_order ADD COLUMN leverage INT DEFAULT 10 COMMENT ''杠杆倍数'' AFTER time_in_force',
    'SELECT ''leverage column already exists'' AS message'
  )
  FROM information_schema.columns
  WHERE table_schema = 'exchange_oms'
    AND table_name = 't_order'
    AND column_name = 'leverage'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
