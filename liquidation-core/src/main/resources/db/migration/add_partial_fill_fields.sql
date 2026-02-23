-- 强平服务数据库迁移脚本
-- 添加部分成交支持字段
-- 执行时间：2024年2月

USE exchange_liquidation;

-- 添加部分成交相关字段
ALTER TABLE `t_liquidation_execution`
    ADD COLUMN `remaining_qty` BIGINT DEFAULT 0 COMMENT '剩余仓位数量(8位小数)' AFTER `adl_required`,
    ADD COLUMN `remaining_order_id` BIGINT COMMENT '剩余仓位订单ID' AFTER `remaining_qty`,
    ADD COLUMN `partial_pnl` BIGINT DEFAULT 0 COMMENT '部分成交累计盈亏(8位小数)' AFTER `remaining_order_id`,
    ADD COLUMN `partial_bankrupt_loss` BIGINT DEFAULT 0 COMMENT '部分成交累计穿仓损失(8位小数)' AFTER `partial_pnl`,
    ADD COLUMN `parent_liquidation_id` VARCHAR(64) COMMENT '父强平ID(剩余仓位订单关联)' AFTER `partial_bankrupt_loss`;

-- 添加索引优化查询
ALTER TABLE `t_liquidation_execution`
    ADD KEY `idx_parent_liquidation_id` (`parent_liquidation_id`) COMMENT '剩余仓位订单关联查询';

-- 验证
SELECT 
    COLUMN_NAME,
    COLUMN_TYPE,
    COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'exchange_liquidation'
  AND TABLE_NAME = 't_liquidation_execution'
  AND COLUMN_NAME IN ('remaining_qty', 'remaining_order_id', 'partial_pnl', 
                      'partial_bankrupt_loss', 'parent_liquidation_id')
ORDER BY ORDINAL_POSITION;

