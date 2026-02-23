package com.exchange.margin.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 风险预警事件
 *
 * 发布到 risk-warning-topic，供 Risk Monitor Service 和 Notification Service 消费
 */
@Data
public class RiskWarningEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 仓位ID（逐仓模式下有值）
     */
    private Long positionId;

    /**
     * 交易对（逐仓模式下有值）
     */
    private String symbol;

    /**
     * 保证金模式：CROSS, ISOLATED
     */
    private String marginMode;

    /**
     * 风险等级：
     * - SAFE: 安全（保证金率 >= 100%）
     * - WARNING: 警告（50% <= 保证金率 < 100%）
     * - DANGER: 危险（20% <= 保证金率 < 50%）
     * - CRITICAL: 紧急（10% <= 保证金率 < 20%）
     * - LIQUIDATION: 强平（保证金率 < 10%）
     */
    private String riskLevel;

    /**
     * 当前保证金率（万分比：10000 = 100%）
     */
    private Long marginRatio;

    /**
     * 当前保证金（精度8位）
     */
    private Long currentMargin;

    /**
     * 维持保证金（精度8位）
     */
    private Long maintenanceMargin;

    /**
     * 未实现盈亏（精度8位）
     */
    private Long unrealizedPnl;

    /**
     * 当前标记价格（精度8位）
     */
    private Long markPrice;

    /**
     * 强平价格（精度8位）
     */
    private Long liquidationPrice;

    /**
     * 距离强平的价格差（精度8位）
     */
    private Long liquidationGap;

    /**
     * 距离强平的百分比（万分比）
     */
    private Long liquidationGapPercent;

    /**
     * 杠杆倍数
     */
    private Integer leverage;

    /**
     * 是否需要追加保证金
     */
    private Boolean needAddMargin;

    /**
     * 建议追加保证金金额（精度8位）
     */
    private Long suggestedAddAmount;

    /**
     * 事件时间戳
     */
    private Long timestamp;

    /**
     * 预警消息
     */
    private String message;

    /**
     * 是否发送通知
     */
    private Boolean sendNotification;
}
