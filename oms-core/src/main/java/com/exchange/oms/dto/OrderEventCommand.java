package com.exchange.oms.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 订单事件命令（OMS → Match Engine）
 */
@Data
public class OrderEventCommand implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 事件类型
     */
    private String eventType; // ORDER_SUBMIT / ORDER_CANCEL / ORDER_FORCE_CANCEL
    
    /**
     * 序列号（用于Replay）
     */
    private Long sequence;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 订单ID
     */
    private Long orderId;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 买卖方向
     */
    private String side; // BUY / SELL
    
    /**
     * 订单类型
     */
    private String orderType; // LIMIT / MARKET
    
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
     * 事件时间
     */
    private Long eventTime;
}
