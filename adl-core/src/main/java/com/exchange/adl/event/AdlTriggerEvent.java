package com.exchange.adl.event;

import lombok.Data;

import java.math.BigDecimal;

/**
 * ADL Trigger Event - ADL触发事件
 *
 * 由ADL Service发布，供其他服务消费（通知、监控等）
 */
@Data
public class AdlTriggerEvent {

    /**
     * ADL触发ID
     */
    private String adlTriggerId;

    /**
     * 穿仓记录ID
     */
    private String bankruptcyRecordId;

    /**
     * 强平记录ID
     */
    private String liquidationId;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 需要ADL的方向（对手方）
     */
    private String oppositeSide;

    /**
     * 需要ADL的金额
     */
    private BigDecimal requiredAmount;

    /**
     * 触发原因
     */
    private String reason;

    /**
     * 保险基金余额
     */
    private BigDecimal insuranceFundBalance;

    /**
     * 触发时间
     */
    private Long triggeredAt;

    /**
     * 事件时间戳
     */
    private Long timestamp;
}
