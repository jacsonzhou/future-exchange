package com.exchange.margin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

/**
 * 仓位保证金详情实体（生产级）
 *
 * 用于记录每个仓位的保证金信息、强平价格、风险指标
 */
@Data
@TableName("t_position_margin_detail")
public class PositionMarginDetail {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 仓位ID
     */
    private Long positionId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 持仓方向：1=多头, 2=空头
     */
    private Integer side;

    // ==================== 保证金模式 ====================

    /**
     * 保证金模式：CROSS/ISOLATED
     */
    private String marginMode;

    /**
     * 杠杆倍数
     */
    private Integer leverage;

    // ==================== 逐仓专用 ====================

    /**
     * 逐仓保证金（初始保证金 + 追加保证金）
     */
    private Long isolatedMargin;

    /**
     * 逐仓可用保证金（可取出）
     */
    private Long isolatedAvailable;

    /**
     * 追加的保证金总额
     */
    private Long addedMargin;

    /**
     * 减少的保证金总额
     */
    private Long reducedMargin;

    // ==================== 保证金计算 ====================

    /**
     * 仓位保证金（全仓=名义价值/杠杆，逐仓=isolatedMargin）
     */
    private Long positionMargin;

    /**
     * 维持保证金（强平阈值）
     */
    @TableField("maintenance_margin")
    private Long maintMargin;

    /**
     * 维持保证金率（万分比，例如500=5%）
     */
    @TableField("maintenance_margin_rate")
    private Long maintMarginRate;

    // ==================== 仓位信息 ====================

    /**
     * 仓位价值（持仓数量 × 标记价格）
     */
    private Long positionValue;

    /**
     * 持仓数量
     */
    @TableField("quantity")
    private Long positionQty;

    /**
     * 开仓均价
     */
    private Long entryPrice;

    /**
     * 标记价格（最新）
     */
    private Long markPrice;

    // ==================== 风险指标 ====================

    /**
     * 保证金率（万分比）
     * 逐仓：isolatedMargin / positionValue * 10000
     * 全仓：见账户快照
     */
    private Long marginRatio;

    /**
     * 预估强平价格
     */
    private Long liquidationPrice;

    /**
     * 破产价格（保证金归零价格）
     */
    private Long bankruptcyPrice;

    // ==================== 盈亏 ====================

    /**
     * 未实现盈亏
     * 多头：(markPrice - entryPrice) × qty
     * 空头：(entryPrice - markPrice) × qty
     */
    private Long unrealizedPnl;

    /**
     * 已实现盈亏（本仓位累计）
     */
    private Long realizedPnl;

    // ==================== 全仓专用 ====================

    /**
     * 全仓未实现盈亏（冗余字段，便于查询）
     */
    private Long crossUnrealizedPnl;

    // ==================== 时间戳 ====================

    /**
     * 创建时间
     */
    private Long createdAt;

    /**
     * 更新时间
     */
    private Long updatedAt;

    /**
     * 版本号（乐观锁）
     */
    @Version
    private Integer version;

    // ==================== 辅助方法 ====================

    /**
     * 是否为逐仓模式
     */
    public boolean isIsolated() {
        return "ISOLATED".equalsIgnoreCase(marginMode);
    }

    /**
     * 是否为全仓模式
     */
    public boolean isCross() {
        return "CROSS".equalsIgnoreCase(marginMode);
    }

    /**
     * 是否为多头
     */
    public boolean isLong() {
        return side != null && side == 1;
    }

    /**
     * 是否为空头
     */
    public boolean isShort() {
        return side != null && side == 2;
    }

    /**
     * 向后兼容旧字段名（positionSide）。
     */
    public Integer getPositionSide() {
        return side;
    }

    public void setPositionSide(Integer positionSide) {
        this.side = positionSide;
    }

    /**
     * 向后兼容旧字段名（maintenanceMarginRate）。
     */
    public Long getMaintenanceMarginRate() {
        return maintMarginRate;
    }

    public void setMaintenanceMarginRate(Long maintenanceMarginRate) {
        this.maintMarginRate = maintenanceMarginRate;
    }

    /**
     * 是否需要强平（逐仓）
     */
    public boolean needsLiquidation() {
        if (!isIsolated() || marginRatio == null || maintMarginRate == null) {
            return false;
        }
        return marginRatio <= maintMarginRate;
    }

    /**
     * 计算距离强平的距离（价格）
     * @return 距离强平价的差距（正数=安全，负数=已触发或超过强平价）
     */
    public Long getLiquidationGap() {
        if (markPrice == null || liquidationPrice == null) {
            return null;
        }
        if (isLong()) {
            // 多头：markPrice > liquidationPrice 为安全
            return markPrice - liquidationPrice;
        } else {
            // 空头：markPrice < liquidationPrice 为安全
            return liquidationPrice - markPrice;
        }
    }
}
