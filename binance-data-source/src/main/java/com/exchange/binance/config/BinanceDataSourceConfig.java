package com.exchange.binance.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 币安数据源配置
 * 
 * 配置项示例：
 * <pre>
 * binance.datasource:
 *   ws-url: "wss://fstream.binance.com"
 *   rest-base-url: "https://fapi.binance.com"
 *   symbols:
 *     - BTCUSDT
 *     - ETHUSDT
 *   source: "binance"
 *   depth-levels: 20
 *   batch-window-ms: 100
 *   reconnect:
 *     max-attempts: 10
 *     base-delay-ms: 1000
 *     max-delay-ms: 30000
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "binance.datasource")
public class BinanceDataSourceConfig {

    /**
     * WebSocket连接地址
     */
    private String wsUrl = "wss://fstream.binance.com";

    /**
     * REST API基础地址（用于拉取深度快照）
     */
    private String restBaseUrl = "https://fapi.binance.com";

    /**
     * 数据源标识（用于topic命名和消息source字段）
     */
    private String source = "binance";

    /**
     * 订阅的交易对列表
     */
    private List<String> symbols = new ArrayList<>(List.of("BTCUSDT"));

    /**
     * 深度数据档位 (5, 10, 20)
     * 注意：币安WebSocket推送的是完整深度，此配置仅影响快照获取
     */
    private int depthLevels = 20;

    /**
     * 批量聚合窗口（毫秒）
     * 0表示不聚合，实时推送
     */
    private int batchWindowMs = 100;

    /**
     * 深度更新频率 (100ms, 250ms, 500ms)
     */
    private String depthSpeed = "100ms";

    /**
     * 是否启用成交数据
     */
    private boolean tradeEnabled = true;

    /**
     * 是否启用聚合成交
     */
    private boolean aggTradeEnabled = false;

    /**
     * 是否启用Ticker
     */
    private boolean tickerEnabled = true;

    /**
     * 是否启用K线数据
     */
    private boolean klineEnabled = true;

    /**
     * K线周期列表（币安标准：1m,3m,5m,15m,30m,1h,2h,4h,6h,8h,12h,1d,3d,1w,1M）
     */
    private List<String> klineIntervals = new ArrayList<>(List.of(
            "1m", "3m", "5m", "15m", "30m",
            "1h", "2h", "4h", "6h", "8h", "12h",
            "1d", "3d", "1w", "1M"
    ));

    /**
     * 重连配置
     */
    private ReconnectConfig reconnect = new ReconnectConfig();

    /**
     * 心跳配置
     */
    private HeartbeatConfig heartbeat = new HeartbeatConfig();

    /**
     * Kafka配置
     */
    private KafkaConfig kafka = new KafkaConfig();

    @Data
    public static class ReconnectConfig {
        /**
         * 最大重连次数
         */
        private int maxAttempts = 10;

        /**
         * 基础延迟（毫秒）
         */
        private long baseDelayMs = 1000;

        /**
         * 最大延迟（毫秒）
         */
        private long maxDelayMs = 30000;

        /**
         * 指数退避倍数
         */
        private double multiplier = 2.0;
    }

    @Data
    public static class HeartbeatConfig {
        /**
         * 是否启用心跳
         */
        private boolean enabled = true;

        /**
         * 心跳间隔（秒）
         */
        private int intervalSec = 30;

        /**
         * 超时时间（秒）
         */
        private int timeoutSec = 10;
    }

    @Data
    public static class KafkaConfig {
        /**
         * 是否启用Kafka输出
         */
        private boolean enabled = true;

        /**
         * Topic前缀
         */
        private String topicPrefix = "market";

        /**
         * 深度Topic格式: {prefix}.depth.{symbol}
         */
        private String depthTopicFormat = "market.ext.%s.depth.%s";

        /**
         * 成交Topic格式: {prefix}.trade.{symbol}
         */
        private String tradeTopicFormat = "market.ext.%s.trade.%s";

        /**
         * 聚合成交Topic格式
         */
        private String aggTradeTopicFormat = "market.ext.%s.aggtrade.%s";

        /**
         * Ticker Topic格式
         */
        private String tickerTopicFormat = "market.ext.%s.ticker.%s";

        /**
         * Kline Topic格式
         */
        private String klineTopicFormat = "market.ext.%s.kline.%s.%s";
    }
}
