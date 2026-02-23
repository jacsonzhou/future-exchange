package com.exchange.binance.fetcher;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 币安快照获取器
 *
 * 职责：
 * - 通过REST API获取订单簿快照
 * - 用于订单簿初始化和重建
 *
 * 币安REST API限制：
 * - 限流：根据limit不同权重不同
 *   - limit=5-100: 权重1
 *   - limit=500: 权重5
 *   - limit=1000: 权重10
 *   - limit=5000: 权重50
 * - 默认限制：1200请求权重/分钟
 *
 * API文档：
 * https://binance-docs.github.io/apidocs/spot/en/#order-book
 */
@Slf4j
@Component
public class BinanceSnapshotFetcher {

    private final HttpClient httpClient;

    // 币安REST API地址
    private static final String SNAPSHOT_URL_TEMPLATE =
            "https://api.binance.com/api/v3/depth?symbol=%s&limit=%d";

    // 备用地址（中国大陆可能需要）
    private static final String SNAPSHOT_URL_TEMPLATE_CN =
            "https://api.binance.com/api/v3/depth?symbol=%s&limit=%d";

    // 金额精度：8位小数
    private static final int PRICE_SCALE = 8;
    private static final long PRICE_MULTIPLIER = 100_000_000L;

    // 请求超时配置
    private static final int CONNECT_TIMEOUT_SEC = 10;
    private static final int REQUEST_TIMEOUT_SEC = 5;

    // 统计指标
    private long successCount = 0;
    private long failCount = 0;
    private long totalLatencyMs = 0;

    public BinanceSnapshotFetcher() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
                .build();
        log.info("[SnapshotFetcher] Initialized with timeout={}s", CONNECT_TIMEOUT_SEC);
    }

    /**
     * 获取深度快照
     *
     * @param symbol 交易对，如 BTCUSDT
     * @param limit 档位数，5/10/20/50/100/500/1000/5000
     * @return 快照数据，失败返回null
     */
    public DepthSnapshot fetchSnapshot(String symbol, int limit) {
        long startTime = System.currentTimeMillis();

        try {
            // 验证参数
            if (!isValidLimit(limit)) {
                log.warn("[SnapshotFetcher] Invalid limit: {}, using default 1000", limit);
                limit = 1000;
            }

            String url = String.format(SNAPSHOT_URL_TEMPLATE, symbol.toUpperCase(), limit);

            log.debug("[SnapshotFetcher] Fetching snapshot for {}: url={}", symbol, url);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
                    .header("User-Agent", "BinanceDataSource/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            long latency = System.currentTimeMillis() - startTime;
            totalLatencyMs += latency;

            if (response.statusCode() != 200) {
                failCount++;
                log.error("[SnapshotFetcher] Failed to fetch snapshot for {}: status={}, body={}",
                        symbol, response.statusCode(), response.body());
                return null;
            }

            DepthSnapshot snapshot = parseSnapshot(symbol, response.body());

            if (snapshot != null) {
                successCount++;
                log.info("[SnapshotFetcher] ✅ Fetched snapshot for {}: lastUpdateId={}, " +
                                "bids={}, asks={}, latency={}ms",
                        symbol, snapshot.lastUpdateId, snapshot.bids.size(),
                        snapshot.asks.size(), latency);
            } else {
                failCount++;
            }

            return snapshot;

        } catch (java.net.http.HttpTimeoutException e) {
            failCount++;
            long latency = System.currentTimeMillis() - startTime;
            log.error("[SnapshotFetcher] Timeout fetching snapshot for {}: latency={}ms",
                    symbol, latency);
            return null;

        } catch (Exception e) {
            failCount++;
            long latency = System.currentTimeMillis() - startTime;
            log.error("[SnapshotFetcher] Error fetching snapshot for {}: {}, latency={}ms",
                    symbol, e.getMessage(), latency);
            return null;
        }
    }

    /**
     * 解析快照响应
     */
    private DepthSnapshot parseSnapshot(String symbol, String body) {
        try {
            JSONObject json = JSON.parseObject(body);

            // 检查错误响应
            if (json.containsKey("code")) {
                int code = json.getIntValue("code");
                String msg = json.getString("msg");
                log.error("[SnapshotFetcher] API error for {}: code={}, msg={}", symbol, code, msg);
                return null;
            }

            long lastUpdateId = json.getLongValue("lastUpdateId");
            JSONArray bidsJson = json.getJSONArray("bids");
            JSONArray asksJson = json.getJSONArray("asks");

            if (lastUpdateId == 0) {
                log.error("[SnapshotFetcher] Invalid lastUpdateId=0 for {}", symbol);
                return null;
            }

            List<long[]> bids = parseLevels(bidsJson);
            List<long[]> asks = parseLevels(asksJson);

            // 数据质量检查
            if (bids.isEmpty() && asks.isEmpty()) {
                log.error("[SnapshotFetcher] Empty orderbook for {}", symbol);
                return null;
            }

            // 价格合理性检查
            if (!bids.isEmpty() && !asks.isEmpty()) {
                long bestBid = bids.get(0)[0];
                long bestAsk = asks.get(0)[0];
                if (bestBid >= bestAsk) {
                    log.error("[SnapshotFetcher] Invalid prices for {}: bid={} >= ask={}",
                            symbol, bestBid, bestAsk);
                    return null;
                }
            }

            return new DepthSnapshot(symbol, lastUpdateId, bids, asks);

        } catch (Exception e) {
            log.error("[SnapshotFetcher] Failed to parse snapshot for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * 解析价格档位
     */
    private List<long[]> parseLevels(JSONArray array) {
        List<long[]> levels = new ArrayList<>();

        if (array == null) {
            return levels;
        }

        for (int i = 0; i < array.size(); i++) {
            JSONArray level = array.getJSONArray(i);
            if (level != null && level.size() >= 2) {
                String priceStr = level.getString(0);
                String qtyStr = level.getString(1);

                long price = parsePrice(priceStr);
                long qty = parsePrice(qtyStr);

                // 只加载qty > 0的档位
                if (qty > 0) {
                    levels.add(new long[]{price, qty});
                }
            }
        }

        return levels;
    }

    /**
     * 解析价格字符串为long（8位精度）
     *
     * 示例："50000.50" -> 5000050000000
     *
     * @param priceStr 价格字符串
     * @return 8位精度long值
     */
    private long parsePrice(String priceStr) {
        if (priceStr == null || priceStr.isEmpty()) {
            return 0;
        }

        try {
            BigDecimal price = new BigDecimal(priceStr);
            return price.multiply(BigDecimal.valueOf(PRICE_MULTIPLIER))
                    .setScale(0, RoundingMode.DOWN)
                    .longValue();
        } catch (NumberFormatException e) {
            log.warn("[SnapshotFetcher] Invalid price format: {}", priceStr);
            return 0;
        }
    }

    /**
     * 验证limit参数
     */
    private boolean isValidLimit(int limit) {
        return limit == 5 || limit == 10 || limit == 20 || limit == 50 ||
               limit == 100 || limit == 500 || limit == 1000 || limit == 5000;
    }

    /**
     * 获取统计指标
     */
    public FetcherMetrics getMetrics() {
        long avgLatencyMs = successCount > 0 ? totalLatencyMs / successCount : 0;
        return new FetcherMetrics(successCount, failCount, avgLatencyMs);
    }

    /**
     * 快照数据模型
     */
    @Getter
    public static class DepthSnapshot {
        private final String symbol;
        private final long lastUpdateId;
        private final List<long[]> bids;
        private final List<long[]> asks;

        public DepthSnapshot(String symbol, long lastUpdateId,
                           List<long[]> bids, List<long[]> asks) {
            this.symbol = symbol;
            this.lastUpdateId = lastUpdateId;
            this.bids = bids;
            this.asks = asks;
        }

        @Override
        public String toString() {
            return String.format("DepthSnapshot[%s, lastId=%d, bids=%d, asks=%d]",
                    symbol, lastUpdateId, bids.size(), asks.size());
        }
    }

    /**
     * 统计指标
     */
    @Getter
    public static class FetcherMetrics {
        private final long successCount;
        private final long failCount;
        private final long avgLatencyMs;

        public FetcherMetrics(long successCount, long failCount, long avgLatencyMs) {
            this.successCount = successCount;
            this.failCount = failCount;
            this.avgLatencyMs = avgLatencyMs;
        }

        public double getSuccessRate() {
            long total = successCount + failCount;
            return total > 0 ? (double) successCount / total * 100 : 0;
        }

        @Override
        public String toString() {
            return String.format("FetcherMetrics[success=%d, fail=%d, successRate=%.2f%%, avgLatency=%dms]",
                    successCount, failCount, getSuccessRate(), avgLatencyMs);
        }
    }
}
