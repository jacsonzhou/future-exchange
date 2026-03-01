package com.exchange.cfddealer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "cfd.dealer")
public class CfdDealerProperties {

    private String referenceRedisPrefix = "cfd:reference:book:";

    private long referenceMaxStaleMs = 0L;

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
    private List<String> symbols = List.of("BTCUSDT");

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
    private int referenceDepthLevels = 20;
}
