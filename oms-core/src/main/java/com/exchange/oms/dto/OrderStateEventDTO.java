package com.exchange.oms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 订单状态事件 DTO
 * 
 * 🔥 用于在 OMS 内部传递订单状态变更
 * 
 * 来源: Match Engine → Kafka (order-state-{symbol}) → OMS Consumer
 * 去向: OMS Consumer → OrderStatePushPublisher → Kafka (private-order-state)
 * 
 * @author Exchange Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderStateEventDTO {
    
    /**
     * 事件ID
     */
    private String eventId;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 订单ID
     */
    private Long orderId;
    
    /**
     * 订单状态
     * @see com.exchange.common.proto.event.PrivatePushEvent.OrderStatus
     */
    private String status;
    
    /**
     * 本次成交数量增量
     */
    private BigDecimal filledQuantityDelta;
    
    /**
     * 累计已成交数量
     */
    private BigDecimal cumulativeFilledQty;
    
    /**
     * 累计已成交金额
     */
    private BigDecimal cumulativeFilledAmount;
    
    /**
     * 本次成交价格
     */
    private BigDecimal lastFilledPrice;
    
    /**
     * 手续费
     */
    private BigDecimal fee;
    
    /**
     * 手续费资产
     */
    private String feeAsset;
    
    /**
     * 成交时间
     */
    private Long tradeTime;
    
    /**
     * 成交ID
     */
    private Long tradeId;
    
    /**
     * 撮合序列号
     */
    private Long matchSequence;
    
    /**
     * 事件时间
     */
    private Long eventTime;
    
    /**
     * 错误码 (REJECTED时)
     */
    private Integer errorCode;
    
    /**
     * 错误信息 (REJECTED时)
     */
    private String errorMsg;
}
