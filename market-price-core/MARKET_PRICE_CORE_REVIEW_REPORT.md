# Market-Price-Core 深度审查报告

> 审查日期：2026-04-19
> 审查范围：K线/深度/Ticker处理、Kafka消费与重启行为、数据重复、下游写入
> 审查结论：**存在3项严重缺陷、5项中等风险、若干轻微问题，建议修复后再上线**

---

## 一、严重缺陷（❌ 必须修复）

### 1.1 ❌ 撮合事件驱动的K线未持久化到ClickHouse（数据丢失）

**问题描述：**
```
MatchEventConsumer.onTradeEvent() 
  → engineService.onTrade() 
    → SymbolEngines.getKlineEngine()  // 使用的是 KlineEngine（纯内存）
```

`MarketDataEngineService.SymbolEngines` 创建的是 `KlineEngine`，而 `KlineEngineWithStorage` 是一个独立的 `@Component`，两者互不关联。

`MatchEventConsumer` 消费撮合成交后，K线仅保存在内存中（`KlineEngine`），**从未写入 ClickHouse**。重启后这些K线完全丢失，API查询会 fallback 到缓存或空数据。

**正确做法：**
`MarketDataEngineService` 应该注入 `KlineEngineWithStorage`，将撮合成交同步路由到带存储的K线引擎：
```java
// MarketDataEngineService.onTrade() 应改为：
klineEngineWithStorage.onTrade(symbol, trade.getPrice(), trade.getQuantity(), 
                                trade.getTimestamp(), trade.isBuyerMaker());
```

或者，移除 `KlineEngine`，统一使用 `KlineEngineWithStorage`。

**影响：** 服务重启后，所有从撮合引擎产生的K线历史完全丢失；外部行情K线与撮合K线存储在不同引擎，数据不一致。

---

### 1.2 ❌ TickerEngine 虽然存在但从未被使用，24h统计实现错误

**问题描述：**
`MarketDataEngineService.SymbolEngines` 构造函数：
```java
SymbolEngines(String symbol, MarketDataPublisher publisher) {
    this.orderBook = new OrderBook(symbol, ...);
    this.tradeEngine = new TradeEngine(symbol, publisher);
    this.klineEngine = new KlineEngine(symbol, publisher);
    // ❌ 没有创建 TickerEngine！
}
```

`MarketDataEngineService.onTrade()` 没有调用 `TickerEngine.onTrade()`。`publishAllTickers()` 使用的是 `TradeEngine.getTradeStats24h()`。

而 `TradeEngine` 的24h统计实现存在根本缺陷：
- `totalVolume` / `totalQuoteVolume` / `tradeCount` 是**自上次 reset 以来的累计值**，不是24小时滑动窗口
- `recentTrades` 队列满时会丢弃旧数据，但累计值不会减少
- 没有定时调用 `resetDailyStats()`，导致 volume 无限增长

**影响：** `/api/v1/ticker/24hr` 返回的24h成交量是"自服务启动以来的累计值"，而非"过去24小时"，严重误导用户和风控系统。

---

### 1.3 ❌ K线/成交/Ticker 均无Kafka消息去重机制（重复消费导致数据错误）

**问题描述：**
- `OrderBook.applyDelta()` 有 `updateId` 去重 ✅
- `KlineEngine.onTrade()` / `KlineEngineWithStorage.onTrade()` **无任何去重**
- `TradeEngine.onTrade()` **无任何去重**
- `TickerEngine`（即使被使用）**无任何去重**

Kafka 在以下场景会重复投递：
1. 消费者处理超时未ack，触发rebalance后重投
2. 消费者重启，offset提交与处理非原子性
3. Kafka broker故障切换

同一条 `trade-event` 被消费两次时：
- K线的 `volume`, `tradeCount`, `quoteVolume` 会**双倍累加**
- `TradeEngine` 的 `totalVolume` 会**双倍累加**
- 如果 `TickerEngine` 被使用，24h统计也会**双倍累加**

**正确做法：**
在 `Trade` 模型中增加 `sequence` 字段（已有），在 `KlineEngine` 和 `TradeEngine` 中按 `(symbol, sequence)` 去重：
```java
// KlineGenerator / TradeEngine 中增加：
private final LongAdder lastProcessedSequence = new LongAdder();

void onTrade(long price, long quantity, long timestamp, boolean isBuyerMaker, long sequence) {
    if (sequence <= lastProcessedSequence.sum()) {
        return; // 重复消息，忽略
    }
    lastProcessedSequence.reset();
    lastProcessedSequence.add(sequence);
    // ... 正常处理
}
```

---

## 二、中等风险（⚠️ 建议修复）

### 2.1 ⚠️ 重启后深度订单簿可能从增量开始重建（状态错误）

**问题描述：**
`KafkaConfig` 中 `AUTO_OFFSET_RESET_CONFIG = "latest"`，服务重启后：
1. `MatchEventConsumer` 跳过积压的深度消息，从最新增量开始消费
2. `OrderBook.applyDelta()` 中 `lastId == 0` 时直接接受第一个增量
3. 此时订单簿为空，第一个增量被应用到空状态，**订单簿数据完全错误**

**缓解措施：**
- `processDepthEvent()` 将 `firstUpdateId == 1 && lastUpdateId == 1` 视为快照
- 但这依赖于 Match Engine 是否会在新消费者加入时发送快照

**建议：**
- 深度消费应使用 `auto-offset-reset: earliest`，或在应用增量前主动请求快照
- 增加订单簿健康检查：如果 `applyDelta` 连续返回 false 超过阈值，触发主动重建

---

### 2.2 ⚠️ 深度发布的是完整快照而非增量（下游语义混乱）

**问题描述：**
`MarketDataEngineService.onDepthDelta()`：
```java
OrderBook.DepthSnapshot snapshot = orderBook.getSnapshot(20);
// 发布的是完整20档快照，不是增量
```

但 `MarketDataPublisher.buildDepthMessage()` 构建的消息：
```java
msg.put("e", "depthUpdate");
msg.put("U", depthUpdate.getFirstUpdateId());
msg.put("u", depthUpdate.getLastUpdateId());
msg.put("pu", depthUpdate.getLastUpdateId() - 1);  // 语义错误
```

下游消费者（如 public-push-core）如果按 "增量更新" 语义处理，会把这个完整快照当成增量应用，导致深度数据翻倍或混乱。

**建议：**
- 明确区分 `depthUpdate`（增量）和 `depthSnapshot`（快照）两种消息类型
- 增量消息只包含变化的档位，快照消息包含完整档位并标记 `snapshot=true`

---

### 2.3 ⚠️ Kafka生产者ack配置不一致（可能丢消息）

**问题描述：**
- `application.yml` 中配置 `acks: all`
- `KafkaConfig.producerFactory()` 中覆盖为 `props.put(ProducerConfig.ACKS_CONFIG, "1")`

`acks=1` 意味着 leader 确认即可，如果 leader 在副本同步前崩溃，消息可能丢失。

**建议：** 行情数据应使用 `acks=all`，确保不丢消息。

---

### 2.4 ⚠️ @Async发布异常时仅记录日志，无重试/兜底机制

**问题描述：**
`MarketDataPublisher` 中所有发布方法都是 `@Async`，异常时：
```java
try {
    kafkaTemplate.send(topic, symbol, json);
} catch (Exception e) {
    log.error("...");  // ❌ 仅记录日志，消息丢失
}
```

Kafka发送失败、Redis写入失败时，消息永久丢失，无重试、无告警、无兜底。

**建议：**
- 对 Kafka 发送增加 `ListenableFuture` 回调，失败时入死信队列或重试
- 对 Redis 写入失败增加降级策略（如只写本地缓存）

---

### 2.5 ⚠️ TickerEngine.recalculateStats() 每次全量遍历 O(n)

**问题描述：**
`TickerEngine`（虽然未被使用）每次 `onTrade` 都遍历整个 `CircularTradeBuffer`（10万条）重算24h统计。如果将来启用，这将是严重性能瓶颈。

`TradeEngine` 虽然被使用，但其24h统计实现是错误的（累计值而非滑动窗口）。

---

## 三、重启后消费行为分析

| 场景 | 行为 | 风险 |
|------|------|------|
| 服务重启，trade-event消费 | `auto-offset-reset=latest`，跳过积压，从最新开始 | ✅ 行情场景合理，但可能丢失重启期间的成交 |
| 服务重启，depth.raw消费 | `auto-offset-reset=latest`，跳过积压 | ⚠️ 订单簿从空状态+增量开始，状态可能错误 |
| 服务重启，外部行情消费 | `forceSeekToEndOnAssign=true` + `latest` | ✅ 外部行情跳过旧数据合理 |
| Kafka重平衡后 | 未ack的消息会被重投 | ❌ K线/成交/Ticker无去重，会重复累加 |
| 消费处理异常 | catch后**不ack** | ✅ 会触发重试；但无死信队列，可能无限重试 |

**结论：** 重启后能正常消费新消息，但存在**深度状态恢复风险**和**消息重复消费风险**。

---

## 四、数据重复问题分析

| 数据类型 | 是否有去重 | 重复场景 | 后果 |
|----------|-----------|---------|------|
| 深度更新 | ✅ `updateId` 序号检查 | Kafka重投 | 正确忽略 |
| K线（撮合驱动） | ❌ 无 | Kafka重投 | volume/tradeCount双倍累加 |
| K线（外部驱动） | ✅ `shouldDropEvent` 时序检查 | 外部源重复发布 | 基本防护，但不绝对 |
| 成交统计 | ❌ 无 | Kafka重投 | totalVolume双倍累加 |
| Ticker | ❌ 无 | Kafka重投 | 24h统计双倍累加 |
| ClickHouse K线关闭 | ✅ `saveIfAbsent` | 重复关闭信号 | 正确忽略 |
| ClickHouse 实时K线 | ⚠️ `ReplacingMergeTree` | 重复保存 | 后台合并去重，非实时 |

---

## 五、下游数据写入正确性分析

### 5.1 Kafka Topic 输出

| Topic | 生产者 | 内容 | 问题 |
|-------|--------|------|------|
| `market.trade.{symbol}` | `publishTrade` | 单条成交 | ✅ 正确 |
| `market.aggtrade.{symbol}` | `publishAggTrade` | 100ms聚合成交 | ✅ 正确 |
| `market.kline.{symbol}.{interval}` | `publishKline` / `publishKlineClosed` | K线更新/关闭 | ✅ 正确 |
| `market.depth.{symbol}` | `publishDepth` | **完整快照被标记为增量** | ⚠️ 语义混乱 |
| `market.ticker.{symbol}` | `publishTicker` | 24h统计 | ❌ 数据错误（累计值非24h滑动窗口） |
| `market.markprice.{symbol}` | `publishMarkPrice` | 标记价格 | ✅ 正确 |

### 5.2 Redis 输出

| Key | 内容 | TTL | 问题 |
|-----|------|-----|------|
| `market:snapshot:trade:{symbol}` | 最新成交 | 无 | ✅ 正确 |
| `market:snapshot:depth:{symbol}` | 深度快照 | 无 | ✅ 正确 |
| `market:snapshot:kline:{symbol}:{interval}` | 当前K线 | 无 | ✅ 正确 |
| `market:snapshot:ticker:{symbol}` | 24h统计 | 无 | ❌ 数据错误 |
| `market:kline:{symbol}:{interval}:history` | 历史K线(ZSet) | 无 | ⚠️ 无过期，长期增长 |

### 5.3 ClickHouse 输出

| 表 | 用途 | 写入时机 | 问题 |
|----|------|---------|------|
| `kline_data` | 已完成K线历史 | K线关闭时 | ✅ 幂等插入 |
| `kline_realtime` | 实时K线 | 每秒定时保存 | ⚠️ 撮合K线未写入（严重） |
| `kline_authority` | 权威K线 | 外部K线消费时 | ✅ 有hash去重 |

---

## 六、修复建议（按优先级排序）

### P0 - 立即修复

1. **统一K线引擎**：让 `MarketDataEngineService` 使用 `KlineEngineWithStorage`，或移除 `KlineEngine`，确保撮合K线持久化到ClickHouse
2. **修复24h统计**：在 `MarketDataEngineService.SymbolEngines` 中创建 `TickerEngine`，并启用滑动窗口重算；或修复 `TradeEngine` 的累计值逻辑，改为滑动窗口
3. **增加消费去重**：在 `KlineEngineWithStorage` 和 `TradeEngine` 中按 `sequence` 去重，防止Kafka重投导致数据错误

### P1 - 短期修复

4. **修复深度语义**：区分 `depthSnapshot` 和 `depthUpdate` 消息，增量只发变化档位
5. **修复Kafka acks**：生产者改为 `acks=all`
6. **修复重启后深度恢复**：深度消费改为 `earliest`，或增加快照请求机制
7. **增加发布失败重试**：Kafka/Redis 写入失败时入本地队列重试，而非仅记录日志

### P2 - 中期优化

8. **TickerEngine 性能优化**：改为增量滑动窗口，避免 O(n) 全量遍历
9. **增加监控指标**：Kafka消费延迟、消息丢失率、订单簿序列号gap次数
10. **增加死信队列**：消费失败超过阈值后入死信队列，避免无限重试

---

## 七、总体评估

| 维度 | 评分 | 说明 |
|------|------|------|
| K线处理正确性 | ⭐⭐ (2/5) | 撮合K线未持久化、无去重 |
| 深度处理正确性 | ⭐⭐⭐ (3/5) | 有序号检查，但发布语义混乱 |
| Ticker处理正确性 | ⭐ (1/5) | 引擎未被使用，24h统计错误 |
| 重启后消费 | ⭐⭐⭐ (3/5) | 能消费新消息，深度恢复有风险 |
| 防重复消费 | ⭐⭐ (2/5) | 仅深度有去重 |
| 下游写入正确性 | ⭐⭐⭐ (3/5) | 格式正确，但部分数据值错误 |

**综合结论：不建议直接上线，需修复P0问题后再验证。**
