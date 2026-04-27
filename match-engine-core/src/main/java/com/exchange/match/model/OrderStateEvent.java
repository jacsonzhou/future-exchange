package com.exchange.match.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 订单状态事件（回传OMS）
 * 
 * 用于：
 * - 更新订单状态
 * - 更新filledQuantity
 * - 驱动OMS状态机
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderStateEvent {
    
    /**
     * 事件ID
     */
    private String eventId;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 订单ID
     */
    private Long orderId;
    
    /**
     * 本次成交数量增量
     */
    private BigDecimal filledQuantityDelta;

    /**
     * 本次成交均价（8位小数）
     */
    private BigDecimal avgPrice;

    /**
     * 订单状态 PARTIAL_FILLED / FILLED / CANCELED
     */
    private String status;
    
    /**
     * 撮合序列号
     */
    private Long matchSequence;
    
    /**
     * 事件时间
     */
    private Long eventTime;
}

