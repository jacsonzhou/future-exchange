package com.exchange.margin.enums;

import lombok.Getter;

/**
 * 保证金模式枚举
 * 
 * 全仓模式(CROSS)：所有仓位共享账户保证金
 * 逐仓模式(ISOLATED)：每个仓位独立保证金，互不影响
 */
@Getter
public enum MarginMode {
    
    /**
     * 全仓模式 - Cross Margin
     * 特点：
     * 1. 所有全仓仓位共享钱包余额作为保证金
     * 2. 盈亏实时影响账户保证金
     * 3. 爆仓可能导致全部仓位被强平
     */
    CROSS(1, "CROSS", "全仓模式"),
    
    /**
     * 逐仓模式 - Isolated Margin
     * 特点：
     * 1. 每个仓位独立保证金
     * 2. 最大亏损限于该仓位的逐仓保证金
     * 3. 可单独调整杠杆和追加保证金
     */
    ISOLATED(2, "ISOLATED", "逐仓模式");
    
    private final int code;
    private final String name;
    private final String desc;
    
    MarginMode(int code, String name, String desc) {
        this.code = code;
        this.name = name;
        this.desc = desc;
    }
    
    /**
     * 根据code获取枚举
     */
    public static MarginMode fromCode(int code) {
        for (MarginMode mode : values()) {
            if (mode.code == code) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown margin mode code: " + code);
    }
    
    /**
     * 根据name获取枚举
     */
    public static MarginMode fromName(String name) {
        for (MarginMode mode : values()) {
            if (mode.name.equalsIgnoreCase(name)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown margin mode name: " + name);
    }
    
    /**
     * 是否为全仓模式
     */
    public boolean isCross() {
        return this == CROSS;
    }
    
    /**
     * 是否为逐仓模式
     */
    public boolean isIsolated() {
        return this == ISOLATED;
    }
}
