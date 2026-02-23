# ClickHouse K 线存储模块

## 📋 概述

本模块使用 **ClickHouse** 作为 K 线数据的存储引擎，提供高性能的时序数据存储和查询能力。

## 🏗️ 架构设计

```
┌─────────────────────────────────────────────────────────────────┐
│                     Market Price Core                           │
│                        (端口 8095)                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────────────┐ │
│  │ Match Event │───▶│ KlineEngine │───▶│ ClickHouse (K 线)   │ │
│  │  Consumer   │    │  WithStorage│    │                     │ │
│  └─────────────┘    └──────┬──────┘    │  - kline_data       │ │
│                            │           │  - kline_realtime   │ │
│                            ▼           │  - trade_data       │ │
│                     ┌─────────────┐    └─────────────────────┘ │
│                     │   Kafka     │              │             │
│                     │ market.kline│◀─────────────┘             │
│                     └──────┬──────┘                            │
│                            │                                   │
└────────────────────────────┼───────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Public Push System                           │
│                        (端口 8096)                              │
└─────────────────────────────────────────────────────────────────┘
```

## 📁 目录结构

```
market-price-core/
├── src/main/java/com/exchange/market/
│   ├── config/
│   │   └── ClickHouseConfig.java          # ClickHouse 配置
│   ├── repository/
│   │   └── KlineRepository.java           # K 线数据访问层
│   ├── service/
│   │   ├── KlineService.java              # K 线服务
│   │   └── impl/
│   │       └── MarketDataServiceWithClickHouseImpl.java
│   └── engine/
│       └── KlineEngineWithStorage.java    # K 线生成引擎
├── src/main/resources/
│   └── application.yml
└── pom.xml

clickhouse/
├── config.d/
│   └── exchange-config.xml               # ClickHouse 配置
├── users.d/
│   └── exchange-users.xml                # 用户配置
└── init/
    └── 01_create_kline_tables.sql        # 表结构初始化

docker-compose.clickhouse.yml             # Docker 部署配置
```

## 🚀 快速开始

### 1. 启动 ClickHouse

```bash
# 方式一：使用脚本启动
./scripts/start-clickhouse.sh

# 方式二：使用 docker-compose
docker-compose -f docker-compose.clickhouse.yml up -d

# 方式三：完整安装测试（包含测试数据）
./scripts/setup-clickhouse.sh
```

### 2. 访问 ClickHouse

| 接口 | 地址 | 说明 |
|------|------|------|
| HTTP | http://localhost:8123 | JDBC/HTTP 接口 |
| TCP | localhost:9000 | Native 协议 |
| Web UI | http://localhost:8088 | Tabix 管理界面 |

**默认账号：**
- User: `default`
- Password: `clickhouse123456`

### 3. 验证安装

```bash
# 测试连接
curl http://localhost:8123/ping

# 查看表结构
curl -X POST -d "SHOW TABLES FROM exchange_kline" http://localhost:8123

# 查看 K 线数据
curl -X POST \
  -H "Content-Type: application/json" \
  -d "SELECT * FROM exchange_kline.kline_data LIMIT 5" \
  http://localhost:8123
```

## 📊 表结构设计

### kline_data (历史 K 线)

```sql
CREATE TABLE exchange_kline.kline_data (
    symbol String,                    -- 交易对
    interval String,                  -- 周期 (1m, 5m, 1h, 1d...)
    open_time DateTime64(3),          -- 开盘时间
    close_time DateTime64(3),         -- 收盘时间
    open_price Decimal(32, 8),        -- 开盘价
    high_price Decimal(32, 8),        -- 最高价
    low_price Decimal(32, 8),         -- 最低价
    close_price Decimal(32, 8),       -- 收盘价
    volume Decimal(32, 8),            -- 成交量
    quote_volume Decimal(32, 8),      -- 成交额
    trade_count UInt32,               -- 成交笔数
    taker_buy_volume Decimal(32, 8),  -- 主动买入成交量
    taker_buy_quote_volume Decimal(32, 8) -- 主动买入成交额
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(event_date)
ORDER BY (symbol, interval, open_time)
TTL event_date + INTERVAL 3 YEAR;
```

### kline_realtime (实时 K 线)

```sql
CREATE TABLE exchange_kline.kline_realtime (
    -- 同上字段
    version UInt64                    -- 版本号（用于 ReplacingMergeTree）
) ENGINE = ReplacingMergeTree(version)
PARTITION BY (symbol, interval)
ORDER BY (symbol, interval, open_time);
```

### trade_data (成交明细)

```sql
CREATE TABLE exchange_kline.trade_data (
    symbol String,
    trade_id UInt64,
    price Decimal(32, 8),
    quantity Decimal(32, 8),
    trade_time DateTime64(3),
    is_buyer_maker UInt8
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(event_date)
ORDER BY (symbol, trade_time)
TTL event_date + INTERVAL 1 MONTH;
```

## 🔌 API 接口

### REST API

```bash
# 获取 K 线数据
GET /api/v1/klines?symbol=BTCUSDT&interval=1m&limit=500

# 参数：
# - symbol: 交易对 (必填)
# - interval: 周期 (必填，支持 1s, 1m, 5m, 15m, 30m, 1h, 2h, 4h, 6h, 8h, 12h, 1d, 3d, 1w, 1M)
# - startTime: 开始时间戳 (毫秒，可选)
# - endTime: 结束时间戳 (毫秒，可选)
# - limit: 返回条数 (默认 500，最大 1000)

# 返回格式（Binance 兼容）：
[
  [
    1499040000000,      // 开盘时间
    "0.01634790",       // 开盘价
    "0.80000000",       // 最高价
    "0.01575800",       // 最低价
    "0.01577100",       // 收盘价
    "148976.11427815",  // 成交量
    1499644799999,      // 收盘时间
    "2434.19055334",    // 成交额
    308,                // 成交笔数
    "1756.87402397",    // 主动买入成交量
    "28.46694368"       // 主动买入成交额
  ]
]
```

### WebSocket 推送

```javascript
// 订阅 K 线频道
{
  "method": "SUBSCRIBE",
  "params": ["btcusdt@kline_1m"],
  "id": 1
}

// K 线推送数据格式
{
  "e": "kline",           // 事件类型
  "E": 123456789,         // 事件时间
  "s": "BTCUSDT",         // 交易对
  "k": {
    "t": 123400000,       // K 线开始时间
    "T": 123460000,       // K 线结束时间
    "s": "BTCUSDT",       // 交易对
    "i": "1m",            // 周期
    "o": "0.0010",        // 开盘价
    "c": "0.0020",        // 收盘价
    "h": "0.0025",        // 最高价
    "l": "0.0015",        // 最低价
    "v": "1000",          // 成交量
    "n": 100,             // 成交笔数
    "x": false,           // 是否收盘
    "q": "1.0000",        // 成交额
    "V": "500",           // 主动买入成交量
    "Q": "0.500"          // 主动买入成交额
  }
}
```

## ⚙️ 配置说明

### application.yml

```yaml
spring:
  clickhouse:
    url: jdbc:clickhouse://localhost:8123/exchange_kline
    user: default
    password: clickhouse123456
    socket-timeout: 30000

market-data:
  kline:
    intervals: 1s,1m,5m,15m,30m,1h,2h,4h,6h,8h,12h,1d,3d,1w,1M
    history-limit: 1000
    save-interval: 1
```

## 📈 性能优化

### ClickHouse 优化

| 优化项 | 配置 | 说明 |
|--------|------|------|
| 分区 | `PARTITION BY toYYYYMM(event_date)` | 按月分区，便于数据管理 |
| 排序键 | `ORDER BY (symbol, interval, open_time)` | 最左前缀匹配查询 |
| TTL | `TTL event_date + INTERVAL 3 YEAR` | 自动清理过期数据 |
| 压缩 | 默认 LZ4 | 高压缩比，节省存储 |
| 索引粒度 | `index_granularity = 8192` | 适合时序数据 |

### 查询优化

```sql
-- ✅ 推荐：使用分区过滤
SELECT * FROM kline_data 
WHERE symbol = 'BTCUSDT' 
  AND interval = '1m'
  AND event_date >= '2024-01-01'
ORDER BY open_time DESC
LIMIT 500;

-- ❌ 避免：全表扫描
SELECT * FROM kline_data WHERE open_price > 1000;
```

## 🔍 常见问题

### 1. 连接失败

```bash
# 检查容器状态
docker-compose -f docker-compose.clickhouse.yml ps

# 查看日志
docker-compose -f docker-compose.clickhouse.yml logs clickhouse-server
```

### 2. 性能问题

```sql
-- 检查表大小
SELECT 
    table,
    formatReadableSize(sum(data_uncompressed_bytes)) AS uncompressed,
    formatReadableSize(sum(data_compressed_bytes)) AS compressed,
    round(sum(data_compressed_bytes) / sum(data_uncompressed_bytes) * 100, 2) AS ratio
FROM system.parts
WHERE database = 'exchange_kline'
GROUP BY table;

-- 检查查询性能
SELECT query, query_duration_ms, read_rows
FROM system.query_log
ORDER BY event_time DESC
LIMIT 10;
```

### 3. 数据不一致

```sql
-- 手动合并 ReplacingMergeTree 数据
OPTIMIZE TABLE kline_realtime FINAL;
```

## 📝 注意事项

1. **时区问题**：ClickHouse 默认使用服务器时区，建议统一使用 UTC 或 Asia/Shanghai
2. **数据精度**：价格使用 Decimal(32, 8)，避免浮点数精度问题
3. **批量写入**：使用批量插入（batch size 建议 1000-10000）
4. **分区管理**：定期检查分区大小，避免分区过多
5. **内存使用**：ClickHouse 内存使用较高，建议至少 4GB 内存

## 🔗 相关链接

- [ClickHouse 官方文档](https://clickhouse.com/docs)
- [ClickHouse JDBC Driver](https://github.com/ClickHouse/clickhouse-java)
- [Tabix Web UI](https://github.com/tabulapdf/tabula-java)

## 📄 License

MIT License
