package com.exchange.liquidation.dto;

import lombok.Data;

/**
 * 创建订单请求
 */
@Data
public class CreateOrderRequest {
    
    private Long userId;
    private String symbol;
    private String side;
    private String orderType;
    private Long quantity;
    private Long price;
    private Boolean reduceOnly;
    private String orderSource;
    private Long positionId;
    private String liquidationId;
    private String executionMode;
}
