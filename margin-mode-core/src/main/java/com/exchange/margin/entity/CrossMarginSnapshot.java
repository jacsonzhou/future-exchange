package com.exchange.margin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 全仓账户风险快照实体（生产级）
 * 
 * 🔥 核心设计原则：
 * 1. 每个用户一条全仓快照记录
 * 2. 实时计算账户整体风险状况
 * 3. 用于风控决策和强平判断
 * 4. 支持快速查询账户健康度
 * 
 * 对标：Binance / OKX / Bybit级别
 */
@Data
@TableName("t_cross_margin_snapshot")
public class CrossMarginSnapshot {
    
    /**
     * 主键ID（自增）
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    // ==================== 🔥 账户资金 ====================
    
    /**
     * 钱包余额（可用余额 + 仓位保证金）
     * 使用long存储，精度8位小数
     */
    private Long walletBalance;
    
    /**
     * 可用余额（可下单、可转出）
     */
    private Long availableBalance;
    
    /**
     * 冻结余额（挂单冻结）
     */
    private Long frozenBalance;
    
    /**
     * 已用保证金（所有全仓仓位的保证金总和）
     */
    private Long usedMargin;
    
    // ==================== 🔥 仓位价值 ====================
    
    /**
     * 全仓仓位总价值（名义价值总和）
     * 计算公式：Σ(各仓位持仓数量 * 标记价格)
     */
    private Long totalPositionValue;
    
    /**
     * 多头仓位价值
     */
    private Long longPositionValue;
    
    /**
     * 空头仓位价值
     */
    private Long shortPositionValue;
    
    /**
     * 持仓数量统计（全仓仓位数）
     */
    private Integer positionCount;
    
    // ==================== 🔥 盈亏统计 ====================
    
    /**
     * 未实现盈亏（所有全仓仓位的浮盈/浮亏总和）
     * 正数=盈利，负数=亏损
     */
    private Long totalUnrealizedPnl;
    
    /**
     * 已实现盈亏（当日已实现盈亏）
     */
    private Long todayRealizedPnl;
    
    // ==================== 🔥 保证金计算 ====================
    
    /**
     * 总维持保证金（所有全仓仓位的维持保证金总和）
     */
    private Long totalMaintenanceMargin;
    
    /**
     * 初始保证金要求（新开仓所需最小保证金）
     */
    private Long initialMarginRequirement;
    
    /**
     * 维持保证金率（万分比）
     * 计算公式：totalMaintenanceMargin / totalPositionValue * 10000
     */
    private Integer maintenanceMarginRate;
    
    // ==================== 🔥 核心风险指标 ====================
    
    /**
     * 保证金余额
     * 计算公式：walletBalance + totalUnrealizedPnl
     */
    private Long marginBalance;
    
    /**
     * 保证金率（万分比）
     * 计算公式：marginBalance / totalPositionValue * 10000
     * 例如：10000 = 100%，500 = 5%
     */
    private Long marginRatio;
    
    /**
     * 可用保证金（可开新仓的保证金）
     * 计算公式：marginBalance - totalMaintenanceMargin
     */
    private Long availableMargin;
    
    /**
     * 最大可开仓价值（基于当前可用保证金）
     */
    private Long maxOpenPositionValue;
    
    // ==================== 🔥 风险等级 ====================
    
    /**
     * 风险等级：1=SAFE, 2=WARNING, 3=DANGER, 4=LIQUIDATION
     * @see com.exchange.margin.enums.RiskLevel
     */
    private Integer riskLevel;
    
    /**
     * 风险等级名称
     */
    private String riskLevelName;
    
    /**
     * 是否可交易（风险等级为LIQUIDATION时不可交易）
     */
    private Boolean canTrade;
    
    /**
     * 是否可提现（风险等级为DANGER以上时限制提现）
     */
    private Boolean canWithdraw;
    
    // ==================== 🔥 强平相关 ====================
    
    /**
     * 预估总强平价格（加权平均）
     * 用于快速判断账户整体风险
     */
    private Long estimatedLiquidationPrice;
    
    /**
     * 距离强平的盈亏缺口
     * 正数=安全，负数=已触发或即将触发强平
     */
    private Long liquidationGap;
    
    /**
     * 强平执行状态：0=正常, 1=强平中, 2=强平完成
     */
    private Integer liquidationStatus;
    
    /**
     * 强平开始时间
     */
    private Long liquidationStartTime;
    
    // ==================== 时间戳 ====================
    
    /**
     * 快照创建时间（毫秒时间戳）
     */
    private Long createdAt;
    
    /**
     * 快照更新时间（毫秒时间戳）
     */
    private Long updatedAt;
    
    /**
     * 版本号（乐观锁）
     */
    private Integer version;
    
    // ==================== 辅助方法 ====================
    
    /**
     * 是否安全（保证金率 > 100%）
     */
    public boolean isSafe() {
        return marginRatio != null && marginRatio >= 10000L;
    }
    
    /**
     * 是否需要警告（保证金率 < 50%）
     */
    public boolean needsWarning() {
        return marginRatio != null && marginRatio < 5000L;
    }
    
    /**
     * 是否危险（保证金率 < 20%）
     */
    public boolean isDanger() {
        return marginRatio != null && marginRatio < 2000L;
    }
    
    /**
     * 是否需要强平（保证金率 <= 10%）
     */
    public boolean needsLiquidation() {
        return marginRatio != null && marginRatio <= 1000L;
    }
    
    /**
     * 是否有持仓
     */
    public boolean hasPosition() {
        return totalPositionValue != null && totalPositionValue > 0;
    }
    
    /**
     * 是否有浮亏
     */
    public boolean hasUnrealizedLoss() {
        return totalUnrealizedPnl != null && totalUnrealizedPnl < 0;
    }
    
    /**
     * 计算实际杠杆倍数
     * 实际杠杆 = 总仓位价值 / 保证金余额
     * @return 实际杠杆 * 100（保留2位小数）
     */
    public Long getActualLeverage() {
        if (marginBalance == null || marginBalance == 0) {
            return 0L;
        }
        return (totalPositionValue * 100L) / marginBalance;
    }
    
    /**
     * 计算当前最大可用杠杆
     */
    public Integer getMaxAvailableLeverage() {
        if (marginRatio == null || marginRatio == 0) {
            return 1;
        }
        // 根据保证金率反推可用杠杆
        long maxLeverage = marginRatio / 100; // 转换为百分比
        return (int) Math.min(maxLeverage, 125); // 最大125倍
    }
}
