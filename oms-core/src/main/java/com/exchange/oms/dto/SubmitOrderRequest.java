package com.exchange.oms.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 提交订单请求
 */
@Data
public class SubmitOrderRequest implements Serializable {
    
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
     * 客户端订单ID（幂等key）
     */
    private String clientOrderId;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 买卖方向 BUY/SELL
     */
    private String side;
    
    /**
     * 订单类型 LIMIT/MARKET
     */
    private String type;
    
    /**
     * 价格（限价单必填）
     */
    private String price;
    
    /**
     * 数量
     */
    private String quantity;
    
    /**
     * 有效期类型
     */
    private String timeInForce;
    
    /**
     * 杠杆倍数
     */
    private Integer leverage;
    
    /**
     * 保证金模式 ISOLATED/CROSS
     */
    private String marginMode;
}



