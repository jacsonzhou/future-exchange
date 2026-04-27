package com.exchange.oms.exception;

import com.exchange.oms.enums.OmsErrorCode;

/**
 * OMS业务异常
 */
public class OmsException extends RuntimeException {
    
    private final String errorCode;
    private final String errorMessage;
    
    public OmsException(OmsErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode.getCode();
        this.errorMessage = errorCode.getMessage();
    }
    
    public OmsException(String errorCode, String errorMessage) {
        super(errorMessage);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }
    
    public String getErrorCode() {
        return errorCode;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
}






