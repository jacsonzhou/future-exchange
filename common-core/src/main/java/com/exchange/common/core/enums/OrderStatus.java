package com.exchange.common.core.enums;

/**
 * 订单状态
 */
public enum OrderStatus {
    
    /**
     * 新建
     */
    NEW(1, "新建"),
    
    /**
     * 风控通过
     */
    RISK_PASSED(2, "风控通过"),
    
    /**
     * 已发送到撮合引擎
     */
    SENT_TO_MATCH(3, "已发送撮合"),
    
    /**
     * 部分成交
     */
    PARTIAL_FILLED(4, "部分成交"),
    
    /**
     * 完全成交
     */
    FILLED(5, "完全成交"),
    
    /**
     * 已撤销
     */
    CANCELED(6, "已撤销"),
    
    /**
     * 风控拒绝
     */
    RISK_REJECTED(7, "风控拒绝"),
    
    /**
     * 撮合拒绝
     */
    MATCH_REJECTED(8, "撮合拒绝"),
    
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
               this == RISK_REJECTED || this == MATCH_REJECTED;
    }
}

