package com.exchange.oms.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 查询订单请求
 */
@Data
public class QueryOrderRequest implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 订单ID
     */
    private String orderId;
}






