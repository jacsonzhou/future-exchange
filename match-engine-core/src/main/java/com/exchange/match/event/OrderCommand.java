package com.exchange.match.event;

import com.exchange.match.model.Order;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 订单命令（输入事件）
 * 
 * 来自OMS的订单事件
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderCommand {
    
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
    @JsonAlias({"type"})
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
     * 事件时间
     */
    private Long eventTime;
    
    /**
     * 时间戳
     */
    private Long timestamp;
    
    /**
     * 客户端订单ID
     */
    private String clientOrderId;
    
    /**
     * 扩展字段
     */
    private String ext;
}


