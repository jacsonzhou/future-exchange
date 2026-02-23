package com.exchange.binance.metrics;

import com.exchange.binance.client.BinanceWebSocketClient;
import com.exchange.binance.fetcher.BinanceSnapshotFetcher;
import com.exchange.binance.handler.BinanceMessageHandler;
import com.exchange.binance.manager.OrderBookManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 币安数据源监控指标收集器
 *
 * 职责：
 * - 收集并暴露Prometheus指标
 * - 定时更新订单簿状态指标
 * - 提供延迟统计
 *
 * 指标分类：
 * 1. 连接健康：连接状态、重连次数、心跳延迟
 * 2. 数据质量：深度更新频率、序号Gap次数、订单簿重建次数、价格异常次数
 * 3. 延迟监控：端到端延迟、处理延迟
 * 4. 吞吐量：消息接收速率、Kafka发布速率
 */
@Slf4j
@Component
public class BinanceMetricsCollector {

    private final MeterRegistry registry;

    @Autowired
    private BinanceWebSocketClient wsClient;

    @Autowired
    private BinanceMessageHandler messageHandler;

    @Autowired
    private BinanceSnapshotFetcher snapshotFetcher;

    // Symbol级别的指标
    private final ConcurrentHashMap<String, SymbolMetrics> symbolMetricsMap = new ConcurrentHashMap<>();

    public BinanceMetricsCollector(MeterRegistry registry) {
        this.registry = registry;
        log.info("[BinanceMetrics] Initialized");
    }

    @PostConstruct
    public void init() {
        // 注册全局指标
        registerGlobalMetrics();

        log.info("[BinanceMetrics] Metrics registered successfully");
    }

    /**
     * 注册全局指标
     */
    private void registerGlobalMetrics() {
        // 连接状态（0=断开，1=连接）
        Gauge.builder("binance.connection.status", wsClient, client -> client.isConnected() ? 1 : 0)
                .description("WebSocket connection status (0=disconnected, 1=connected)")
                .register(registry);

        // 连接时长（秒）
        Gauge.builder("binance.connection.uptime.seconds", wsClient, BinanceWebSocketClient::getUptimeSeconds)
                .description("WebSocket connection uptime in seconds")
                .register(registry);

        // 重连次数
        Gauge.builder("binance.connection.reconnect.count", wsClient, BinanceWebSocketClient::getReconnectCount)
                .description("Total WebSocket reconnection count")
                .register(registry);

        // 消息接收总数
        Gauge.builder("binance.messages.received.total", wsClient, BinanceWebSocketClient::getMessagesReceived)
                .description("Total messages received from Binance WebSocket")
                .register(registry);

        // 处理器指标
        Gauge.builder("binance.handler.messages.total", messageHandler,
                handler -> handler.getMetrics().totalMessagesHandled())
                .description("Total messages handled")
                .register(registry);

        Gauge.builder("binance.handler.messages.depth", messageHandler,
                handler -> handler.getMetrics().depthMessagesHandled())
                .description("Total depth messages handled")
                .register(registry);

        Gauge.builder("binance.handler.messages.trade", messageHandler,
                handler -> handler.getMetrics().tradeMessagesHandled())
                .description("Total trade messages handled")
                .register(registry);

        Gauge.builder("binance.handler.messages.ticker", messageHandler,
                handler -> handler.getMetrics().tickerMessagesHandled())
                .description("Total ticker messages handled")
                .register(registry);

        Gauge.builder("binance.handler.orderbooks.active", messageHandler,
                handler -> handler.getMetrics().activeOrderBooks())
                .description("Number of active order books")
                .register(registry);

        // 快照获取器指标
        Gauge.builder("binance.fetcher.success.count", snapshotFetcher,
                fetcher -> fetcher.getMetrics().getSuccessCount())
                .description("Successful snapshot fetch count")
                .register(registry);

        Gauge.builder("binance.fetcher.fail.count", snapshotFetcher,
                fetcher -> fetcher.getMetrics().getFailCount())
                .description("Failed snapshot fetch count")
                .register(registry);

        Gauge.builder("binance.fetcher.success.rate", snapshotFetcher,
                fetcher -> fetcher.getMetrics().getSuccessRate())
                .description("Snapshot fetch success rate (percentage)")
                .register(registry);

        Gauge.builder("binance.fetcher.latency.avg.ms", snapshotFetcher,
                fetcher -> fetcher.getMetrics().getAvgLatencyMs())
                .description("Average snapshot fetch latency in milliseconds")
                .register(registry);
    }

    /**
     * 定时更新订单簿指标（每10秒）
     */
    @Scheduled(fixedRate = 10000)
    public void updateOrderBookMetrics() {
        try {
            messageHandler.getAllOrderBookManagers().forEach((symbol, manager) -> {
                updateSymbolMetrics(symbol, manager);
            });
        } catch (Exception e) {
            log.error("[BinanceMetrics] Error updating orderbook metrics", e);
        }
    }

    /**
     * 更新单个symbol的指标
     */
    private void updateSymbolMetrics(String symbol, OrderBookManager manager) {
        SymbolMetrics metrics = symbolMetricsMap.computeIfAbsent(
                symbol,
                s -> new SymbolMetrics(registry, s, manager)
        );

        metrics.update(manager.getMetrics());
    }

    /**
     * 获取symbol级别的指标收集器
     */
    public SymbolMetrics getSymbolMetrics(String symbol) {
        return symbolMetricsMap.get(symbol);
    }

    /**
     * Symbol级别的指标收集器
     */
    public static class SymbolMetrics {
        private final String symbol;
        private final MeterRegistry registry;

        // 订单簿状态（0=INIT, 1=ACTIVE, 2=STALE, 3=REBUILDING）
        private Gauge orderBookStatus;

        // 订单簿深度
        private Gauge bidLevels;
        private Gauge askLevels;

        // 更新计数
        private Gauge updateCount;
        private Gauge gapCount;
        private Gauge rebuildCount;
        private Gauge priceAnomalyCount;
        private Gauge staleUpdateCount;

        // 最后更新序号
        private Gauge lastUpdateId;

        public SymbolMetrics(MeterRegistry registry, String symbol, OrderBookManager manager) {
            this.symbol = symbol;
            this.registry = registry;

            registerMetrics(manager);
        }

        private void registerMetrics(OrderBookManager manager) {
            // 订单簿状态
            orderBookStatus = Gauge.builder("binance.orderbook.status", manager,
                    m -> statusToValue(m.getStatus()))
                    .tag("symbol", symbol)
                    .description("OrderBook status (0=INIT, 1=ACTIVE, 2=STALE, 3=REBUILDING)")
                    .register(registry);

            // 深度档位数
            bidLevels = Gauge.builder("binance.orderbook.depth", manager,
                    m -> m.getMetrics().getBidLevels())
                    .tag("symbol", symbol)
                    .tag("side", "bid")
                    .description("Number of bid levels in orderbook")
                    .register(registry);

            askLevels = Gauge.builder("binance.orderbook.depth", manager,
                    m -> m.getMetrics().getAskLevels())
                    .tag("symbol", symbol)
                    .tag("side", "ask")
                    .description("Number of ask levels in orderbook")
                    .register(registry);

            // 更新计数
            updateCount = Gauge.builder("binance.orderbook.update.count", manager,
                    m -> m.getMetrics().getUpdateCount())
                    .tag("symbol", symbol)
                    .description("Total depth update count")
                    .register(registry);

            // Gap计数
            gapCount = Gauge.builder("binance.orderbook.gap.count", manager,
                    m -> m.getMetrics().getGapCount())
                    .tag("symbol", symbol)
                    .description("Total sequence gap count")
                    .register(registry);

            // 重建计数
            rebuildCount = Gauge.builder("binance.orderbook.rebuild.count", manager,
                    m -> m.getMetrics().getRebuildCount())
                    .tag("symbol", symbol)
                    .description("Total orderbook rebuild count")
                    .register(registry);

            // 价格异常计数
            priceAnomalyCount = Gauge.builder("binance.orderbook.price_anomaly.count", manager,
                    m -> m.getMetrics().getPriceAnomalyCount())
                    .tag("symbol", symbol)
                    .description("Total price anomaly count")
                    .register(registry);

            // 过期更新计数
            staleUpdateCount = Gauge.builder("binance.orderbook.stale_update.count", manager,
                    m -> m.getMetrics().getStaleUpdateCount())
                    .tag("symbol", symbol)
                    .description("Total stale update count")
                    .register(registry);

            // 最后更新序号
            lastUpdateId = Gauge.builder("binance.orderbook.last_update_id", manager,
                    m -> (double) m.getLastUpdateId())
                    .tag("symbol", symbol)
                    .description("Last processed update ID")
                    .register(registry);
        }

        /**
         * 更新指标（由定时任务调用）
         */
        public void update(OrderBookManager.OrderBookMetrics metrics) {
            // Gauge会自动从OrderBookManager读取最新值，无需手动更新
            // 这里预留用于未来可能的Counter类型指标
        }

        /**
         * 状态枚举转数值
         */
        private double statusToValue(OrderBookManager.OrderBookStatus status) {
            return switch (status) {
                case INIT -> 0;
                case ACTIVE -> 1;
                case STALE -> 2;
                case REBUILDING -> 3;
            };
        }
    }

    /**
     * 记录延迟（供外部调用）
     */
    public void recordLatency(String symbol, String operation, long nanos) {
        Timer.builder("binance.operation.latency")
                .tag("symbol", symbol)
                .tag("operation", operation)
                .description("Operation latency in nanoseconds")
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录事件（供外部调用）
     */
    public void recordEvent(String symbol, String event) {
        Counter.builder("binance.events")
                .tag("symbol", symbol)
                .tag("event", event)
                .description("Event counter")
                .register(registry)
                .increment();
    }
}
