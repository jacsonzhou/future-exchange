# K线API故障修复指南

> 快速修复market-price-core的K线API 500错误和数据断档问题
>
> 创建时间：2026-03-07

---

## 🔍 问题诊断

### 现象

```
GET /api/v1/klines?symbol=BTCUSDT&interval=1h&limit=500
Response: HTTP 500 - unknown error

客户端日志：
- K线数据存在断档，已回退 Binance 源: BTCUSDT 1h
- 盘口数据跳动不明显
```

### 根本原因

#### 原因1：ClickHouse服务未启动或配置不正确

**配置位置**：`nacos-configs/market-price-core-dev.yml:26-30`

```yaml
spring:
  clickhouse:
    url: jdbc:clickhouse://localhost:8123/exchange_kline
    user: default
    password: clickhouse123456
    socket-timeout: 120000
```

**可能问题**：
- ClickHouse服务未安装/未启动
- 数据库`exchange_kline`不存在
- 表结构未创建

#### 原因2：只存储1m K线，其他周期需实时聚合

**设计现状**：
- 回补：只回补1m K线
- 存储：只存储1m到ClickHouse
- 查询：1h/15m/4h/1d需要从1m实时聚合

**问题**：
- 聚合查询慢（扫描大量1m数据）
- 查询超时（默认120s）
- 返回空结果或异常

#### 原因3：盘口批量聚合延迟

**位置**：Public Push Core的批量窗口50ms

**影响**：
- 深度更新累积50-150ms后才推送
- 客户端感知跳动不明显

---

## ⚡ 快速修复（15分钟）

### 方案1：禁用ClickHouse，使用MySQL降级

适用于：快速恢复服务，牺牲性能

#### 步骤1：修改Service实现

**文件**：`market-price-core/src/main/java/com/exchange/market/config/MarketDataConfig.java`

找到以下行：
```java
@Primary
@ConditionalOnProperty(name = "spring.clickhouse.enabled", havingValue = "true", matchIfMissing = true)
```

修改为：
```java
@Primary
@ConditionalOnProperty(name = "spring.clickhouse.enabled", havingValue = "true", matchIfMissing = false)
// matchIfMissing = false → ClickHouse默认禁用
```

#### 步骤2：修改配置

**文件**：`nacos-configs/market-price-core-dev.yml`

添加（在spring.clickhouse下方）：
```yaml
spring:
  clickhouse:
    enabled: false  # 禁用ClickHouse
```

#### 步骤3：重启服务

```bash
# 重启market-price-core
kill -9 $(lsof -t -i:8095)
java -jar market-price-core.jar
```

#### 步骤4：验证

```bash
# 测试K线API
curl "http://localhost:8095/api/v1/klines?symbol=BTCUSDT&interval=1m&limit=10"

# 预期：返回当前1m K线（来自缓存）
```

**限制**：
- 只能查询当前K线（最多1000条缓存）
- 历史数据查询返回空
- 不支持1h/4h等周期（返回空）

---

## 🔧 完整修复（1-2小时）

### 方案2：正确配置ClickHouse

适用于：生产环境，高性能要求

#### 步骤1：安装ClickHouse（如未安装）

**macOS**：
```bash
brew install clickhouse
```

**Docker**：
```bash
cd /Users/zhoufan/project/future-exchange
docker-compose -f docker-compose.clickhouse.yml up -d
```

**验证安装**：
```bash
clickhouse-client --query "SELECT version()"
```

#### 步骤2：创建数据库和表

**创建数据库**：
```sql
-- 连接ClickHouse
clickhouse-client -u default --password clickhouse123456

-- 创建数据库
CREATE DATABASE IF NOT EXISTS exchange_kline;

USE exchange_kline;
```

**创建表结构**：

```sql
-- K线历史表（ClickHouse优化版）
CREATE TABLE IF NOT EXISTS kline_data (
    symbol String,
    interval String,
    open_time DateTime64(3, 'UTC'),
    close_time DateTime64(3, 'UTC'),

    open_price Decimal64(8),
    high_price Decimal64(8),
    low_price Decimal64(8),
    close_price Decimal64(8),

    volume Decimal64(8),
    quote_volume Decimal64(8),
    trade_count UInt32,

    taker_buy_volume Decimal64(8),
    taker_buy_quote_volume Decimal64(8),

    is_closed UInt8,
    source String DEFAULT 'binance',
    created_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(open_time)
ORDER BY (symbol, interval, open_time)
SETTINGS index_granularity = 8192;

-- 实时K线表
CREATE TABLE IF NOT EXISTS kline_realtime (
    symbol String,
    interval String,
    open_time DateTime64(3, 'UTC'),
    close_time DateTime64(3, 'UTC'),

    open_price Decimal64(8),
    high_price Decimal64(8),
    low_price Decimal64(8),
    close_price Decimal64(8),

    volume Decimal64(8),
    quote_volume Decimal64(8),
    trade_count UInt32,

    taker_buy_volume Decimal64(8),
    taker_buy_quote_volume Decimal64(8),

    is_closed UInt8,
    source String DEFAULT 'binance',
    updated_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY toYYYYMM(open_time)
ORDER BY (symbol, interval, open_time)
SETTINGS index_granularity = 8192;
```

#### 步骤3：启用ClickHouse配置

**文件**：`nacos-configs/market-price-core-dev.yml`

确保配置正确：
```yaml
spring:
  clickhouse:
    enabled: true  # 启用
    url: jdbc:clickhouse://localhost:8123/exchange_kline
    user: default
    password: clickhouse123456
    socket-timeout: 120000
```

#### 步骤4：修改代码（优化聚合查询）

**文件**：`market-price-core/src/main/java/com/exchange/market/repository/KlineRepository.java`

找到`queryAggregatedFrom1m`方法，添加查询超时：

```java
public List<Kline> queryAggregatedFrom1m(...) {
    // ... 构建SQL ...

    try (Connection conn = clickHouseDataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql.toString())) {

        // ⭐ 添加查询超时
        ps.setQueryTimeout(5); // 5秒超时

        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }

        ResultSet rs = ps.executeQuery();
        // ... 处理结果 ...

    } catch (SQLTimeoutException e) {
        log.warn("[KlineRepository] Query timeout for {} {}, fallback", symbol, interval);
        throw new RuntimeException("Query timeout", e);
    }
}
```

#### 步骤5：预计算周期K线（推荐）

**新增Job**：`market-price-core/src/main/java/com/exchange/market/job/KlineAggregationJob.java`

```java
package com.exchange.market.job;

import com.exchange.market.repository.KlineRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * K线聚合任务
 * 定期从1m K线聚合生成其他周期，并存储到ClickHouse
 */
@Slf4j
@Component
public class KlineAggregationJob {

    @Autowired
    private KlineRepository klineRepository;

    private static final List<String> SYMBOLS = Arrays.asList(
        "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT"
    );

    private static final List<String> INTERVALS = Arrays.asList(
        "5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d"
    );

    /**
     * 每5分钟聚合一次最近的K线
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 10000)
    public void aggregateRecentKlines() {
        long now = System.currentTimeMillis();
        long start = now - 86400000L; // 最近24小时

        for (String symbol : SYMBOLS) {
            for (String interval : INTERVALS) {
                try {
                    // 从1m聚合
                    List<Kline> aggregated = klineRepository.queryAggregatedFrom1m(
                        symbol, interval, start, now, 500
                    );

                    // 保存到ClickHouse（使用upsert逻辑）
                    if (!aggregated.isEmpty()) {
                        klineRepository.batchUpsert(aggregated);
                        log.debug("[KlineAgg] Aggregated {} {} klines: {} bars",
                            symbol, interval, aggregated.size());
                    }

                } catch (Exception e) {
                    log.error("[KlineAgg] Failed to aggregate {} {}: {}",
                        symbol, interval, e.getMessage());
                }
            }
        }

        log.info("[KlineAgg] Completed aggregation for {} symbols", SYMBOLS.size());
    }
}
```

**添加batchUpsert方法**（KlineRepository.java）：

```java
public void batchUpsert(List<Kline> klines) {
    String sql = "INSERT INTO kline_data VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    try (Connection conn = clickHouseDataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {

        for (Kline k : klines) {
            ps.setString(1, k.getSymbol());
            ps.setString(2, k.getInterval());
            ps.setTimestamp(3, new Timestamp(k.getOpenTime()));
            ps.setTimestamp(4, new Timestamp(k.getCloseTime()));
            ps.setBigDecimal(5, toBigDecimal(k.getOpenPrice()));
            ps.setBigDecimal(6, toBigDecimal(k.getHighPrice()));
            ps.setBigDecimal(7, toBigDecimal(k.getLowPrice()));
            ps.setBigDecimal(8, toBigDecimal(k.getClosePrice()));
            ps.setBigDecimal(9, toBigDecimal(k.getVolume()));
            ps.setBigDecimal(10, toBigDecimal(k.getQuoteVolume()));
            ps.setInt(11, k.getTradeCount());
            ps.setBigDecimal(12, toBigDecimal(k.getTakerBuyVolume()));
            ps.setBigDecimal(13, toBigDecimal(k.getTakerBuyQuoteVolume()));
            ps.setInt(14, k.isClosed() ? 1 : 0);
            ps.setString(15, k.getSource() != null ? k.getSource() : "binance");
            ps.setTimestamp(16, new Timestamp(System.currentTimeMillis()));
            ps.addBatch();
        }

        ps.executeBatch();
        log.info("[KlineRepository] Batch upsert {} klines", klines.size());

    } catch (SQLException e) {
        log.error("[KlineRepository] Batch upsert failed", e);
        throw new RuntimeException(e);
    }
}
```

#### 步骤6：修改回补Job，支持多周期

**文件**：`BinanceKlineBackfillJob.java`

在回补完成后，立即聚合其他周期：

```java
private void backfillSymbol(String symbol) {
    // ... 原有1m回补逻辑 ...

    log.info("[Backfill] Completed 1m for {}, starting aggregation", symbol);

    // ⭐ 聚合其他周期
    for (String interval : Arrays.asList("5m", "15m", "1h", "4h", "1d")) {
        try {
            List<Kline> aggregated = klineRepository.queryAggregatedFrom1m(
                symbol, interval, backfillStart, now(), 10000
            );

            if (!aggregated.isEmpty()) {
                klineRepository.batchUpsert(aggregated);
                log.info("[Backfill] Aggregated {} {}: {} bars",
                    symbol, interval, aggregated.size());
            }
        } catch (Exception e) {
            log.error("[Backfill] Aggregation failed {} {}: {}",
                symbol, interval, e.getMessage());
        }
    }
}
```

#### 步骤7：重启并验证

```bash
# 重启服务
systemctl restart market-price-core

# 等待回补完成（查看日志）
tail -f logs/market-price-core.log | grep Backfill

# 测试API
curl "http://localhost:8095/api/v1/klines?symbol=BTCUSDT&interval=1h&limit=500"

# 预期：返回完整的1h K线数据
```

---

## 🚀 盘口推送优化

### 问题：跳动不明显

**原因**：批量聚合延迟50ms

### 修复方案

**文件**：`public-push-core配置`

修改批量窗口：

```yaml
# 从50ms减小到10ms（更快推送）
public-push:
  depth:
    batch-interval-ms: 10  # 原50ms
    max-batch-size: 100
```

**或者**：禁用批量聚合，实时推送

```java
// MessageDispatcher.java
private void broadcastDepthUpdate(String channel, JsonObject data) {
    // 直接推送，不聚合
    flushImmediately(channel, data);
}
```

---

## 📊 验证清单

### 1. ClickHouse服务

```bash
# 检查服务状态
clickhouse-client --query "SELECT version()"

# 检查数据库
clickhouse-client -u default --password clickhouse123456 \
  --query "SHOW DATABASES"

# 检查表
clickhouse-client -u default --password clickhouse123456 \
  --query "SHOW TABLES FROM exchange_kline"

# 检查数据量
clickhouse-client -u default --password clickhouse123456 \
  --query "SELECT symbol, interval, count(*) as cnt FROM exchange_kline.kline_data GROUP BY symbol, interval"
```

### 2. K线API

```bash
# 测试1m（应该有数据）
curl "http://localhost:8095/api/v1/klines?symbol=BTCUSDT&interval=1m&limit=10"

# 测试1h（修复后应该有数据）
curl "http://localhost:8095/api/v1/klines?symbol=BTCUSDT&interval=1h&limit=500"

# 测试时间范围查询
curl "http://localhost:8095/api/v1/klines?symbol=BTCUSDT&interval=1h&startTime=1704067200000&limit=100"
```

### 3. 日志检查

```bash
# market-price-core日志
tail -f logs/market-price-core.log | grep -E "ERROR|WARN|Backfill|Aggregation"

# 预期：无ERROR，有Backfill/Aggregation成功日志
```

---

## 🎯 推荐方案

### 短期（立即）

1. **快速修复**：禁用ClickHouse（方案1）
2. 验证API返回当前K线
3. 告知前端使用Binance源（临时）

### 中期（1-2天）

1. **安装ClickHouse**
2. 创建表结构
3. 运行回补Job
4. 启用聚合Job
5. 验证完整周期支持

### 长期（1周）

1. 优化聚合查询性能
2. 添加查询缓存
3. 监控ClickHouse性能
4. 盘口推送优化

---

## 🔍 故障排查

### 问题1：API仍返回500

**检查**：
```bash
# 查看详细错误
curl -v "http://localhost:8095/api/v1/klines?symbol=BTCUSDT&interval=1m&limit=10"

# 检查日志
grep "MarketDataServiceWithClickHouseImpl" logs/market-price-core.log
```

**可能原因**：
- ClickHouse连接失败 → 检查配置和服务状态
- 表不存在 → 执行步骤2创建表
- 查询超时 → 添加步骤4的超时保护

### 问题2：聚合查询慢

**优化**：
```sql
-- 优化ClickHouse表（添加索引）
ALTER TABLE kline_data
  ADD INDEX idx_interval_time (interval, open_time) TYPE minmax GRANULARITY 1;

-- 设置查询限制
SET max_execution_time = 5;
SET max_memory_usage = 10000000000;
```

### 问题3：回补未自动触发

**手动触发**：
```bash
curl -X POST "http://localhost:8095/internal/backfill/trigger?symbol=BTCUSDT"
```

---

## 📁 相关文件

| 文件 | 作用 | 修改点 |
|------|------|--------|
| market-price-core-dev.yml | 配置 | 启用/禁用ClickHouse |
| MarketDataServiceWithClickHouseImpl.java | Service层 | 降级逻辑 |
| KlineRepository.java | DAO层 | 超时保护、batchUpsert |
| BinanceKlineBackfillJob.java | 回补Job | 多周期聚合 |
| KlineAggregationJob.java | 聚合Job | 新增 |

---

**最后更新**：2026-03-07
**维护者**：架构组
