package com.exchange.oms.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 撤单响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CancelOrderResponse implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 订单ID
     */
    private String orderId;
    
    /**
     * 订单状态
     */
    private String status;
    
    /**
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 错误码
     */
    private String errorCode;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    public static CancelOrderResponse success(String orderId, String status) {
        CancelOrderResponse response = new CancelOrderResponse();
        response.setOrderId(orderId);
        response.setStatus(status);
        response.setSuccess(true);
        return response;
    }
    
    public static CancelOrderResponse fail(String errorCode, String errorMessage) {
        CancelOrderResponse response = new CancelOrderResponse();
        response.setSuccess(false);
        response.setErrorCode(errorCode);
        response.setErrorMessage(errorMessage);
        return response;
    }
}






