package com.exchange.marketmaker.dto.request;

import lombok.Data;

/**
 * 修改订单请求
 */
@Data
public class ModifyOrderRequest {

    private Long orderId;
    private String newPrice;
    private String newQuantity;
}
