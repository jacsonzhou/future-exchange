package com.exchange.cfddealer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Locale;

@Data
@Configuration
@ConfigurationProperties(prefix = "cfd.dealer")
public class CfdDealerProperties {

    private static final int DEFAULT_REFERENCE_DEPTH_LEVELS = 20;
    private static final int DEFAULT_REFERENCE_MIN_DEPTH_LEVELS = 1;

    /**
     * REDIS | MEMORY | DUAL, default DUAL.
     */
    private String pricingMode = "DUAL";

    private String referenceRedisPrefix = "cfd:reference:book:";

    /**
     * Legacy flat config key: cfd.dealer.reference-max-stale-ms
     */
    private long referenceMaxStaleMs = 0L;

    /**
     * Legacy flat config key: cfd.dealer.reference-depth-levels
     */
    private int referenceDepthLevels = DEFAULT_REFERENCE_DEPTH_LEVELS;

    /**
     * New nested config key: cfd.dealer.reference.*
     */
    private ReferenceProperties reference = new ReferenceProperties();

    private long dealerAccountId = 0L;

    private String liquiditySource = "BINANCE_REF";

    private String commandTopicPattern = "cfd-order-command-.*";

    /**
     * 参考深度topic pattern（事件驱动触发）
     */
    private String referenceTopicPattern = "market.ext.binance.depth.*";

    /**
     * 参考深度消费组（独立于指令消费组）
     */
    private String referenceConsumerGroup = "cfd-dealer-reference";

    private String orderStateTopicPrefix = "order-state-";

    private String tradeTopicPrefix = "trade-event-";

    /**
     * WORKING 订单触发扫描交易对。
     */
    private List<String> symbols = List.of(
        "BTCUSDT",
        "ETHUSDT",
        "SOLUSDT",
        "BNBUSDT",
        "XRPUSDT"
    );

    /**
     * 每次扫描每个交易对最多处理的 WORKING 订单数。
     */
    private int triggerBatchSize = 200;

    /**
     * WORKING 订单触发扫描间隔（毫秒）。
     */
    private long limitTriggerIntervalMs = 100L;

    /**
     * 是否启用轮询触发。默认关闭，优先使用深度事件驱动触发。
     */
    private boolean pollingEnabled = false;

    /**
     * 参考盘口缓存深度层数（写入Redis快照时截断）。
     */
    public int getReferenceDepthLevels() {
        if (reference != null && reference.getDepthLevels() != null && reference.getDepthLevels() > 0) {
            return reference.getDepthLevels();
        }
        if (referenceDepthLevels > 0) {
            return referenceDepthLevels;
        }
        return DEFAULT_REFERENCE_DEPTH_LEVELS;
    }

    public long getReferenceMaxStaleMs() {
        if (reference != null && reference.getMaxStaleMs() != null) {
            return Math.max(0L, reference.getMaxStaleMs());
        }
        return Math.max(0L, referenceMaxStaleMs);
    }

    public int getReferenceMinDepthLevels() {
        if (reference != null && reference.getMinDepthLevels() != null && reference.getMinDepthLevels() > 0) {
            return reference.getMinDepthLevels();
        }
        return DEFAULT_REFERENCE_MIN_DEPTH_LEVELS;
    }

    public String getPricingMode() {
        if (pricingMode == null || pricingMode.isBlank()) {
            return "DUAL";
        }
        return pricingMode.trim().toUpperCase(Locale.ROOT);
    }

    @Data
    public static class ReferenceProperties {
        private Long maxStaleMs;
        private Integer minDepthLevels;
        private Integer depthLevels;
    }
}
