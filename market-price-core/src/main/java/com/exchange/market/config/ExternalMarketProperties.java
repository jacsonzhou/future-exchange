package com.exchange.market.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部行情配置（开发阶段用于 Binance 数据闭环）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "market-data.external")
public class ExternalMarketProperties {

    /**
     * 是否启用外部行情接入与回补。
     */
    private boolean enabled = true;

    /**
     * 外部数据源标识（对应 topic: market.ext.{source}.*）。
     */
    private String source = "binance";

    /**
     * 外部 REST 基础地址（用于历史 K 线回补）。
     */
    private String restBaseUrl = "https://fapi.binance.com";

    /**
     * 需要处理的交易对。
     */
    private List<String> symbols = new ArrayList<>(List.of(
            "BTCUSDT",
            "ETHUSDT",
            "BNBUSDT",
            "SOLUSDT",
            "XRPUSDT"
    ));

    /**
     * 回补配置。
     */
    private Backfill backfill = new Backfill();

    /**
     * 24h ticker 配置。
     */
    private Ticker ticker = new Ticker();

    @Data
    public static class Backfill {
        /**
         * 是否在启动时执行回补。
         */
        private boolean enabled = true;

        /**
         * 启动回补延迟（毫秒），避免与服务启动抢资源。
         */
        private long startupDelayMs = 5000L;

        /**
         * 回补周期。建议固定 1m，其他周期由聚合查询计算。
         */
        private String interval = "1m";

        /**
         * 首次无数据时回补天数。
         */
        private int initialDays = 30;

        /**
         * 单次 REST 拉取条数（Binance 最大 1500）。
         */
        private int batchLimit = 1000;

        /**
         * 分页请求间隔（毫秒），降低外部 API 压力。
         */
        private long requestDelayMs = 100L;
    }

    @Data
    public static class Ticker {
        /**
         * 外部 ticker 最新数据最大可接受陈旧时间。
         */
        private long externalFreshMs = 15_000L;

        /**
         * 回退聚合刷新周期（毫秒）。
         */
        private long fallbackRefreshMs = 1_000L;
    }
}

