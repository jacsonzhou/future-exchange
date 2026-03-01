package com.exchange.adl.event;

import lombok.Data;

/**
 * Liquidation Completed Event - 强平完成事件
 *
 * 由Liquidation Service发布，ADL Service消费
 */
@Data
public class LiquidationCompletedEvent {

    /**
     * 事件类型
     */
    private String eventType;

    /**
     * 强平记录ID（例如 LIQ_1772300000000_12345）
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
     * 被强平仓位ID
     */
    private Long positionId;

    /**
     * 仓位方向（LONG/SHORT）
     */
    private String side;

    /**
     * 保证金模式
     */
    private String marginMode;

    /**
     * 是否穿仓
     */
    private Boolean isBankrupt;

    /**
     * 破产价格（8位精度long）
     */
    private Long bankruptPrice;

    /**
     * 穿仓数量（8位精度long）
     */
    private Long bankruptQty;

    /**
     * 穿仓损失（8位精度long）
     */
    private Long bankruptLoss;

    /**
     * 保险基金赔付金额
     */
    private Long insuranceCover;

    /**
     * 剩余亏损（可能触发ADL）
     */
    private Long remainingLoss;

    /**
     * 是否需要触发ADL
     */
    private Boolean adlRequired;

    /**
     * 强平执行均价
     */
    private Long executedPrice;

    /**
     * 强平执行数量
     */
    private Long executedQty;

    /**
     * 强平已实现盈亏
     */
    private Long realizedPnl;

    /**
     * 事件时间戳
     */
    private Long timestamp;

    /**
     * 序列号
     */
    private Long sequence;
}
