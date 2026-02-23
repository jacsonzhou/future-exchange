package com.exchange.ledger.enums;

/**
 * 业务类型（交易所级）
 */
public enum BusinessType {
    
    // ==================== 交易相关 ====================
    TRADE_SETTLE("TRADE", "成交结算"),
    TRADE_FEE("FEE", "交易手续费"),
    
    // ==================== Funding相关 ====================
    FUNDING_PAY("FUNDING_PAY", "支付Funding Fee"),
    FUNDING_RECEIVE("FUNDING_RECEIVE", "收取Funding Fee"),
    
    // ==================== 强平相关 ====================
    LIQUIDATION_LOSS("LIQUIDATION_LOSS", "强平亏损"),
    LIQUIDATION_FEE("LIQUIDATION_FEE", "强平手续费"),
    BANKRUPTCY("BANKRUPTCY", "穿仓处理"),
    
    // ==================== 保证金相关 ====================
    MARGIN_FREEZE("MARGIN_FREEZE", "冻结保证金"),
    MARGIN_UNFREEZE("MARGIN_UNFREEZE", "解冻保证金"),
    
    // ==================== 出入金相关 ====================
    DEPOSIT("DEPOSIT", "充值"),
    WITHDRAW("WITHDRAW", "提现"),
    INITIAL_FUNDING("INITIAL_FUNDING", "初始资金"),

    // ==================== 系统调整 ====================
    SYSTEM_ADJUST("ADJUST", "系统调账"),
    INSURANCE_INJECT("INSURANCE_INJECT", "注入保险基金"),
    INSURANCE_PAYOUT("INSURANCE_PAYOUT", "保险基金赔付");
    
    private final String code;
    private final String description;
    
    BusinessType(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * 根据code获取业务类型
     */
    public static BusinessType fromCode(String code) {
        for (BusinessType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown business type code: " + code);
    }
}



