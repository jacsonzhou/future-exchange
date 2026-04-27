package com.exchange.common.proto.request;

import lombok.Data;

import java.io.Serializable;

/**
 * 撤销订单请求
 */
@Data
public class CancelOrderRequest implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 订单ID
     */
    private Long orderId;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 请求时间戳
     */
    private Long timestamp;
}







