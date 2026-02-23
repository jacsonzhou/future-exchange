package com.exchange.marketmaker.enums;

/**
 * 做市商等级枚举
 */
public enum MmLevel {

    /**
     * 等级1 - 普通做市商
     * 月交易量 > 1000 BTC
     * Maker返佣: 0.02%
     * API限制: 1000 req/s
     */
    LEVEL_1(1, "普通做市商", -2_000L, 5_000L, 1000),

    /**
     * 等级2 - 高级做市商
     * 月交易量 > 5000 BTC
     * Maker返佣: 0.03%
     * API限制: 2000 req/s
     */
    LEVEL_2(2, "高级做市商", -3_000L, 4_000L, 2000),

    /**
     * 等级3 - 顶级做市商
     * 月交易量 > 20000 BTC
     * Maker返佣: 0.05%
     * API限制: 5000 req/s
     */
    LEVEL_3(3, "顶级做市商", -5_000L, 3_000L, 5000),

    /**
     * 等级4 - 战略做市商
     * 特殊邀请
     * Maker返佣: 0.08%
     * API限制: 10000 req/s
     */
    LEVEL_4(4, "战略做市商", -8_000L, 2_000L, 10000);

    private final int level;
    private final String desc;
    private final long makerFeeRate;  // 负数表示返佣，8位精度
    private final long takerFeeRate;  // 8位精度
    private final int apiLimitPerSec;

    MmLevel(int level, String desc, long makerFeeRate, long takerFeeRate, int apiLimitPerSec) {
        this.level = level;
        this.desc = desc;
        this.makerFeeRate = makerFeeRate;
        this.takerFeeRate = takerFeeRate;
        this.apiLimitPerSec = apiLimitPerSec;
    }

    public int getLevel() {
        return level;
    }

    public String getDesc() {
        return desc;
    }

    public long getMakerFeeRate() {
        return makerFeeRate;
    }

    public long getTakerFeeRate() {
        return takerFeeRate;
    }

    public int getApiLimitPerSec() {
        return apiLimitPerSec;
    }

    public static MmLevel fromLevel(int level) {
        for (MmLevel mmLevel : values()) {
            if (mmLevel.level == level) {
                return mmLevel;
            }
        }
        throw new IllegalArgumentException("Unknown MM level: " + level);
    }
}
