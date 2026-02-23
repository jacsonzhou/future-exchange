package com.exchange.common.core.enums;

/**
 * 业务类型（用于账本分录）
 */
public enum BizType {
    
    /**
     * 充值
     */
    DEPOSIT(1, "充值"),
    
    /**
     * 提现
     */
    WITHDRAW(2, "提现"),
    
    /**
     * 下单冻结
     */
    ORDER_FREEZE(3, "下单冻结"),
    
    /**
     * 撤单解冻
     */
    ORDER_UNFREEZE(4, "撤单解冻"),
    
    /**
     * 成交结算
     */
    TRADE_SETTLE(5, "成交结算"),
    
    /**
     * 手续费
     */
    FEE(6, "手续费"),
    
    /**
     * 资金费率
     */
    FUNDING_RATE(7, "资金费率"),
    
    /**
     * 强平
     */
    LIQUIDATION(8, "强平"),
    
    /**
     * ADL自动减仓
     */
    ADL(9, "自动减仓"),
    
    /**
     * 保险基金
     */
    INSURANCE_FUND(10, "保险基金");
    
    private final int code;
    private final String desc;
    
    BizType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
    
    public int getCode() {
        return code;
    }
    
    public String getDesc() {
        return desc;
    }
    
    public static BizType of(int code) {
        for (BizType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown biz type code: " + code);
    }
}

