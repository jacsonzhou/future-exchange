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
     * 事件时间
     */
    private Long eventTime;
}
