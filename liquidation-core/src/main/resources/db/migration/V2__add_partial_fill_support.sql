-- 迁移脚本：添加部分成交支持
-- 版本: V2
-- 日期: 2026-02-18
-- 描述: 在 t_liquidation_execution 表中新增支持部分成交处理的字段

-- 新增部分成交相关字段
ALTER TABLE `t_liquidation_execution`
    -- 剩余仓位数量
    ADD COLUMN `remaining_qty` BIGINT DEFAULT NULL COMMENT '剩余仓位数量（8位小数）' AFTER `executed_qty`,

    -- 剩余仓位订单ID
    ADD COLUMN `remaining_order_id` BIGINT DEFAULT NULL COMMENT '剩余仓位订单ID（部分成交后创建的新订单）' AFTER `remaining_qty`,

    -- 部分成交累计盈亏
    ADD COLUMN `partial_pnl` BIGINT DEFAULT 0 COMMENT '部分成交累计盈亏（8位小数，负数为亏损）' AFTER `realized_pnl`,

    -- 部分成交累计穿仓损失
    ADD COLUMN `partial_bankrupt_loss` BIGINT DEFAULT 0 COMMENT '部分成交累计穿仓损失（8位小数，正数表示损失金额）' AFTER `bankrupt_loss`,

    -- 父强平ID
    ADD COLUMN `parent_liquidation_id` VARCHAR(64) DEFAULT NULL COMMENT '父强平ID（如果是剩余仓位订单，关联原始强平ID）' AFTER `liquidation_id`;

-- 新增索引：父强平ID索引（用于追溯强平链路）
ALTER TABLE `t_liquidation_execution`
    ADD INDEX `idx_parent_liquidation_id` (`parent_liquidation_id`);

-- 新增索引：剩余订单ID索引（用于监控剩余订单）
ALTER TABLE `t_liquidation_execution`
    ADD INDEX `idx_remaining_order_id` (`remaining_order_id`);

-- 新增完成时间字段（原表缺失）
ALTER TABLE `t_liquidation_execution`
    ADD COLUMN `completed_at` BIGINT DEFAULT NULL COMMENT '完成时间' AFTER `filled_at`;

-- 新增验证完成时间字段（原表缺失）
ALTER TABLE `t_liquidation_execution`
    ADD COLUMN `validated_at` BIGINT DEFAULT NULL COMMENT '验证完成时间' AFTER `triggered_at`;

-- 注释：
-- 1. remaining_qty: 部分成交后，记录剩余未成交的数量
-- 2. remaining_order_id: 部分成交后创建的新订单ID，用于继续平仓剩余仓位
-- 3. partial_pnl: 每次部分成交时累加的盈亏，用于增量计算
-- 4. partial_bankrupt_loss: 每次部分成交时累加的穿仓损失
-- 5. parent_liquidation_id: 如果是剩余仓位订单，关联原始强平ID，用于追溯完整链路
--
-- 示例场景：
-- 强平订单 10 BTC，第一次成交 6 BTC，第二次成交 4 BTC
-- 第一次成交：
--   - executedQty = 6
--   - remaining_qty = 4
--   - partial_pnl = (第一次的盈亏)
--   - partial_bankrupt_loss = (第一次的穿仓损失)
--   - remaining_order_id = (新订单ID)
--   - 创建新强平记录，parent_liquidation_id = (原始强平ID)
--
-- 第二次成交：
--   - executedQty = 4
--   - remaining_qty = 0
--   - partial_pnl += (第二次的盈亏)
--   - partial_bankrupt_loss += (第二次的穿仓损失)
--   - status = FILLED
--
-- 最终计算：
--   - realized_pnl = partial_pnl + (最后一次的盈亏)
--   - bankrupt_loss = partial_bankrupt_loss + (最后一次的穿仓损失)
--   - insurance_cover = (累计赔付)
--   - remaining_loss = bankrupt_loss - insurance_cover
