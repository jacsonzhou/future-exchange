package com.exchange.margin.enums;

import lombok.Getter;

/**
 * 风险等级枚举
 * 
 * 用于评估全仓账户的风险状态
 */
@Getter
public enum RiskLevel {
    
    /**
     * 安全 - 保证金充足
     * 保证金率 > 100%
     */
    SAFE(1, "SAFE", "安全", 10000L),
    
    /**
     * 警告 - 需要关注
     * 保证金率在 50% - 100% 之间
     */
    WARNING(2, "WARNING", "警告", 5000L),
    
    /**
     * 危险 - 接近爆仓
     * 保证金率在 10% - 50% 之间
     */
    DANGER(3, "DANGER", "危险", 1000L),
    
    /**
     * 爆仓 - 触发强平
     * 保证金率 <= 10%
     */
    LIQUIDATION(4, "LIQUIDATION", "爆仓", 0L);
    
    private final int code;
    private final String name;
    private final String desc;
    
    /**
     * 该等级的保证金率阈值（万分比）
     * 例如：10000 = 100%
     */
    private final long marginRatioThreshold;
    
    RiskLevel(int code, String name, String desc, long marginRatioThreshold) {
        this.code = code;
        this.name = name;
        this.desc = desc;
        this.marginRatioThreshold = marginRatioThreshold;
    }
    
    /**
     * 根据保证金率判断风险等级
     * @param marginRatio 保证金率（万分比）
     * @return 风险等级
     */
    public static RiskLevel fromMarginRatio(long marginRatio) {
        if (marginRatio <= LIQUIDATION.marginRatioThreshold) {
            return LIQUIDATION;
        } else if (marginRatio <= DANGER.marginRatioThreshold) {
            return DANGER;
        } else if (marginRatio <= WARNING.marginRatioThreshold) {
            return WARNING;
        } else {
            return SAFE;
        }
    }
    
    /**
     * 是否需要预警
     */
    public boolean needsWarning() {
        return this.code >= WARNING.code;
    }
    
    /**
     * 是否需要强平
     */
    public boolean needsLiquidation() {
        return this == LIQUIDATION;
    }
}
