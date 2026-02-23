package com.exchange.oms.advice;

import com.exchange.oms.dto.CancelOrderResponse;
import com.exchange.oms.dto.SubmitOrderResponse;
import com.exchange.oms.enums.OmsErrorCode;
import com.exchange.oms.exception.OmsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(OmsException.class)
    public Object handleOmsException(OmsException e) {
        log.error("[OMS] Business exception: {}", e.getErrorMessage(), e);
        
        // 根据异常类型返回不同响应
        return SubmitOrderResponse.fail(e.getErrorCode(), e.getErrorMessage());
    }
    
    @ExceptionHandler(Exception.class)
    public Object handleException(Exception e) {
        log.error("[OMS] System exception", e);
        
        return SubmitOrderResponse.fail(
            OmsErrorCode.OMS_9001.getCode(),
            OmsErrorCode.OMS_9001.getMessage()
        );
    }
}

