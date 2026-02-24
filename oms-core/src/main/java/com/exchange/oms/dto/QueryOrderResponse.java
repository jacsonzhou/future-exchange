package com.exchange.oms.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 查询订单响应
 */
@Data
public class QueryOrderResponse implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 订单ID
     */
    private String orderId;
    
    /**
     * 客户端订单ID
     */
    private String clientOrderId;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 买卖方向
     */
    private String side;
    
    /**
     * 订单类型
     */
    private String type;
    
    /**
     * 价格
     */
    private String price;
    
    /**
     * 数量
     */
    private String quantity;
    
    /**
     * 已成交数量
     */
    private String filledQuantity;
    
    /**
     * 订单状态
     */
    private String status;

    /**
     * 最近一次状态流转原因码（拒单/撤单等）
     */
    private String reasonCode;

    /**
     * 最近一次状态流转原因描述（拒单/撤单等）
     */
    private String reasonMsg;
    
    /**
     * 创建时间
     */
    private Long createTime;
}


