package com.exchange.marketmaker.enums;

/**
 * 做市商状态枚举
 */
public enum MmStatus {

    /**
     * 正常
     */
    ACTIVE("ACTIVE", "正常"),

    /**
     * 暂停
     */
    SUSPENDED("SUSPENDED", "暂停"),

    /**
     * 终止
     */
    TERMINATED("TERMINATED", "终止"),

    /**
     * 冷静期
     */
    IN_COOLING_PERIOD("IN_COOLING_PERIOD", "冷静期"),

    /**
     * 已退出
     */
    WITHDRAWN("WITHDRAWN", "已退出");

    private final String code;
    private final String desc;

    MmStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static MmStatus fromCode(String code) {
        for (MmStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown MM status: " + code);
    }
}
