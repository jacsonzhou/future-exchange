package com.exchange.risk.enums;

/**
 * 风控结果枚举
 */
public enum RiskResult {
    
    /**
     * 通过
     */
    PASS(0, "通过"),
    
    /**
     * 拒绝
     */
    REJECT(1, "拒绝");
    
    private final int code;
    private final String desc;
    
    RiskResult(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
    
    public int getCode() {
        return code;
    }
    
    public String getDesc() {
        return desc;
    }
}

