package com.exchange.common.core.enums;

/**
 * 交易方向
 */
public enum Side {
    
    /**
     * 买入（做多）
     */
    BUY(1, "买入"),
    
    /**
     * 卖出（做空）
     */
    SELL(2, "卖出");
    
    private final int code;
    private final String desc;
    
    Side(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
    
    public int getCode() {
        return code;
    }
    
    public String getDesc() {
        return desc;
    }
    
    public static Side of(int code) {
        // 兼容数据库存储: 0=BUY, 1=SELL
        if (code == 0) return BUY;
        if (code == 1) return SELL;
        // 标准枚举值: 1=BUY, 2=SELL
        for (Side side : values()) {
            if (side.code == code) {
                return side;
            }
        }
        throw new IllegalArgumentException("Unknown side code: " + code);
    }
    
    /**
     * 获取相反方向
     */
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}

