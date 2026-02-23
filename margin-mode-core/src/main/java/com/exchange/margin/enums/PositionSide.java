package com.exchange.margin.enums;

import lombok.Getter;

/**
 * 持仓方向枚举
 */
@Getter
public enum PositionSide {

    /**
     * 多头（做多）
     */
    LONG(1, "LONG", "多头"),

    /**
     * 空头（做空）
     */
    SHORT(2, "SHORT", "空头");

    private final int code;
    private final String name;
    private final String desc;

    PositionSide(int code, String name, String desc) {
        this.code = code;
        this.name = name;
        this.desc = desc;
    }

    /**
     * 根据code获取枚举
     */
    public static PositionSide fromCode(int code) {
        for (PositionSide side : values()) {
            if (side.code == code) {
                return side;
            }
        }
        throw new IllegalArgumentException("Unknown position side: " + code);
    }

    /**
     * 是否为多头
     */
    public boolean isLong() {
        return this == LONG;
    }

    /**
     * 是否为空头
     */
    public boolean isShort() {
        return this == SHORT;
    }
}
