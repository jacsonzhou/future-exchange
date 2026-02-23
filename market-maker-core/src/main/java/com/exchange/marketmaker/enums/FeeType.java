package com.exchange.marketmaker.enums;

/**
 * 做市商费率类型枚举
 */
public enum FeeType {

    /**
     * Maker返佣
     */
    MAKER_REBATE("MAKER_REBATE", "Maker返佣"),

    /**
     * Maker手续费
     */
    MAKER_FEE("MAKER_FEE", "Maker手续费"),

    /**
     * Taker手续费
     */
    TAKER_FEE("TAKER_FEE", "Taker手续费");

    private final String code;
    private final String desc;

    FeeType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static FeeType fromCode(String code) {
        for (FeeType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown fee type: " + code);
    }
}
