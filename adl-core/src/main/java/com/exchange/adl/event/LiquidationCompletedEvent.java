package com.exchange.adl.event;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Liquidation Completed Event - 强平完成事件
 *
 * 由Liquidation Service发布，ADL Service消费
 */
@Data
public class LiquidationCompletedEvent {

    /**
     * 强平记录ID
     */
    private String liquidationId;

    /**
     * 被强平用户ID
     */
    private Long userId;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 仓位方向
     */
    private String side;

    /**
     * 破产价格
     */
    private BigDecimal bankruptPrice;

    /**
     * 标记价格（强平时）
     */
    private BigDecimal markPrice;

    /**
     * 原始仓位数量
     */
    private BigDecimal originalQty;

    /**
     * 已平仓数量
     */
    private BigDecimal filledQty;

    /**
     * 剩余未平仓数量
     */
    private BigDecimal remainingQty;

    /**
     * 穿仓损失金额（如果有）
     */
    private BigDecimal bankruptLoss;

    /**
     * 是否穿仓
     */
    private Boolean isBankrupt;

    /**
     * 强平完成时间
     */
    private Long completedAt;

    /**
     * 事件时间戳
     */
    private Long timestamp;

    /**
     * 事件ID（幂等性）
     */
    private String eventId;
}
