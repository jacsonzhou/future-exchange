package com.exchange.common.proto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 创建订单响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderResponse implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 订单ID
     */
    private Long orderId;
    
    /**
     * 客户端订单ID
     */
    private String clientOrderId;
    
    /**
     * 创建时间戳
     */
    private Long timestamp;
    
    /**
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    public static CreateOrderResponse success(Long orderId, String clientOrderId) {
        CreateOrderResponse response = new CreateOrderResponse();
        response.setOrderId(orderId);
        response.setClientOrderId(clientOrderId);
        response.setTimestamp(System.currentTimeMillis());
        response.setSuccess(true);
        return response;
    }
    
    public static CreateOrderResponse fail(String errorMessage) {
        CreateOrderResponse response = new CreateOrderResponse();
        response.setSuccess(false);
        response.setErrorMessage(errorMessage);
        response.setTimestamp(System.currentTimeMillis());
        return response;
    }
}




