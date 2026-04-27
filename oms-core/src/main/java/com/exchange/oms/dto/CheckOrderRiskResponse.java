package com.exchange.oms.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 风控检查响应（与 hard-risk-core 契约对齐）
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
}
