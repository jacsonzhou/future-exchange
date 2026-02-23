package com.exchange.risk.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 风控检查请求
 */
@Data
public class CheckOrderRiskRequest implements Serializable {
    
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
     * 交易对
     */
    private String symbol;
    
    /**
     * 买卖方向 BUY/SELL
     */
    private String side;
    
    /**
     * 价格
     */
    private String price;
    
    /**
     * 数量
     */
    private String quantity;
    
    /**
     * 杠杆倍数
     */
    private Integer leverage;
    
    /**
     * 是否只减仓
     */
    private Boolean reduceOnly;
    
    /**
     * 订单ID（OMS生成，用于幂等）
     */
    private String orderId;
}

