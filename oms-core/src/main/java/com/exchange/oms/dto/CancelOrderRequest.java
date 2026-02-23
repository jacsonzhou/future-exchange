package com.exchange.oms.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 撤单请求
 */
@Data
public class CancelOrderRequest implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 追踪ID
     */
    private String traceId;
    
    /**
     * 请求ID
     */
    private String requestId;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 订单ID
     */
    private String orderId;
    
    /**
     * 客户端订单ID
     */
    private String clientOrderId;
}

