package com.exchange.common.proto.dto;

import com.exchange.common.core.enums.OrderStatus;
import com.exchange.common.core.enums.OrderType;
import com.exchange.common.core.enums.Side;
import lombok.Data;

import java.io.Serializable;

/**
 * 订单DTO
 */
@Data
public class OrderDTO implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 订单ID
     */
    private Long orderId;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 买卖方向
     */
    private Side side;
    
    /**
     * 订单类型
     */
    private OrderType orderType;
    
    /**
     * 价格（long格式）
     */
    private Long price;
    
    /**
     * 数量（long格式）
     */
    private Long quantity;
    
    /**
     * 已成交数量
     */
    private Long filledQuantity;
    
    /**
     * 订单状态
     */
    private OrderStatus status;
    
    /**
     * 杠杆倍数
     */
    private Integer leverage;
    
    /**
     * 客户端订单ID
     */
    private String clientOrderId;
    
    /**
     * 创建时间
     */
    private Long createTime;
    
    /**
     * 更新时间
     */
    private Long updateTime;
}




