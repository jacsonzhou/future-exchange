package com.exchange.marketmaker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 做市商配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "market-maker")
public class MmConfig {

    private Evaluation evaluation = new Evaluation();
    private Fee fee = new Fee();
    private Reward reward = new Reward();

    @Data
    public static class Evaluation {
        /**
         * 最小挂单时间占比（8位精度）
         */
        private Long minQuoteTimeRatio = 80_00000000L;

        /**
         * 最大买卖价差（8位精度）
         */
        private Long maxSpread = 100000L;

        /**
         * 最小挂单深度（USDT，8位精度）
         */
        private Long minDepth = 50000_00000000L;

        /**
         * 最小成交率（8位精度）
         */
        private Long minFillRate = 50_00000000L;
    }

    @Data
    public static class Fee {
        // 等级1费率
        private Long level1Maker = 20000L;
        private Long level1Taker = 50000L;

        // 等级2费率
        private Long level2Maker = 10000L;
        private Long level2Taker = 40000L;

        // 等级3费率
        private Long level3Maker = 5000L;
        private Long level3Taker = 30000L;

        // 等级4费率
        private Long level4Maker = 0L;
        private Long level4Taker = 20000L;
    }

    @Data
    public static class Reward {
        /**
         * 达标奖励基础金额（USDT，8位精度）
         */
        private Long baseQualifiedReward = 10000_00000000L;

        /**
         * 优秀奖励基础金额（USDT，8位精度）
         */
        private Long baseExcellentReward = 50000_00000000L;
    }
}
