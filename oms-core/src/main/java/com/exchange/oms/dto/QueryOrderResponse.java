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
     * 执行模式：MATCH_ENGINE / CFD_DEALER
     */
    private String executionMode;

    /**
     * 流动性来源：如 BINANCE_REF
     */
    private String liquiditySource;

    /**
     * 参考行情来源 Topic
     */
    private String referenceTopic;

    /**
     * 参考行情来源 offset
     */
    private Long referenceOffset;

    /**
     * 参考行情事件时间（毫秒）
     */
    private Long referenceEventTime;

    /**
     * 参考最优买价
     */
    private String referenceBestBid;

    /**
     * 参考最优卖价
     */
    private String referenceBestAsk;

    /**
     * 参考成交均价
     */
    private String referenceVwapPrice;

    /**
     * 滑点（bps）
     */
    private Integer slippageBps;
    
    /**
     * 创建时间
     */
    private Long createTime;
}




