# 📊 行情服务（Market Data Service）架构设计文档

> 版本：v2.0 Production Ready  
> 定位：对标 Binance / OKX / Bybit 架构级别  
> 作者：Exchange Core Team  
> 最后更新：2026-02-18

---

## 一、架构总览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              客户端层 (Client)                               │
│                    WebSocket / REST API / SDK                               │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                    ┌──────────────────┼──────────────────┐
                    ▼                  ▼                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           接入层 (Gateway)                                   │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐             │
│  │  Load Balancer  │  │  CDN / WAF      │  │  Rate Limiter   │             │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘             │
└───────────┼────────────────────┼────────────────────┼──────────────────────┘
            │                    │                    │
            ▼                    ▼                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        WebSocket Gateway 集群                                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐       │
│  │   Node 1    │  │   Node 2    │  │   Node 3    │  │   Node N    │       │
│  │  (AZ1)      │  │  (AZ2)      │  │  (AZ3)      │  │  (AZ...)    │       │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘       │
│         │                │                │                │               │
│         └────────────────┴────────────────┴────────────────┘               │
│                                   │                                         │
│                          Redis Pub/Sub Bus                                  │
└───────────────────────────────────┼─────────────────────────────────────────┘
                                    │
┌───────────────────────────────────┼─────────────────────────────────────────┐
│                        行情服务集群 (Market Data Service)                     │
│                                   │                                         │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                      核心引擎层 (Core Engine)                        │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐│   │
│  │  │ TradeEngine │  │ OrderBook   │  │ KlineEngine │  │ TickerEngine││   │
│  │  │  成交处理    │  │  深度维护    │  │  K线生成    │  │  统计计算   ││   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘│   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                   │                                         │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                      数据访问层 (Data Access)                        │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐│   │
│  │  │Local Cache  │  │Redis Cache  │  │MySQL        │  │ClickHouse   ││   │
│  │  │(Caffeine)   │  │(Hot Data)   │  │(Persistence)│  │(Analytics)  ││   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘│   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                   │                                         │
└───────────────────────────────────┼─────────────────────────────────────────┘
                                    │
┌───────────────────────────────────┼─────────────────────────────────────────┐
│                        消息总线 (Message Bus)                                │
│                                   │                                         │
│                      Kafka Cluster (3 brokers)                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  Topics:                                                            │   │
│  │    - match-event-topic     (全局成交事件, 多分区)                     │   │
│  │    - orderbook-delta-*     (深度增量, 每个symbol独立topic)            │   │
│  │    - index-price-topic     (指数价格)                                │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└───────────────────────────────────┼─────────────────────────────────────────┘
                                    │
┌───────────────────────────────────┼─────────────────────────────────────────┐
│                        上游服务 (Upstream Services)                          │
│                                   │                                         │
│                         Match Engine (撮合引擎)                              │
│                          ↓ publish                                          │
│                    ┌──────────────┴──────────────┐                         │
│                    │      Trade & Depth Events    │                         │
│                    └──────────────────────────────┘                         │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 二、核心组件详解

### 2.1 OrderBook Cache（内存盘口）

**设计目标**：
- 微秒级查询延迟
- 支持百万级档位
- 内存友好（避免GC）

**数据结构**：
```
OrderBook
├── bidLevels: Long2ObjectOpenHashMap<PriceLevel>
├── askLevels: Long2ObjectOpenHashMap<PriceLevel>
├── bidHead: PriceLevel (链表头，最高买价)
├── askHead: PriceLevel (链表头，最低卖价)
└── lastUpdateId: AtomicLong

PriceLevel (侵入式链表节点)
├── price: long (8 bytes)
├── quantity: long (8 bytes)
├── orderCount: int (4 bytes)
├── prev: PriceLevel (8 bytes)
└── next: PriceLevel (8 bytes)
总内存: ~44 bytes/档
```

**操作复杂度**：
| 操作 | 复杂度 | 说明 |
|------|--------|------|
| 查询BBO | O(1) | 直接访问head |
| 更新档位 | O(1) | HashMap查找 |
| 插入档位 | O(n) | 链表遍历，n=相邻档位数 |
| 生成快照 | O(limit) | 遍历limit档 |

### 2.2 Trade Engine（成交处理器）

**核心功能**：
1. 实时成交推送（每条都推）
2. 聚合成交生成（100ms窗口）
3. 24h统计维护（滑动窗口）

**聚合算法**：
```java
// 100ms时间窗口
Window 1: [0ms - 100ms]   → 聚合推送
Window 2: [100ms - 200ms] → 聚合推送
Window 3: [200ms - 300ms] → 聚合推送
```

**滑动窗口实现**：
```java
ArrayBlockingQueue<Trade> recentTrades;  // 固定大小
当新成交到达:
  1. 添加到队列
  2. 如果队列满，移除最老数据
  3. 重新计算统计值
```

### 2.3 Kline Engine（K线生成器）

**时间对齐算法**：
```java
// 1分钟K线示例
openTime = (timestamp / 60000) * 60000

// 例如：
// timestamp = 1712345678123
// openTime  = 1712345640000 (2024-04-06 12:54:00)
// closeTime = 1712345699999 (2024-04-06 12:54:59.999)
```

**周期配置**：
```
1s   → 1000ms
1m   → 60000ms
5m   → 300000ms
1h   → 3600000ms
1d   → 86400000ms
```

### 2.4 WebSocket推送系统

**订阅协议**：
```json
{
  "method": "SUBSCRIBE",
  "params": ["depth.BTCUSDT@100ms", "trade.BTCUSDT"],
  "id": 1
}
```

**消息格式**：
```json
{
  "stream": "depth.BTCUSDT",
  "data": {
    "e": "depthUpdate",
    "E": 1712345678123,
    "s": "BTCUSDT",
    "U": 1027025,
    "u": 1027028,
    "b": [["50231.50", "1.100"]],
    "a": [["50232.00", "0.950"]]
  }
}
```

**连接管理**：
- 心跳间隔：30秒
- 最大连接数：100,000/节点
- 消息压缩：支持permessage-deflate

---

## 三、数据流设计

### 3.1 成交事件流

```
┌──────────────┐
│ Match Engine │─── 生成 TradeEvent
└──────┬───────┘
       │ write
       ▼
┌──────────────┐
│ Kafka Topic  │─── match-event-topic (partition by symbol)
│ (3 replicas) │
└──────┬───────┘
       │ consume
       ▼
┌──────────────────────────┐
│ MarketPriceService       │
│ ┌──────────────────────┐ │
│ │ TradeEngine          │ │─── 更新统计
│ │ ├─ lastPrice         │ │
│ │ ├─ 24h stats         │ │
│ │ └─ aggregate trades  │ │
│ └──────────────────────┘ │
│ ┌──────────────────────┐ │
│ │ KlineEngine          │ │─── 更新K线
│ │ └─ all intervals     │ │
│ └──────────────────────┘ │
└──────┬───────────────────┘
       │ publish
       ▼
┌──────────────────────────┐
│ Redis Pub/Sub            │
│ ├─ market:trade:BTCUSDT  │
│ ├─ market:ticker:BTCUSDT │
│ └─ market:kline:BTCUSDT  │
└──────┬───────────────────┘
       │ subscribe
       ▼
┌──────────────────────────┐
│ WebSocket Gateway        │─── 推送给客户端
└──────────────────────────┘
```

### 3.2 深度更新流

```
┌──────────────┐
│ Match Engine │─── 生成 OrderBook Delta
└──────┬───────┘
       │ write
       ▼
┌──────────────┐
│ Kafka Topic  │─── orderbook-delta-BTCUSDT (1 partition)
└──────┬───────┘
       │ consume (single thread per symbol)
       ▼
┌──────────────────────────┐
│ OrderBook.applyDelta()   │─── 更新内存盘口
└──────┬───────────────────┘
       │ publish delta
       ▼
┌──────────────────────────┐
│ Redis Pub/Sub            │
└──────┬───────────────────┘
       │
       ▼
┌──────────────────────────┐
│ WebSocket Gateway        │
└──────────────────────────┘
```

---

## 四、存储策略

### 4.1 存储分层

```
┌─────────────────────────────────────────────────────────────┐
│                      存储分层架构                            │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Layer 1: 内存 (In-Memory)                                   │
│  ├── OrderBook Cache (当前盘口)                              │
│  ├── Current Kline (当前K线)                                 │
│  └── Recent Trades (最近成交)                                │
│  延迟: < 1μs | 容量: GB级 | 持久化: 否                        │
│                                                             │
│  Layer 2: 本地缓存 (Caffeine)                                │
│  ├── Hot Klines (热门K线)                                    │
│  ├── Ticker Stats (统计数据)                                 │
│  └── Depth Snapshots (深度快照)                              │
│  延迟: ~1μs | 容量: 10GB | 持久化: 否                         │
│                                                             │
│  Layer 3: Redis Cluster                                      │
│  ├── Real-time Data (实时数据)                               │
│  ├── Pub/Sub Channels (消息广播)                             │
│  └── Recent History (近期历史)                               │
│  延迟: ~1ms | 容量: 100GB | 持久化: RDB/AOF                  │
│                                                             │
│  Layer 4: MySQL (分区分表)                                   │
│  ├── Kline History (K线历史)                                 │
│  ├── Trade History (成交历史)                                │
│  └── Ticker Snapshots (统计快照)                             │
│  延迟: ~10ms | 容量: TB级 | 持久化: 是                        │
│                                                             │
│  Layer 5: ClickHouse (可选)                                  │
│  └── Analytics Data (分析数据)                               │
│  延迟: ~100ms | 容量: PB级 | 持久化: 是                       │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 Redis Key 设计

```
# 实时数据
market:trade:{symbol}:last              → String (JSON)
market:trade:{symbol}:stream            → Stream (最近1000条)

# K线数据  
market:kline:{symbol}:{interval}:current    → String (当前K线)
market:kline:{symbol}:{interval}:history    → SortedSet (score=openTime)

# 统计数据
market:ticker:{symbol}                  → Hash
market:ticker:all                       → Hash (所有symbol)

# 深度数据
market:depth:{symbol}:snapshot          → String (JSON)
market:depth:{symbol}:lastUpdateId      → String

# 标记价格
market:markprice:{symbol}               → String (JSON)

# WebSocket管理
ws:session:{sessionId}:subscriptions    → Set
ws:channel:{channel}:subscribers        → Set
```

---

## 五、高可用设计

### 5.1 故障场景处理

| 故障类型 | 检测时间 | 恢复策略 | 数据一致性 |
|---------|---------|---------|-----------|
| 单节点宕机 | 5s (健康检查) | 流量切换到其他节点 | 无丢失 |
| Redis主宕机 | 1s | 切换到从节点 | 无丢失 |
| Kafka分区不可用 | 10s | 切换到ISR副本 | 无丢失 |
| 网络分区 | 依赖配置 | 最小可用原则 | 可能短暂不一致 |
| 进程OOM | 立即 | 自动重启，从Kafka恢复 | 内存数据重建 |

### 5.2 灾备恢复流程

```
1. 服务启动
   ↓
2. 从MySQL加载历史K线（最近N根）
   ↓
3. 从Redis加载当前K线、Ticker
   ↓
4. 从Kafka消费者组恢复偏移量
   ↓
5. 开始消费Kafka重建内存状态
   ↓
6. OrderBook从撮合引擎请求快照
   ↓
7. 恢复正常服务
```

---

## 六、性能指标

### 6.1 延迟指标

| 指标 | 目标值 | 测试方法 |
|------|--------|---------|
| 撮合 → 行情推送 | < 5ms | 时间戳差值 |
| REST API查询 | < 10ms | p99响应时间 |
| WebSocket推送 | < 5ms | 端到端延迟 |
| K线更新 | < 50ms | 事件时间差 |

### 6.2 吞吐指标

| 指标 | 目标值 | 测试方法 |
|------|--------|---------|
| 成交处理 | 200k/s | JMH压测 |
| WebSocket并发 | 100k/节点 | 连接数测试 |
| 消息推送 | 1M/s | 广播测试 |

---

## 七、部署架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        Kubernetes Cluster                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Namespace: market-data                                          │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  Deployment: market-price-service                        │   │
│  │  Replicas: 3                                             │   │
│  │  Resources:                                              │   │
│  │    - CPU: 4 cores                                        │   │
│  │    - Memory: 8GB                                         │   │
│  │    - JVM Heap: 6GB                                       │   │
│  │  Pod Anti-Affinity: 跨可用区部署                          │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  Service: market-price-service                           │   │
│  │  Type: ClusterIP                                         │   │
│  │  Ports:                                                  │   │
│  │    - 8095 (HTTP REST API)                                │   │
│  │    - 8096 (WebSocket)                                    │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  Ingress: market-price-api                               │   │
│  │  Host: api.exchange.com                                  │   │
│  │  Paths:                                                  │   │
│  │    - /api/v1/* → market-price-service:8095               │   │
│  │    - /ws/market → market-price-service:8096              │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  HPA: market-price-service-hpa                           │   │
│  │  Min Replicas: 3                                         │   │
│  │  Max Replicas: 10                                        │   │
│  │  Metrics:                                                │   │
│  │    - CPU > 70% 扩容                                      │   │
│  │    - Memory > 80% 扩容                                    │   │
│  │    - WebSocket连接数 > 80% 扩容                           │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 八、监控告警

### 8.1 关键指标

| 指标 | 类型 | 阈值 | 告警级别 |
|------|------|------|---------|
| kafka_consumer_lag | Gauge | > 1000 | Warning |
| websocket_connections | Gauge | > 80000 | Warning |
| orderbook_update_latency | Histogram | p99 > 10ms | Critical |
| trade_processing_rate | Counter | 下降50% | Warning |
| redis_memory_usage | Gauge | > 85% | Warning |

### 8.2 Dashboard配置

```yaml
# Grafana Dashboard配置示例
dashboard:
  title: "Market Data Service"
  panels:
    - title: "Trade Processing Rate"
      type: graph
      targets:
        - expr: rate(trade_processed_total[1m])
    
    - title: "WebSocket Connections"
      type: singlestat
      targets:
        - expr: websocket_active_connections
    
    - title: "Kafka Consumer Lag"
      type: graph
      targets:
        - expr: kafka_consumer_records_lag_max
    
    - title: "OrderBook Update Latency"
      type: heatmap
      targets:
        - expr: orderbook_update_duration_seconds_bucket
```

---

## 九、总结

行情服务是交易所的核心组件，设计原则：

1. **低延迟优先**：< 10ms 端到端延迟
2. **水平扩展**：无状态设计，支持动态扩缩容
3. **与账本解耦**：只关注市场数据，不处理资金
4. **高可用**：多副本部署，自动故障恢复
5. **可观测**：全链路监控，快速定位问题

**核心设计思想**：
> 行情是市场事实的实时投影，不是账户计算结果。

---

*本文档供技术团队参考，如有疑问请联系核心架构组。*
