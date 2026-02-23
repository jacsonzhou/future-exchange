package com.exchange.common.core.enums;

/**
 * 账本记账方向（双录记账法）
 */
public enum LedgerDirection {
    
    /**
     * 借方（资产增加/负债减少）
     */
    DEBIT(1, "借方"),
    
    /**
     * 贷方（资产减少/负债增加）
     */
    CREDIT(2, "贷方");
    
    private final int code;
    private final String desc;
    
    LedgerDirection(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
    
    public int getCode() {
        return code;
    }
    
    public String getDesc() {
        return desc;
    }
    
    public static LedgerDirection of(int code) {
        for (LedgerDirection direction : values()) {
            if (direction.code == code) {
                return direction;
            }
        }
        throw new IllegalArgumentException("Unknown ledger direction code: " + code);
    }
}

