package com.exchange.oms.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 提交订单响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubmitOrderResponse implements Serializable {
    
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
     * 客户端订单ID
     */
    private String clientOrderId;
    
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
    
    public static SubmitOrderResponse success(String orderId, String status, String clientOrderId) {
        SubmitOrderResponse response = new SubmitOrderResponse();
        response.setOrderId(orderId);
        response.setStatus(status);
        response.setClientOrderId(clientOrderId);
        response.setSuccess(true);
        return response;
    }
    
    public static SubmitOrderResponse fail(String errorCode, String errorMessage) {
        SubmitOrderResponse response = new SubmitOrderResponse();
        response.setSuccess(false);
        response.setErrorCode(errorCode);
        response.setErrorMessage(errorMessage);
        return response;
    }
}



