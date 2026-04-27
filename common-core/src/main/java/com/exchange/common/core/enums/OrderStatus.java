package com.exchange.common.core.enums;

/**
 * 订单状态
 *
 * 🔥 统一状态码（与 OmsOrder.status Integer 值对齐）：
 * 0=NEW 1=PENDING_RISK 2=FROZEN 3=PARTIALLY_FILLED 4=FILLED 5=CANCELED 6=REJECTED 7=PENDING_CANCEL
 * 8=EXPIRED 9=LIQUIDATION_PENDING 10=ADL_PENDING 11=SYSTEM_CANCELED
 */
public enum OrderStatus {

    /**
     * 新建
     */
    NEW(0, "新建"),

    /**
     * 风控中（对应旧 RISK_PASSED）
     */
    PENDING_RISK(1, "风控中"),

    /**
     * 资金冻结（对应旧 SENT_TO_MATCH）
     */
    FROZEN(2, "资金冻结"),

    /**
     * 部分成交
     */
    PARTIALLY_FILLED(3, "部分成交"),

    /**
     * 完全成交
     */
    FILLED(4, "完全成交"),

    /**
     * 已撤销
     */
    CANCELED(5, "已撤销"),

    /**
     * 已拒绝（合并 RISK_REJECTED + MATCH_REJECTED）
     */
    REJECTED(6, "已拒绝"),

    /**
     * 撤单中（新增中间态）
     */
    PENDING_CANCEL(7, "撤单中"),

    /**
     * 已过期
     */
    EXPIRED(8, "已过期"),

    /**
     * 强平待处理
     */
    LIQUIDATION_PENDING(9, "强平待处理"),

    /**
     * ADL待处理
     */
    ADL_PENDING(10, "ADL待处理"),

    /**
     * 系统取消
     */
    SYSTEM_CANCELED(11, "系统取消");

    private final int code;
    private final String desc;

    OrderStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static OrderStatus of(int code) {
        for (OrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown order status code: " + code);
    }

    /**
     * 是否为最终态（不可再改变）
     */
    public boolean isFinal() {
        return this == FILLED || this == CANCELED ||
               this == REJECTED || this == EXPIRED ||
               this == SYSTEM_CANCELED;
    }
}

