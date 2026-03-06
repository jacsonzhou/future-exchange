# 目标架构差距分析与实施路线图

> 基于主流标准架构，评估当前实现差距并制定改进计划
>
> 创建时间：2026-03-06

---

## 📋 差距矩阵

| 序号 | 问题域 | 当前状态 | 目标状态 | 优先级 | 影响等级 | 技术债 |
|-----|-------|---------|---------|--------|---------|--------|
| 1 | **行情推送双真相** | ext/standard混用，K线和盘口时基不一致 | 统一走standard流，时基一致 | P0 | 🔴 High | 客户端数据混乱、对账困难 |
| 2 | **Dealer盘口依赖** | Redis pull，每次成交访问Redis | 内存盘口，event-driven更新 | P0 | 🔴 High | 延迟5-10ms、stale风险 |
| 3 | **K线重启补齐** | 部分实现，无强制watermark | 启动必经流程：补齐→watermark→放流 | P1 | 🟡 Medium | 重启后gap，用户投诉 |
| 4 | **收线幂等约束** | 日志人工检查 | 数据库唯一约束+冲突告警队列 | P1 | 🟡 Medium | 可能重复K线 |
| 5 | **强平兜底通道** | 待确认是否只有实时通道 | 实时+500ms定时双触发 | P0 | 🔴 High | 消息丢失时穿仓风险 |

---

## 🎯 目标架构详细设计

### 1. 行情数据流分层架构

```
┌─────────────────────────────────────────────────────────────────┐
│ Layer 1: External Data Source (外部接入层)                       │
│                                                                 │
│  binance-data-source                                            │
│  ├── WebSocket订阅 (depth/trade/ticker)                         │
│  ├── 序号校验 (prevEventId + 1 = currentEventId)                │
│  ├── Gap检测 (超过阈值触发REST重建)                              │
│  └── 新鲜度检查 (eventTime < now + 3s)                           │
│                                                                 │
│  输出 Topic:                                                     │
│  - market.ext.binance.depth.{symbol}    (仅供调试/回放)         │
│  - market.ext.binance.trade.{symbol}                            │
│  - market.ext.binance.ticker.{symbol}                           │
│  - market.ext.binance.kline.{symbol}.{interval}                 │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ Layer 2: Standardization Layer (标准化层)                        │
│                                                                 │
│  market-price-core                                              │
│  ├── 时基统一 (统一时间戳标准)                                   │
│  ├── 格式转换 (source特定格式 -> 内部标准格式)                   │
│  ├── 持久化 (K线写MySQL: t_kline_1m, t_kline_realtime)          │
│  ├── 聚合计算 (1m -> 5m/15m/1h/1d)                              │
│  ├── 收线判定 (is_closed=true写入历史表)                        │
│  └── 幂等保证 (唯一键: source+symbol+interval+open_time)        │
│                                                                 │
│  输出 Topic (标准行情总线):                                      │
│  - market.depth.{symbol}         (标准盘口)                     │
│  - market.trade.{symbol}         (标准成交)                     │
│  - market.ticker.{symbol}        (标准Ticker)                   │
│  - market.kline.{symbol}.{interval} (标准K线)                   │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ Layer 3: Push Layer (推送层)                                     │
│                                                                 │
│  public-push-core                                               │
│  ├── 订阅管理 (WebSocket连接池)                                 │
│  ├── 首包快照 (从Redis读取最新快照)                              │
│  ├── 增量推送 (消费market.*，推送增量)                           │
│  └── 限流保护 (per-connection rate limit)                       │
│                                                                 │
│  输出:                                                           │
│  - WebSocket消息 (JSON格式)                                      │
└─────────────────────────────────────────────────────────────────┘
```

#### 关键规则

| 规则 | 说明 | 强制性 |
|-----|------|--------|
| **客户端只订阅标准流** | 前端/App只能连接public-push，消费market.*标准Topic | ✅ 强制 |
| **ext流仅供内部** | market.ext.*仅用于调试、回放、数据对账 | ✅ 强制 |
| **时基统一** | K线/盘口/成交必须来自同一标准化层输出 | ✅ 强制 |
| **Redis作为快照层** | 首包快照走Redis，增量走Kafka，避免全量轮询 | ⚠️ 建议 |

---

### 2. K线数据模型与持久化

#### 表结构设计

```sql
-- 历史K线表 (1m基准周期，收线后不可变)
CREATE TABLE t_kline_1m (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source VARCHAR(20) NOT NULL COMMENT '数据源: binance/okx',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对: BTCUSDT',
    open_time BIGINT NOT NULL COMMENT 'K线开盘时间 (毫秒时间戳)',
    close_time BIGINT NOT NULL COMMENT 'K线收盘时间',

    -- OHLCV (使用BIGINT存储，8位小数精度)
    open_price BIGINT NOT NULL COMMENT '开盘价',
    high_price BIGINT NOT NULL COMMENT '最高价',
    low_price BIGINT NOT NULL COMMENT '最低价',
    close_price BIGINT NOT NULL COMMENT '收盘价',
    volume BIGINT NOT NULL COMMENT '成交量',
    quote_volume BIGINT NOT NULL COMMENT '成交额',
    trade_count INT NOT NULL COMMENT '成交笔数',

    -- 收线标记 (核心字段)
    is_closed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否收线 (收线后不可变)',

    -- 时间戳
    event_time BIGINT NOT NULL COMMENT '事件时间 (Binance事件时间)',
    ingest_time BIGINT NOT NULL COMMENT '写入时间 (系统时间)',

    -- 幂等键 (唯一约束)
    UNIQUE KEY uk_kline (source, symbol, open_time),

    -- 查询索引
    KEY idx_symbol_time (symbol, open_time),
    KEY idx_closed (is_closed, symbol, open_time)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='1分钟K线历史表 (收线后不可变)';

-- 实时K线表 (当前未收线bar，收线后移入历史表)
CREATE TABLE t_kline_realtime (
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    source VARCHAR(20) NOT NULL DEFAULT 'binance',
    open_time BIGINT NOT NULL COMMENT 'K线开盘时间',

    open_price BIGINT NOT NULL,
    high_price BIGINT NOT NULL,
    low_price BIGINT NOT NULL,
    close_price BIGINT NOT NULL,
    volume BIGINT NOT NULL,
    quote_volume BIGINT NOT NULL,
    trade_count INT NOT NULL,

    last_update_time BIGINT NOT NULL COMMENT '最后更新时间',

    PRIMARY KEY (symbol, source, open_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时未收线K线 (内存表)';

-- 数据约束检查 (保证K线数据合法性)
ALTER TABLE t_kline_1m ADD CONSTRAINT chk_high CHECK (high_price >= open_price AND high_price >= close_price);
ALTER TABLE t_kline_1m ADD CONSTRAINT chk_low CHECK (low_price <= open_price AND low_price <= close_price);
ALTER TABLE t_kline_1m ADD CONSTRAINT chk_volume CHECK (volume >= 0);
```

#### K线处理流程

```java
@Service
@Slf4j
public class KlineService {

    @Autowired
    private KlineMapper klineMapper;

    @Transactional
    public void processKlineEvent(KlineEvent event) {
        String symbol = event.getSymbol();
        long openTime = event.getOpenTime();
        boolean isClosed = event.isClosed();

        // 1. 幂等键检查
        String idempotentKey = String.format("%s:%s:%d", event.getSource(), symbol, openTime);

        if (isClosed) {
            // 收线逻辑
            Kline existing = klineMapper.selectByKey(event.getSource(), symbol, openTime);

            if (existing != null && existing.isClosed()) {
                // 已存在收线记录，检查是否冲突
                if (!isKlineEqual(existing, event)) {
                    log.error("[KlineConflict] Closed kline conflict detected: key={}, existing={}, new={}",
                        idempotentKey, existing, event);
                    // 进入对账告警队列
                    reconciliationAlertQueue.add(new KlineConflictAlert(existing, event));
                }
                return; // 收线后不可变，直接返回
            }

            // 首次收线，写入历史表
            Kline kline = buildKline(event);
            kline.setClosed(true);

            // 数据约束校验
            validateKline(kline);

            // 幂等插入 (ON DUPLICATE KEY UPDATE 仅在未收线时更新)
            klineMapper.upsert(kline);

            // 从实时表删除
            klineMapper.deleteRealtime(symbol, event.getSource(), openTime);

            log.info("[KlineClosed] symbol={}, openTime={}, ohlcv=[{},{},{},{},{}]",
                symbol, openTime, kline.getOpen(), kline.getHigh(),
                kline.getLow(), kline.getClose(), kline.getVolume());

        } else {
            // 未收线，更新实时表
            klineMapper.upsertRealtime(buildRealtimeKline(event));
        }
    }

    private void validateKline(Kline kline) {
        if (kline.getHigh() < kline.getOpen() || kline.getHigh() < kline.getClose()) {
            throw new IllegalArgumentException("High price must >= open and close");
        }
        if (kline.getLow() > kline.getOpen() || kline.getLow() > kline.getClose()) {
            throw new IllegalArgumentException("Low price must <= open and close");
        }
        if (kline.getVolume() < 0) {
            throw new IllegalArgumentException("Volume must >= 0");
        }
    }

    private boolean isKlineEqual(Kline a, KlineEvent b) {
        return a.getOpen() == b.getOpen() &&
               a.getHigh() == b.getHigh() &&
               a.getLow() == b.getLow() &&
               a.getClose() == b.getClose() &&
               a.getVolume() == b.getVolume();
    }
}
```

---

### 3. 服务重启K线补齐流程

#### Checkpoint机制

```sql
-- Checkpoint表 (记录每个symbol的处理进度)
CREATE TABLE t_kline_checkpoint (
    symbol VARCHAR(20) NOT NULL,
    source VARCHAR(20) NOT NULL DEFAULT 'binance',
    interval VARCHAR(10) NOT NULL DEFAULT '1m',

    last_closed_open_time BIGINT NOT NULL COMMENT '最后收线的openTime',
    last_event_time BIGINT NOT NULL COMMENT '最后事件时间',
    last_kafka_offset BIGINT NOT NULL COMMENT '最后消费的Kafka offset',

    watermark BIGINT NOT NULL COMMENT '水位线 (小于此时间的事件会被丢弃)',

    updated_at BIGINT NOT NULL,

    PRIMARY KEY (symbol, source, interval)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### 启动补齐流程

```java
@Component
@Slf4j
public class KlineBackfillService {

    @Autowired
    private KlineCheckpointMapper checkpointMapper;

    @Autowired
    private BinanceRestClient binanceClient;

    @Autowired
    private KlineMapper klineMapper;

    @PostConstruct
    public void init() {
        // 启动时对每个symbol执行补齐
        List<String> symbols = List.of("BTCUSDT", "ETHUSDT", "BNBUSDT");
        for (String symbol : symbols) {
            backfillOnStartup(symbol, "1m");
        }
    }

    /**
     * 启动时强制补齐流程
     *
     * 步骤:
     * 1. 读取checkpoint
     * 2. 暂停该symbol的实时消费
     * 3. REST回补 [last_closed_open_time + 1m, now - 1m]
     * 4. 设置watermark
     * 5. 恢复实时消费
     */
    @Transactional
    public void backfillOnStartup(String symbol, String interval) {
        log.info("[BackfillStart] symbol={}, interval={}", symbol, interval);

        // 1. 读取checkpoint
        KlineCheckpoint checkpoint = checkpointMapper.selectBySymbol(symbol, "binance", interval);
        if (checkpoint == null) {
            checkpoint = initCheckpoint(symbol, interval);
        }

        long lastClosedOpenTime = checkpoint.getLastClosedOpenTime();
        long now = System.currentTimeMillis();
        long intervalMs = parseIntervalMs(interval); // 1m -> 60000ms

        // 2. 暂停实时消费 (通过Kafka consumer pause API)
        kafkaConsumerManager.pauseSymbol(symbol);

        try {
            // 3. REST回补
            long startTime = lastClosedOpenTime + intervalMs;
            long endTime = now - intervalMs; // 只回补已收线的

            if (startTime >= endTime) {
                log.info("[BackfillSkip] No gap to backfill: symbol={}", symbol);
                return;
            }

            log.info("[BackfillREST] symbol={}, startTime={}, endTime={}, duration={}min",
                symbol, startTime, endTime, (endTime - startTime) / 60000);

            List<Kline> klines = binanceClient.getKlines(symbol, interval, startTime, endTime);

            int upsertCount = 0;
            for (Kline kline : klines) {
                kline.setClosed(true); // REST返回的都是收线数据

                // 幂等Upsert
                int affected = klineMapper.upsert(kline);
                if (affected > 0) {
                    upsertCount++;
                }
            }

            log.info("[BackfillDone] symbol={}, totalKlines={}, upserted={}",
                symbol, klines.size(), upsertCount);

            // 4. 更新checkpoint + watermark
            long newWatermark = endTime;
            checkpointMapper.updateWatermark(symbol, "binance", interval,
                endTime, newWatermark, now);

        } finally {
            // 5. 恢复实时消费
            kafkaConsumerManager.resumeSymbol(symbol);
            log.info("[BackfillResume] symbol={} resumed", symbol);
        }
    }

    /**
     * 运行时增量补齐 (检测到gap时触发)
     */
    public void backfillIncremental(String symbol, long gapStart, long gapEnd) {
        log.warn("[IncrementalBackfill] symbol={}, gap=[{}, {}]", symbol, gapStart, gapEnd);

        List<Kline> klines = binanceClient.getKlines(symbol, "1m", gapStart, gapEnd);
        for (Kline kline : klines) {
            klineMapper.upsert(kline);
        }

        log.info("[IncrementalBackfillDone] symbol={}, filled={} klines", symbol, klines.size());
    }
}
```

#### Watermark过滤逻辑

```java
@Service
public class KlineConsumer {

    @KafkaListener(topics = "market.ext.binance.kline.#{symbol}.1m")
    public void onKlineEvent(KlineEvent event) {
        String symbol = event.getSymbol();
        long openTime = event.getOpenTime();

        // 读取watermark
        KlineCheckpoint checkpoint = checkpointMapper.selectBySymbol(symbol, "binance", "1m");
        long watermark = checkpoint.getWatermark();

        // 过滤旧事件
        if (openTime <= watermark) {
            log.debug("[KlineFilter] Dropped old event: symbol={}, openTime={}, watermark={}",
                symbol, openTime, watermark);
            return;
        }

        // 正常处理
        klineService.processKlineEvent(event);

        // 更新checkpoint
        if (event.isClosed()) {
            checkpointMapper.updateLastClosed(symbol, "binance", "1m", openTime,
                event.getEventTime(), getCurrentKafkaOffset());
        }
    }
}
```

---

### 4. CFD Dealer 内存盘口架构

#### 当前问题

```java
// ❌ 当前做法: 每次成交都访问Redis
@Service
public class CfdDealerService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    public TradeResult executeMarketOrder(Order order) {
        // 从Redis读取盘口快照 (延迟5-10ms)
        String depthJson = redisTemplate.opsForValue().get("depth:" + order.getSymbol());
        OrderBook orderBook = JSON.parseObject(depthJson, OrderBook.class);

        // 可能是stale数据 (Redis更新频率100ms一次)
        return fillOrder(order, orderBook);
    }
}
```

**问题：**
1. 每次成交都有网络RTT (5-10ms)
2. Redis快照更新频率有限 (通常100ms)，可能stale
3. 高并发时Redis成为瓶颈

#### 目标架构

```java
@Service
@Slf4j
public class CfdDealerService {

    // 内存盘口 (event-driven更新)
    private final ConcurrentHashMap<String, OrderBook> orderBooks = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 启动时从Redis恢复快照
        List<String> symbols = List.of("BTCUSDT", "ETHUSDT");
        for (String symbol : symbols) {
            OrderBook snapshot = loadOrderBookFromRedis(symbol);
            orderBooks.put(symbol, snapshot);
        }

        // 订阅Kafka增量更新
        startDepthConsumer();
    }

    /**
     * 消费盘口增量 (market.ext.binance.depth.{symbol})
     */
    @KafkaListener(topics = "market.ext.binance.depth.#{symbol}", groupId = "cfd-dealer")
    public void onDepthUpdate(DepthUpdateEvent event) {
        String symbol = event.getSymbol();
        OrderBook orderBook = orderBooks.get(symbol);

        if (orderBook == null) {
            log.warn("[OrderBook] Not initialized: {}", symbol);
            return;
        }

        // 更新内存盘口 (< 1μs)
        orderBook.applyUpdate(event);

        // 异步写Redis快照 (容灾用，不阻塞主流程)
        asyncSaveToRedis(symbol, orderBook);
    }

    /**
     * 市价单成交 (从内存盘口读取，< 1μs)
     */
    public TradeResult executeMarketOrder(Order order) {
        String symbol = order.getSymbol();
        OrderBook orderBook = orderBooks.get(symbol);

        if (orderBook == null) {
            throw new IllegalStateException("OrderBook not ready: " + symbol);
        }

        // VWAP算法逐档吃单
        TradeResult result = fillOrderByVWAP(order, orderBook);

        log.info("[CfdTrade] orderId={}, symbol={}, qty={}, avgPrice={}, latency={}μs",
            order.getOrderId(), symbol, order.getQuantity(), result.getAvgPrice(),
            result.getLatency());

        return result;
    }

    /**
     * VWAP逐档吃单算法
     */
    private TradeResult fillOrderByVWAP(Order order, OrderBook orderBook) {
        long startNano = System.nanoTime();

        Side side = order.getSide();
        long remainingQty = order.getQuantity();
        long totalCost = 0;
        List<Fill> fills = new ArrayList<>();

        // 买单吃asks，卖单吃bids
        List<PriceLevel> levels = (side == Side.BUY) ? orderBook.getAsks() : orderBook.getBids();

        for (PriceLevel level : levels) {
            if (remainingQty == 0) break;

            long fillQty = Math.min(remainingQty, level.getQuantity());
            long fillCost = Money.multiply(fillQty, level.getPrice());

            fills.add(new Fill(level.getPrice(), fillQty));
            totalCost += fillCost;
            remainingQty -= fillQty;
        }

        if (remainingQty > 0) {
            // 盘口深度不足，部分成交或拒单
            log.warn("[InsufficientDepth] orderId={}, symbol={}, remainingQty={}",
                order.getOrderId(), order.getSymbol(), remainingQty);
        }

        long filledQty = order.getQuantity() - remainingQty;
        long avgPrice = (filledQty > 0) ? totalCost / filledQty : 0;

        long latencyNano = System.nanoTime() - startNano;

        return TradeResult.builder()
            .orderId(order.getOrderId())
            .filledQuantity(filledQty)
            .avgPrice(avgPrice)
            .fills(fills)
            .latency(latencyNano / 1000) // μs
            .build();
    }

    /**
     * 异步保存到Redis (容灾快照)
     */
    private void asyncSaveToRedis(String symbol, OrderBook orderBook) {
        CompletableFuture.runAsync(() -> {
            try {
                String json = JSON.toJSONString(orderBook);
                redisTemplate.opsForValue().set("depth:" + symbol, json,
                    Duration.ofSeconds(10));
            } catch (Exception e) {
                log.error("[Redis] Failed to save orderbook: {}", symbol, e);
            }
        });
    }
}
```

**关键改进：**
1. ✅ 内存盘口延迟 < 1μs (vs Redis 5-10ms)
2. ✅ 事件驱动更新，实时性更好
3. ✅ Redis仅做容灾快照，不阻塞成交
4. ✅ 启动时从Redis恢复 + Kafka增量补齐

---

### 5. 强平双通道触发架构

```java
@Service
@Slf4j
public class LiquidationTriggerService {

    /**
     * 通道1: 实时事件驱动 (mark-price-update触发)
     */
    @KafkaListener(topics = "mark-price-update", groupId = "liquidation-trigger")
    public void onMarkPriceUpdate(MarkPriceEvent event) {
        String symbol = event.getSymbol();
        long markPrice = event.getMarkPrice();

        // 查询该symbol下所有持仓
        List<Position> positions = positionSnapshotService.getPositions(symbol);

        for (Position position : positions) {
            // 计算保证金率
            BigDecimal marginRatio = calculateMarginRatio(position, markPrice);
            BigDecimal maintMarginRate = getMaintenanceMarginRate(symbol);

            if (marginRatio.compareTo(maintMarginRate) < 0) {
                // 触发强平
                log.warn("[LiquidationTriggered] userId={}, symbol={}, marginRatio={}, maintRate={}",
                    position.getUserId(), symbol, marginRatio, maintMarginRate);

                sendLiquidationCommand(position, markPrice, "REALTIME_TRIGGER");
            }
        }
    }

    /**
     * 通道2: 定时兜底扫描 (500ms一次)
     */
    @Scheduled(fixedDelay = 500)
    public void scanHighRiskPositions() {
        List<Position> highRiskPositions = positionSnapshotService.getHighRiskPositions(0.1); // 保证金率<10%

        for (Position position : highRiskPositions) {
            String symbol = position.getSymbol();
            long markPrice = markPriceService.getCurrentMarkPrice(symbol);

            BigDecimal marginRatio = calculateMarginRatio(position, markPrice);
            BigDecimal maintMarginRate = getMaintenanceMarginRate(symbol);

            if (marginRatio.compareTo(maintMarginRate) < 0) {
                log.warn("[LiquidationFallback] userId={}, symbol={}, marginRatio={}",
                    position.getUserId(), symbol, marginRatio);

                sendLiquidationCommand(position, markPrice, "FALLBACK_SCAN");
            }
        }
    }

    private void sendLiquidationCommand(Position position, long markPrice, String source) {
        LiquidationCommand command = LiquidationCommand.builder()
            .userId(position.getUserId())
            .symbol(position.getSymbol())
            .positionSide(position.getSide())
            .quantity(position.getQuantity())
            .markPrice(markPrice)
            .triggerSource(source)
            .timestamp(System.currentTimeMillis())
            .build();

        kafkaTemplate.send("liquidation-trigger-topic", command);
    }
}
```

**双通道必要性：**
- 实时通道：正常情况下主力，低延迟
- 兜底通道：防止消息丢失、消费者故障、网络抖动

---

## 🚀 实施路线图

### Sprint 1: 核心风险修复 (2周，P0优先级)

| 任务 | 负责模块 | 验收标准 |
|-----|---------|---------|
| 统一行情推送为标准流 | market-price-core + public-push-core | K线/盘口时基一致，无ext/standard混用 |
| Dealer改为内存盘口 | cfd-dealer-core | 成交延迟<1ms，Redis仅做快照 |
| 强平加兜底定时扫描 | margin-mode-core + liquidation-core | 双通道触发，500ms扫描 |

### Sprint 2: 数据一致性增强 (2周，P1优先级)

| 任务 | 负责模块 | 验收标准 |
|-----|---------|---------|
| K线重启补齐流程 | market-price-core | 启动必经：checkpoint→补齐→watermark→放流 |
| 收线幂等硬约束 | market-price-core | 数据库唯一键+冲突告警队列 |
| 对账告警系统 | monitoring-service | 冲突自动进入人工审核队列 |

### Sprint 3: 监控与优化 (1周)

| 任务 | 负责模块 | 验收标准 |
|-----|---------|---------|
| Watermark机制完善 | market-price-core | 乱序事件正确过滤 |
| 全链路监控 | prometheus + grafana | 关键指标覆盖率100% |
| 压测验证 | - | 10万并发，无数据错乱 |

---

## 📊 关键指标

| 指标 | 当前 | 目标 | 监控方式 |
|-----|------|------|---------|
| Dealer成交延迟 | 5-10ms | <1ms | Prometheus histogram |
| K线数据完整性 | 人工检查 | 自动对账+告警 | Checkpoint gap检测 |
| 强平触发延迟 | 未知 | <100ms (实时) + 500ms (兜底) | 事件时间戳对比 |
| 行情时基一致性 | 混乱 | 100% | 盘口/K线时间戳差<1s |

---

## 🔧 配置示例

### Kafka Topics配置

```yaml
# application.yml (market-price-core)
kafka:
  topics:
    # 外部原始流 (仅供调试/回放)
    ext-depth: market.ext.binance.depth.{symbol}
    ext-trade: market.ext.binance.trade.{symbol}
    ext-kline: market.ext.binance.kline.{symbol}.{interval}

    # 标准行情流 (客户端订阅)
    standard-depth: market.depth.{symbol}
    standard-trade: market.trade.{symbol}
    standard-kline: market.kline.{symbol}.{interval}

  consumer:
    group-id: market-price-consumer
    enable-auto-commit: false  # 手动commit，保证exactly-once
    max-poll-records: 100
```

---

## ✅ 验收清单

### 行情数据流

- [ ] 客户端只能订阅 `market.*` 标准流
- [ ] K线/盘口时基一致 (时间戳差<1s)
- [ ] `market.ext.*` 仅用于内部调试
- [ ] Redis快照与Kafka增量同步正确

### K线数据质量

- [ ] 收线后不可变 (数据库约束)
- [ ] 幂等键生效 (重复事件不写入)
- [ ] 冲突进入告警队列
- [ ] 数据约束检查 (high>=max(open,close), low<=min(open,close))

### 服务重启

- [ ] 启动时自动补齐gap
- [ ] Watermark正确过滤旧事件
- [ ] Checkpoint正确更新

### CFD Dealer

- [ ] 内存盘口正确更新
- [ ] 成交延迟<1ms
- [ ] Redis快照异步写入不阻塞

### 强平系统

- [ ] 实时通道正常工作
- [ ] 兜底扫描每500ms执行
- [ ] 双通道都能触发强平

---

*最后更新：2026-03-06*
