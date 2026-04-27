package com.exchange.tpsl.exception;

import lombok.Getter;

/**
 * 止盈止损服务异常
 */
@Getter
public class TpSlException extends RuntimeException {
    
    private final String errorCode;
    private final String errorMessage;
    
    public TpSlException(String errorCode, String errorMessage) {
        super(errorMessage);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }
    
    public TpSlException(String errorCode, String errorMessage, Throwable cause) {
        super(errorMessage, cause);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }
    
    public TpSlException(String errorMessage) {
        super(errorMessage);
        this.errorCode = "TPSL_ERROR";
        this.errorMessage = errorMessage;
    }
}
