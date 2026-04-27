package com.exchange.risk.enums;

/**
 * 风控拒绝原因枚举
 */
public enum RiskRejectReason {
    
    /**
     * 无（通过时使用）
     */
    NONE(0, "无"),
    
    /**
     * 保证金不足
     */
    INSUFFICIENT_MARGIN(1, "保证金不足"),
    
    /**
     * 杠杆超限
     */
    LEVERAGE_EXCEEDED(2, "杠杆超限"),
    
    /**
     * 仓位超限
     */
    POSITION_LIMIT_EXCEEDED(3, "仓位超限"),
    
    /**
     * 只减仓违规
     */
    REDUCE_ONLY_VIOLATION(4, "只减仓违规"),
    
    /**
     * 账户冻结
     */
    ACCOUNT_FROZEN(5, "账户冻结"),
    
    /**
     * 账户强平中
     */
    ACCOUNT_LIQUIDATING(6, "账户强平中"),
    
    /**
     * 价格超出范围
     */
    PRICE_OUT_OF_RANGE(7, "价格超出范围"),
    
    /**
     * 风控黑名单
     */
    RISK_BLACKLISTED(8, "风控黑名单"),
    
    /**
     * 交易对配置不存在
     */
    SYMBOL_CONFIG_NOT_FOUND(9, "交易对配置不存在"),
    
    /**
     * 账户不存在
     */
    ACCOUNT_NOT_FOUND(10, "账户不存在"),
    
    /**
     * 订单数量超限
     */
    ORDER_QTY_EXCEEDED(11, "订单数量超限"),

    /**
     * 系统错误（内部异常、数据库故障、参数绑定失败等）
     */
    SYSTEM_ERROR(12, "系统错误");

    private final int code;
    private final String message;
    
    RiskRejectReason(int code, String message) {
        this.code = code;
        this.message = message;
    }
    
    public int getCode() {
        return code;
    }
    
    public String getMessage() {
        return message;
    }
}

