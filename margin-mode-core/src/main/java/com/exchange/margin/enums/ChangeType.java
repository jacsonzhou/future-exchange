package com.exchange.margin.enums;

import lombok.Getter;

/**
 * 保证金变动类型枚举
 *
 * 用于记录所有保证金变动流水
 */
@Getter
public enum ChangeType {

    /**
     * 开仓 - 逐仓保证金增加
     */
    OPEN("OPEN", "开仓"),

    /**
     * 平仓 - 逐仓保证金释放
     */
    CLOSE("CLOSE", "平仓"),

    /**
     * 追加保证金
     */
    ADD("ADD", "追加保证金"),

    /**
     * 减少保证金
     */
    REMOVE("REMOVE", "减少保证金"),

    /**
     * 模式切换
     */
    MODE_CHANGE("MODE_CHANGE", "模式切换"),

    /**
     * 强平扣除
     */
    LIQUIDATION("LIQUIDATION", "强平扣除"),

    /**
     * 资金费率支付/收取
     */
    FUNDING_FEE("FUNDING_FEE", "资金费率"),

    /**
     * ADL自动减仓
     */
    ADL("ADL", "自动减仓");

    private final String code;
    private final String desc;

    ChangeType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据code获取枚举
     */
    public static ChangeType fromCode(String code) {
        for (ChangeType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown change type: " + code);
    }
}
