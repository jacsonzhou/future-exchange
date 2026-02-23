package com.exchange.ledger.enums;

/**
 * 账户类型（交易所级）
 * 
 * 设计原则：
 * 1. 用户账户（1-10）
 * 2. 系统账户（11-20）
 */
public enum AccountType {
    
    // ==================== 用户账户 ====================
    USER_AVAILABLE(1, "用户可用余额", "USER"),
    USER_FROZEN(2, "用户冻结余额（下单占用）", "USER"),
    USER_POSITION_MARGIN(3, "用户持仓占用保证金", "USER"),
    
    // ==================== 系统账户 ====================
    EXCHANGE_FEE(11, "交易所手续费收入账户", "SYSTEM"),
    INSURANCE_FUND(12, "风险准备金（保险基金）", "SYSTEM"),
    SYSTEM_PNL(13, "系统盈亏账户", "SYSTEM"),
    FUNDING_POOL(14, "Funding资金池", "SYSTEM"),
    LIQUIDATION_CLEAR(15, "强平清算中转账户", "SYSTEM"),
    SYSTEM_INITIAL_FUNDING(16, "系统初始资金账户", "SYSTEM");
    
    private final int code;
    private final String description;
    private final String category;
    
    AccountType(int code, String description, String category) {
        this.code = code;
        this.description = description;
        this.category = category;
    }
    
    public int getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
    
    public String getCategory() {
        return category;
    }
    
    public boolean isUserAccount() {
        return "USER".equals(category);
    }
    
    public boolean isSystemAccount() {
        return "SYSTEM".equals(category);
    }
    
    /**
     * 根据code获取账户类型
     */
    public static AccountType fromCode(int code) {
        for (AccountType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown account type code: " + code);
    }
}

