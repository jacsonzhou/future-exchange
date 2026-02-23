# 币安数据源技术实施指南

> **版本**: v1.0.0
> **日期**: 2026-02-19
> **目标**: Phase 1.5 数据质量增强实施

---

## 一、当前代码问题分析

### 1.1 ❌ 关键缺陷

#### 问题1：缺少订单簿序号检查
**位置**: `BinanceMessageHandler.handleDepthUpdate()`

**当前代码**:
```java
// BinanceMessageHandler.java:95
private void handleDepthUpdate(JSONObject message) {
    // ... 解析字段
    BinanceDepth depth = BinanceDepth.builder()
            .symbol(symbol)
            .eventTime(eventTime)
            .firstUpdateId(firstUpdateId)
            .lastUpdateId(lastUpdateId)
            .bids(bids)
            .asks(asks)
            .build();

    publisher.publishDepth(depth);  // ❌ 直接发布，没有序号检查
}
```

**问题**：
- 没有维护 `lastUpdateId` 状态
- 没有检查序号连续性
- 发生Gap时无法检测，导致订单簿数据不一致

**影响**：
- 网络抖动时深度数据不准确
- 无法发现数据丢失
- 用户可能看到错误的盘口

---

#### 问题2：没有本地订单簿维护
**当前架构**：
```
WebSocket → 解析 → 直接发布Kafka
```

**应该是**：
```
WebSocket → 解析 → 序号检查 → 更新本地OrderBook → 发布Kafka
                                    ↓
                              状态：ACTIVE/STALE/REBUILDING
```

**问题**：
- 无法提供订单簿快照查询（REST API返回的是Redis缓存，可能过期）
- 无法进行数据校验
- 无法在重建期间暂停发布

---

#### 问题3：缺少重建机制
**场景**：WebSocket重连后，序号可能跳跃

**当前实现**：
```java
// BinanceWebSocketClient.java:383
if (statusCode != WebSocket.NORMAL_CLOSURE) {
    scheduleReconnect();  // ❌ 重连后直接继续接收，可能产生Gap
}
```

**问题**：
- 重连后没有重新获取快照
- 增量更新可能与旧状态不连续
- 导致订单簿数据错误

---

#### 问题4：缺少数据质量监控
**当前监控指标**：
```java
// BinanceWebSocketClient.java:434
public long getMessagesReceived() { ... }  // 仅统计消息数
public long getUptimeSeconds() { ... }     // 仅统计在线时长
```

**缺少**：
- 延迟统计（P50/P99）
- 序号Gap计数
- 重建次数统计
- 价格异常计数

---

### 1.2 ⚠️ 次要问题

1. **没有价格合理性校验**：可能发布异常价格数据
2. **缺少延迟日志**：无法诊断性能问题
3. **异常处理不完善**：部分异常被吞掉，没有告警
4. **配置硬编码**：部分参数硬编码在代码中

---

## 二、实施方案

### 2.1 新增组件：OrderBookManager

**职责**：
- 维护本地订单簿状态
- 序号连续性检查
- 触发快照重建
- 提供订单簿快照查询

**类设计**：

```java
package com.exchange.binance.manager;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 订单簿管理器
 *
 * 职责：
 * - 维护本地订单簿（增量更新）
 * - 序号连续性检查
 * - 触发快照重建
 * - 提供BBO查询
 */
@Slf4j
public class OrderBookManager {

    @Getter
    private final String symbol;

    /**
     * 订单簿状态
     * INIT: 初始化状态，等待首次快照
     * ACTIVE: 正常运行，序号连续
     * STALE: 检测到Gap，数据可能不准
     * REBUILDING: 正在重建中
     */
    @Getter
    private volatile OrderBookStatus status = OrderBookStatus.INIT;

    /**
     * 最后处理的更新序号
     */
    @Getter
    private volatile long lastUpdateId = 0;

    /**
     * 买盘：价格 -> 数量（降序排列）
     */
    private final ConcurrentSkipListMap<Long, Long> bids =
            new ConcurrentSkipListMap<>(Comparator.reverseOrder());

    /**
     * 卖盘：价格 -> 数量（升序排列）
     */
    private final ConcurrentSkipListMap<Long, Long> asks =
            new ConcurrentSkipListMap<>();

    /**
     * 重建期间缓存的增量更新
     */
    private final List<DepthUpdate> bufferedUpdates =
            Collections.synchronizedList(new ArrayList<>());

    /**
     * 统计指标
     */
    private long gapCount = 0;
    private long rebuildCount = 0;
    private long updateCount = 0;

    public OrderBookManager(String symbol) {
        this.symbol = symbol;
    }

    /**
     * 处理深度更新
     *
     * @return true=更新成功，false=需要重建
     */
    public boolean onDepthUpdate(long firstUpdateId, long lastUpdateId,
                                 List<long[]> bidUpdates,
                                 List<long[]> askUpdates) {
        updateCount++;

        // Case 1: 初始化状态，需要先获取快照
        if (status == OrderBookStatus.INIT) {
            log.warn("[OrderBook-{}] Received update in INIT state, need snapshot first", symbol);
            return false;  // 触发重建
        }

        // Case 2: 重建中，缓存更新
        if (status == OrderBookStatus.REBUILDING) {
            bufferedUpdates.add(new DepthUpdate(firstUpdateId, lastUpdateId, bidUpdates, askUpdates));
            log.debug("[OrderBook-{}] Buffered update during rebuilding: U={}, u={}",
                    symbol, firstUpdateId, lastUpdateId);
            return false;
        }

        // Case 3: 序号检查
        if (firstUpdateId <= this.lastUpdateId + 1 && lastUpdateId >= this.lastUpdateId + 1) {
            // 正常更新
            applyUpdate(bidUpdates, askUpdates);
            this.lastUpdateId = lastUpdateId;
            this.status = OrderBookStatus.ACTIVE;
            return true;

        } else if (lastUpdateId < this.lastUpdateId + 1) {
            // 旧消息，丢弃
            log.trace("[OrderBook-{}] Discard old update: u={}, last={}",
                    symbol, lastUpdateId, this.lastUpdateId);
            return true;  // 不需要重建

        } else {
            // Gap detected
            log.error("[OrderBook-{}] Sequence GAP detected! U={}, u={}, last={}",
                    symbol, firstUpdateId, lastUpdateId, this.lastUpdateId);
            gapCount++;
            status = OrderBookStatus.STALE;
            return false;  // 触发重建
        }
    }

    /**
     * 应用增量更新到本地订单簿
     */
    private void applyUpdate(List<long[]> bidUpdates, List<long[]> askUpdates) {
        // 更新买盘
        for (long[] bid : bidUpdates) {
            long price = bid[0];
            long qty = bid[1];

            if (qty == 0) {
                bids.remove(price);  // 删除档位
            } else {
                bids.put(price, qty);
            }
        }

        // 更新卖盘
        for (long[] ask : askUpdates) {
            long price = ask[0];
            long qty = ask[1];

            if (qty == 0) {
                asks.remove(price);
            } else {
                asks.put(price, qty);
            }
        }
    }

    /**
     * 从快照初始化订单簿
     *
     * @param lastUpdateId 快照的lastUpdateId
     * @param snapshotBids 快照买盘
     * @param snapshotAsks 快照卖盘
     */
    public void initFromSnapshot(long lastUpdateId,
                                 List<long[]> snapshotBids,
                                 List<long[]> snapshotAsks) {
        status = OrderBookStatus.REBUILDING;

        // 清空旧数据
        bids.clear();
        asks.clear();

        // 加载快照
        for (long[] bid : snapshotBids) {
            bids.put(bid[0], bid[1]);
        }
        for (long[] ask : snapshotAsks) {
            asks.put(ask[0], ask[1]);
        }

        this.lastUpdateId = lastUpdateId;

        // 应用缓存的增量更新（序号 > snapshot.lastUpdateId）
        int appliedCount = 0;
        for (DepthUpdate update : bufferedUpdates) {
            if (update.firstUpdateId <= this.lastUpdateId + 1) {
                applyUpdate(update.bidUpdates, update.askUpdates);
                this.lastUpdateId = update.lastUpdateId;
                appliedCount++;
            }
        }

        bufferedUpdates.clear();
        status = OrderBookStatus.ACTIVE;
        rebuildCount++;

        log.info("[OrderBook-{}] Rebuilt successfully: lastUpdateId={}, appliedBuffered={}",
                symbol, lastUpdateId, appliedCount);
    }

    /**
     * 获取BBO（最优买卖价）
     */
    public BBO getBBO() {
        Map.Entry<Long, Long> bestBid = bids.firstEntry();
        Map.Entry<Long, Long> bestAsk = asks.firstEntry();

        return new BBO(
                bestBid != null ? bestBid.getKey() : 0,
                bestBid != null ? bestBid.getValue() : 0,
                bestAsk != null ? bestAsk.getKey() : 0,
                bestAsk != null ? bestAsk.getValue() : 0
        );
    }

    /**
     * 获取深度快照（限制档位）
     */
    public Snapshot getSnapshot(int depth) {
        List<long[]> bidList = bids.entrySet().stream()
                .limit(depth)
                .map(e -> new long[]{e.getKey(), e.getValue()})
                .toList();

        List<long[]> askList = asks.entrySet().stream()
                .limit(depth)
                .map(e -> new long[]{e.getKey(), e.getValue()})
                .toList();

        return new Snapshot(symbol, lastUpdateId, bidList, askList, status);
    }

    /**
     * 获取统计指标
     */
    public OrderBookMetrics getMetrics() {
        return new OrderBookMetrics(
                symbol,
                status,
                lastUpdateId,
                bids.size(),
                asks.size(),
                updateCount,
                gapCount,
                rebuildCount
        );
    }

    // ========== 内部类 ==========

    public enum OrderBookStatus {
        INIT,          // 初始化
        ACTIVE,        // 正常运行
        STALE,         // 数据过期（检测到Gap）
        REBUILDING     // 重建中
    }

    @Getter
    public static class DepthUpdate {
        private final long firstUpdateId;
        private final long lastUpdateId;
        private final List<long[]> bidUpdates;
        private final List<long[]> askUpdates;

        public DepthUpdate(long firstUpdateId, long lastUpdateId,
                          List<long[]> bidUpdates, List<long[]> askUpdates) {
            this.firstUpdateId = firstUpdateId;
            this.lastUpdateId = lastUpdateId;
            this.bidUpdates = new ArrayList<>(bidUpdates);
            this.askUpdates = new ArrayList<askUpdates);
        }
    }

    @Getter
    public static class BBO {
        private final long bidPrice;
        private final long bidQty;
        private final long askPrice;
        private final long askQty;

        public BBO(long bidPrice, long bidQty, long askPrice, long askQty) {
            this.bidPrice = bidPrice;
            this.bidQty = bidQty;
            this.askPrice = askPrice;
            this.askQty = askQty;
        }

        public long getSpread() {
            return askPrice - bidPrice;
        }
    }

    @Getter
    public static class Snapshot {
        private final String symbol;
        private final long lastUpdateId;
        private final List<long[]> bids;
        private final List<long[]> asks;
        private final OrderBookStatus status;

        public Snapshot(String symbol, long lastUpdateId,
                       List<long[]> bids, List<long[]> asks,
                       OrderBookStatus status) {
            this.symbol = symbol;
            this.lastUpdateId = lastUpdateId;
            this.bids = bids;
            this.asks = asks;
            this.status = status;
        }
    }

    @Getter
    public static class OrderBookMetrics {
        private final String symbol;
        private final OrderBookStatus status;
        private final long lastUpdateId;
        private final int bidLevels;
        private final int askLevels;
        private final long updateCount;
        private final long gapCount;
        private final long rebuildCount;

        public OrderBookMetrics(String symbol, OrderBookStatus status,
                               long lastUpdateId, int bidLevels, int askLevels,
                               long updateCount, long gapCount, long rebuildCount) {
            this.symbol = symbol;
            this.status = status;
            this.lastUpdateId = lastUpdateId;
            this.bidLevels = bidLevels;
            this.askLevels = askLevels;
            this.updateCount = updateCount;
            this.gapCount = gapCount;
            this.rebuildCount = rebuildCount;
        }
    }
}
```

---

### 2.2 新增组件：SnapshotFetcher

**职责**：调用币安REST API获取快照

```java
package com.exchange.binance.fetcher;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
 * 通过REST API获取订单簿快照
 */
@Slf4j
@Component
public class BinanceSnapshotFetcher {

    private final HttpClient httpClient;
    private static final String SNAPSHOT_URL_TEMPLATE =
            "https://api.binance.com/api/v3/depth?symbol=%s&limit=%d";

    // 金额精度
    private static final long PRICE_MULTIPLIER = 100_000_000L;

    public BinanceSnapshotFetcher() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 获取深度快照
     *
     * @param symbol 交易对，如 BTCUSDT
     * @param limit 档位数，5/10/20/50/100/500/1000
     * @return 快照数据
     */
    public DepthSnapshot fetchSnapshot(String symbol, int limit) {
        try {
            String url = String.format(SNAPSHOT_URL_TEMPLATE, symbol, limit);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                log.error("[SnapshotFetcher] Failed to fetch snapshot for {}: status={}, body={}",
                        symbol, response.statusCode(), response.body());
                return null;
            }

            return parseSnapshot(symbol, response.body());

        } catch (Exception e) {
            log.error("[SnapshotFetcher] Error fetching snapshot for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * 解析快照响应
     */
    private DepthSnapshot parseSnapshot(String symbol, String body) {
        JSONObject json = JSON.parseObject(body);

        long lastUpdateId = json.getLongValue("lastUpdateId");
        JSONArray bidsJson = json.getJSONArray("bids");
        JSONArray asksJson = json.getJSONArray("asks");

        List<long[]> bids = parseLevels(bidsJson);
        List<long[]> asks = parseLevels(asksJson);

        log.info("[SnapshotFetcher] Fetched snapshot for {}: lastUpdateId={}, bids={}, asks={}",
                symbol, lastUpdateId, bids.size(), asks.size());

        return new DepthSnapshot(symbol, lastUpdateId, bids, asks);
    }

    /**
     * 解析价格档位
     */
    private List<long[]> parseLevels(JSONArray array) {
        List<long[]> levels = new ArrayList<>();

        for (int i = 0; i < array.size(); i++) {
            JSONArray level = array.getJSONArray(i);
            String priceStr = level.getString(0);
            String qtyStr = level.getString(1);

            long price = parsePrice(priceStr);
            long qty = parsePrice(qtyStr);

            levels.add(new long[]{price, qty});
        }

        return levels;
    }

    private long parsePrice(String priceStr) {
        try {
            double price = Double.parseDouble(priceStr);
            return (long) (price * PRICE_MULTIPLIER);
        } catch (NumberFormatException e) {
            log.warn("[SnapshotFetcher] Invalid price: {}", priceStr);
            return 0;
        }
    }

    /**
     * 快照数据模型
     */
    public static class DepthSnapshot {
        public final String symbol;
        public final long lastUpdateId;
        public final List<long[]> bids;
        public final List<long[]> asks;

        public DepthSnapshot(String symbol, long lastUpdateId,
                           List<long[]> bids, List<long[]> asks) {
            this.symbol = symbol;
            this.lastUpdateId = lastUpdateId;
            this.bids = bids;
            this.asks = asks;
        }
    }
}
```

---

### 2.3 改造 BinanceMessageHandler

**修改要点**：
1. 注入 `OrderBookManager`（每个symbol一个实例）
2. 深度更新先经过序号检查
3. 检测到Gap触发快照重建
4. 仅发布ACTIVE状态的订单簿

```java
// BinanceMessageHandler.java

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceMessageHandler {

    private final BinanceDataPublisher publisher;
    private final BinanceSnapshotFetcher snapshotFetcher;
    private final BinanceDataSourceConfig config;

    // 每个symbol一个OrderBookManager
    private final ConcurrentHashMap<String, OrderBookManager> orderBookManagers =
            new ConcurrentHashMap<>();

    /**
     * 处理深度更新（改造版）
     */
    private void handleDepthUpdate(JSONObject message) {
        try {
            String symbol = message.getString("s");
            long firstUpdateId = message.getLongValue("U");
            long lastUpdateId = message.getLongValue("u");

            List<long[]> bids = parsePriceLevels(message.getJSONArray("b"));
            List<long[]> asks = parsePriceLevels(message.getJSONArray("a"));

            // 获取或创建OrderBookManager
            OrderBookManager manager = orderBookManagers.computeIfAbsent(
                    symbol,
                    k -> new OrderBookManager(symbol)
            );

            // 序号检查
            boolean success = manager.onDepthUpdate(firstUpdateId, lastUpdateId, bids, asks);

            if (!success) {
                // 需要重建
                log.warn("[BinanceHandler] OrderBook rebuild triggered for {}", symbol);
                rebuildOrderBook(manager);
                return;
            }

            // 仅在ACTIVE状态发布数据
            if (manager.getStatus() == OrderBookManager.OrderBookStatus.ACTIVE) {
                BinanceDepth depth = BinanceDepth.builder()
                        .symbol(symbol)
                        .eventTime(message.getLongValue("E"))
                        .firstUpdateId(firstUpdateId)
                        .lastUpdateId(lastUpdateId)
                        .bids(bids)
                        .asks(asks)
                        .build();

                publisher.publishDepth(depth);
            } else {
                log.debug("[BinanceHandler] Skip publishing depth for {} in {} state",
                        symbol, manager.getStatus());
            }

        } catch (Exception e) {
            log.error("[BinanceHandler] Failed to handle depth update", e);
        }
    }

    /**
     * 重建订单簿
     */
    private void rebuildOrderBook(OrderBookManager manager) {
        String symbol = manager.getSymbol();

        try {
            // 获取快照
            int limit = config.getDepthLevels();
            DepthSnapshot snapshot = snapshotFetcher.fetchSnapshot(symbol, limit);

            if (snapshot == null) {
                log.error("[BinanceHandler] Failed to fetch snapshot for {}, retry later", symbol);
                // TODO: 调度重试
                return;
            }

            // 初始化订单簿
            manager.initFromSnapshot(
                    snapshot.lastUpdateId,
                    snapshot.bids,
                    snapshot.asks
            );

            log.info("[BinanceHandler] OrderBook rebuilt for {}: lastUpdateId={}",
                    symbol, snapshot.lastUpdateId);

        } catch (Exception e) {
            log.error("[BinanceHandler] Error rebuilding orderbook for {}", symbol, e);
        }
    }

    /**
     * 获取订单簿快照（供REST API使用）
     */
    public OrderBookManager.Snapshot getOrderBookSnapshot(String symbol, int depth) {
        OrderBookManager manager = orderBookManagers.get(symbol);
        if (manager == null) {
            return null;
        }
        return manager.getSnapshot(depth);
    }

    // ... 其他方法保持不变
}
```

---

### 2.4 改造 BinanceDataController

增加订单簿状态查询接口：

```java
// BinanceDataController.java

@GetMapping("/orderbook/status")
public Map<String, Object> getOrderBookStatus() {
    Map<String, Object> result = new HashMap<>();

    Map<String, OrderBookManager.OrderBookMetrics> metrics = new HashMap<>();
    for (String symbol : config.getSymbols()) {
        OrderBookManager.Snapshot snapshot = messageHandler.getOrderBookSnapshot(symbol, 5);
        if (snapshot != null) {
            // 构建指标
            metrics.put(symbol, buildMetrics(snapshot));
        }
    }

    result.put("orderBooks", metrics);
    result.put("timestamp", System.currentTimeMillis());
    return result;
}

@GetMapping("/orderbook/{symbol}")
public Map<String, Object> getOrderBook(
        @PathVariable String symbol,
        @RequestParam(defaultValue = "20") int depth) {

    Map<String, Object> result = new HashMap<>();

    OrderBookManager.Snapshot snapshot = messageHandler.getOrderBookSnapshot(
            symbol.toUpperCase(),
            depth
    );

    if (snapshot == null) {
        result.put("success", false);
        result.put("message", "OrderBook not available for " + symbol);
        return result;
    }

    result.put("success", true);
    result.put("data", snapshot);
    result.put("timestamp", System.currentTimeMillis());
    return result;
}
```

---

## 三、监控指标实现

### 3.1 Prometheus Metrics

创建 `BinanceMetricsCollector.java`:

```java
package com.exchange.binance.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class BinanceMetricsCollector {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, SymbolMetrics> metricsMap = new ConcurrentHashMap<>();

    public BinanceMetricsCollector(MeterRegistry registry) {
        this.registry = registry;
    }

    public SymbolMetrics getMetrics(String symbol) {
        return metricsMap.computeIfAbsent(symbol, s -> new SymbolMetrics(registry, s));
    }

    public static class SymbolMetrics {
        private final Counter messagesReceived;
        private final Counter sequenceGaps;
        private final Counter rebuilds;
        private final Counter priceAnomalies;
        private final Timer processingLatency;

        public SymbolMetrics(MeterRegistry registry, String symbol) {
            this.messagesReceived = Counter.builder("binance.messages.received")
                    .tag("symbol", symbol)
                    .register(registry);

            this.sequenceGaps = Counter.builder("binance.sequence.gaps")
                    .tag("symbol", symbol)
                    .register(registry);

            this.rebuilds = Counter.builder("binance.orderbook.rebuilds")
                    .tag("symbol", symbol)
                    .register(registry);

            this.priceAnomalies = Counter.builder("binance.price.anomalies")
                    .tag("symbol", symbol)
                    .register(registry);

            this.processingLatency = Timer.builder("binance.processing.latency")
                    .tag("symbol", symbol)
                    .register(registry);
        }

        public void recordMessage() {
            messagesReceived.increment();
        }

        public void recordGap() {
            sequenceGaps.increment();
        }

        public void recordRebuild() {
            rebuilds.increment();
        }

        public void recordAnomaly() {
            priceAnomalies.increment();
        }

        public void recordLatency(long nanos) {
            processingLatency.record(nanos, TimeUnit.NANOSECONDS);
        }
    }
}
```

在 `BinanceMessageHandler` 中使用：

```java
@Autowired
private BinanceMetricsCollector metricsCollector;

private void handleDepthUpdate(JSONObject message) {
    long startTime = System.nanoTime();
    String symbol = message.getString("s");

    try {
        // ... 处理逻辑

        metricsCollector.getMetrics(symbol).recordMessage();

        if (!success) {
            metricsCollector.getMetrics(symbol).recordGap();
            metricsCollector.getMetrics(symbol).recordRebuild();
        }

    } finally {
        long latency = System.nanoTime() - startTime;
        metricsCollector.getMetrics(symbol).recordLatency(latency);
    }
}
```

---

## 四、测试计划

### 4.1 单元测试

```java
@Test
public void testSequenceGapDetection() {
    OrderBookManager manager = new OrderBookManager("BTCUSDT");

    // 初始化
    manager.initFromSnapshot(100, bids, asks);

    // 正常更新
    boolean success = manager.onDepthUpdate(101, 105, newBids, newAsks);
    assertTrue(success);
    assertEquals(105, manager.getLastUpdateId());

    // Gap更新
    success = manager.onDepthUpdate(110, 115, newBids, newAsks);
    assertFalse(success);  // 应该触发重建
    assertEquals(OrderBookStatus.STALE, manager.getStatus());
}
```

### 4.2 集成测试

```java
@SpringBootTest
public class BinanceIntegrationTest {

    @Autowired
    private BinanceWebSocketClient wsClient;

    @Autowired
    private BinanceMessageHandler handler;

    @Test
    public void testOrderBookRebuildOnReconnect() throws Exception {
        // 1. 正常连接
        wsClient.connect();
        Thread.sleep(5000);

        // 2. 模拟断开
        wsClient.disconnect();
        Thread.sleep(2000);

        // 3. 重连
        wsClient.connect();
        Thread.sleep(5000);

        // 4. 检查订单簿是否重建
        OrderBookManager.Snapshot snapshot = handler.getOrderBookSnapshot("BTCUSDT", 5);
        assertNotNull(snapshot);
        assertEquals(OrderBookStatus.ACTIVE, snapshot.getStatus());
    }
}
```

---

## 五、部署检查清单

### 5.1 上线前检查

- [ ] 单元测试覆盖率 > 80%
- [ ] 集成测试通过
- [ ] 压测：1000条/秒深度更新，无内存泄漏
- [ ] 监控指标正常暴露（/actuator/prometheus）
- [ ] 日志级别配置正确（生产环境INFO）
- [ ] 告警规则配置完成
- [ ] 降级开关准备好

### 5.2 上线后监控

**第1小时**：
- 检查连接状态
- 检查序号Gap计数（应该=0）
- 检查P99延迟（< 200ms）

**第1天**：
- 检查重建次数（< 5次）
- 检查内存使用（< 1GB）
- 检查Kafka消费lag（< 1000）

**第1周**：
- 对比内部行情与币安行情偏离度
- 检查用户反馈
- 调优参数

---

## 六、FAQ

### Q1: 为什么不使用币安提供的Java SDK？
**A**: 币安Java SDK偏重，我们只需要公开行情数据，自己实现WebSocket更轻量可控。

### Q2: 订单簿重建会不会影响实时性？
**A**: 重建期间会暂停发布深度数据（1-3秒），但成交数据不受影响。

### Q3: 如果币安REST API也不可用怎么办？
**A**: 启动降级开关，停止发布币安数据，切换到仅内部行情。

### Q4: 序号Gap的容忍度是多少？
**A**: 0容忍。只要检测到Gap立即重建，确保数据准确性。

---

*文档结束*
