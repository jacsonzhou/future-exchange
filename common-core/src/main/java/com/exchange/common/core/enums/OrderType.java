package com.exchange.common.core.enums;

/**
 * 订单类型
 */
public enum OrderType {
    
    /**
     * 限价单
     */
    LIMIT(1, "限价单"),
    
    /**
     * 市价单
     */
    MARKET(2, "市价单"),
    
    /**
     * 只做maker单（Post-Only）
     */
    POST_ONLY(3, "只做Maker"),
    
    /**
     * 全部成交或撤销（Fill or Kill）
     */
    FOK(4, "全部成交或撤销"),
    
    /**
     * 立即成交或撤销（Immediate or Cancel）
     */
    IOC(5, "立即成交或撤销");
    
    private final int code;
    private final String desc;
    
    OrderType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
    
    public int getCode() {
        return code;
    }
    
    public String getDesc() {
        return desc;
    }
    
    public static OrderType of(int code) {
        for (OrderType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown order type code: " + code);
    }
}







