package com.exchange.risk.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 风控检查响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CheckOrderRiskResponse implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 风控结果 PASS/REJECT
     */
    private String result;
    
    /**
     * 拒绝原因码
     */
    private String rejectReason;
    
    /**
     * 拒绝信息
     */
    private String rejectMessage;
    
    /**
     * 所需保证金（用于审计/debug）
     */
    private String requiredMargin;
    
    /**
     * 可用保证金（用于审计/debug）
     */
    private String availableMargin;
    
    /**
     * 风控检查时间
     */
    private Long riskCheckTime;
    
    /**
     * 创建PASS响应
     */
    public static CheckOrderRiskResponse pass(String requiredMargin, String availableMargin) {
        CheckOrderRiskResponse response = new CheckOrderRiskResponse();
        response.setResult("PASS");
        response.setRejectReason("NONE");
        response.setRequiredMargin(requiredMargin);
        response.setAvailableMargin(availableMargin);
        response.setRiskCheckTime(System.currentTimeMillis());
        return response;
    }
    
    /**
     * 创建REJECT响应
     */
    public static CheckOrderRiskResponse reject(String rejectReason, String rejectMessage,
                                                 String requiredMargin, String availableMargin) {
        CheckOrderRiskResponse response = new CheckOrderRiskResponse();
        response.setResult("REJECT");
        response.setRejectReason(rejectReason);
        response.setRejectMessage(rejectMessage);
        response.setRequiredMargin(requiredMargin);
        response.setAvailableMargin(availableMargin);
        response.setRiskCheckTime(System.currentTimeMillis());
        return response;
    }
}

