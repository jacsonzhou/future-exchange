# Snapshot Account Core

> **账户快照服务（独立服务）** | Account Snapshot Service  
> Decoupled from Ledger Core

---

## 🎯 核心定位

Snapshot Account Core 是**独立的账户快照服务**，负责：

1. ✅ **消费Ledger Event**：从Kafka消费TradeEntryEvent
2. ✅ **更新账户快照**：维护MySQL的account_snapshot表
3. ✅ **Redis缓存**：提供毫秒级查询（热数据）
4. ✅ **供风控查询**：Risk Gate和API的数据来源
5. ✅ **支持Replay**：灾备重建账户快照

---

## 📊 架构定位

```
Ledger Core (记账)
    ↓ Kafka: trade-entry-{symbol}
┌────────────────────────────┐
│ Snapshot Account Core      │
│ (本服务 - 独立维护快照)     │
│ ┌────────────────────────┐ │
│ │ 1. 消费 TradeEntryEvent│ │
│ │ 2. 更新 MySQL Snapshot │ │
│ │ 3. 更新 Redis 缓存     │ │
│ │ 4. 幂等性保证          │ │
│ └────────────────────────┘ │
└────────────┬───────────────┘
             │
             ↓
   ┌────────────────┐
   │ AccountSnapshot│
   │ - MySQL: 持久化│
   │ - Redis: 热缓存│
   └────────────────┘
             │
             ↓
  Risk Gate / API / UI
```

---

## 🔥 核心职责

### 1. 消费Ledger Event

```java
@KafkaListener(
    topics = "trade-entry-BTCUSDT,trade-entry-ETHUSDT",
    groupId = "snapshot-service",
    concurrency = "1" // 单线程保证顺序
)
public void consumeTradeEntry(String message) {
    TradeEntryEvent event = parse(message);
    accountSnapshotService.onTradeEntryEvent(event);
}
```

### 2. 更新账户快照

```java
@Transactional
public void onTradeEntryEvent(TradeEntryEvent event) {
    for (LedgerEntry entry : event.getEntries()) {
        // 1. 幂等性校验
        if (entry.getBizSeq() <= snapshot.getLastBizSeq()) {
            continue;
        }
        
        // 2. 应用分录
        applyEntry(entry, snapshot);
        
        // 3. 更新MySQL（乐观锁）
        accountSnapshotMapper.updateWithOptimisticLock(snapshot);
        
        // 4. 更新Redis
        updateRedisSnapshot(snapshot);
    }
}
```

### 3. 风控查询

```java
public AccountSnapshot queryAccount(Long userId) {
    // 1. 先查Redis（毫秒级）
    AccountSnapshot snapshot = getFromRedis(userId);
    if (snapshot != null) return snapshot;
    
    // 2. 查MySQL
    snapshot = accountSnapshotMapper.selectById(userId);
    
    // 3. 回写Redis
    updateRedisSnapshot(snapshot);
    
    return snapshot;
}
```

---

## 📡 API接口

### 查询账户快照

```bash
GET /internal/snapshot/account/{userId}
```

**Response**:

```json
{
  "userId": 1001,
  "currency": "USDT",
  "available": "100000.00",
  "frozen": "5000.00",
  "positionMargin": "10000.00",
  "unrealizedPnl": "500.00",
  "equity": "115500.00",
  "marginRatio": "2.5000",
  "lastBizSeq": 123456,
  "updatedAt": 1706169600000
}
```

### 触发Replay

```bash
POST /internal/snapshot/replay/symbol

{
  "symbol": "BTCUSDT"
}
```

### 查询Replay进度

```bash
GET /internal/snapshot/replay/progress/{replayId}
```

---

## 🗄️ 数据表设计

### account_snapshot

```sql
CREATE TABLE account_snapshot (
  user_id BIGINT PRIMARY KEY,
  currency VARCHAR(16) NOT NULL,
  
  available DECIMAL(32,16) NOT NULL,
  frozen DECIMAL(32,16) NOT NULL,
  position_margin DECIMAL(32,16) NOT NULL,
  unrealized_pnl DECIMAL(32,16),
  realized_pnl DECIMAL(32,16),
  equity DECIMAL(32,16) NOT NULL,
  margin_ratio DECIMAL(10,4),
  
  last_biz_seq BIGINT NOT NULL,
  last_entry_id BIGINT,
  last_trade_id VARCHAR(64),
  
  version INT NOT NULL DEFAULT 0,
  
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  
  UNIQUE KEY uk_user_currency (user_id, currency)
) ENGINE=InnoDB;
```

### Redis缓存结构

```
Key: snapshot:{userId}
Type: Hash

Fields:
  available: "100000.00"
  frozen: "5000.00"
  positionMargin: "10000.00"
  unrealizedPnl: "500.00"
  equity: "115500.00"
  marginRatio: "2.5000"
  lastBizSeq: "123456"
  updatedAt: "1706169600000"

TTL: 24小时
```

---

## ⚙️ 配置文件

### application.yml

```yaml
spring:
  application:
    name: snapshot-account-core
  
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: snapshot-service
      concurrency: 1  # 单线程保证顺序
  
  redis:
    host: localhost
    port: 6379
    database: 1
  
  datasource:
    url: jdbc:mysql://localhost:3306/exchange_snapshot

snapshot:
  kafka:
    topics: trade-entry-BTCUSDT,trade-entry-ETHUSDT,trade-entry-SYSTEM
  
  redis:
    ttl-hours: 24
```

---

## 🔧 核心类

| 类 | 职责 |
|---|------|
| `TradeEntryEventConsumer` | 消费Kafka Event |
| `AccountSnapshotService` | 业务接口 |
| `AccountSnapshotServiceImpl` | 业务实现 |
| `ReplayService` | Replay重建 |
| `SnapshotInternalController` | REST API |
| `AccountSnapshot` | 实体类 |
| `AccountSnapshotMapper` | 持久层 |

---

## 🚀 启动

```bash
cd snapshot-account-core
mvn spring-boot:run
```

服务启动在 **http://localhost:8085**

---

## ✅ 核心特性

| 特性 | 说明 |
|------|------|
| **解耦** | 与Ledger完全解耦，通过Kafka通信 |
| **Redis缓存** | 毫秒级查询（0.5ms） |
| **幂等性** | bizSeq递增校验 |
| **乐观锁** | version字段防止并发 |
| **Replay** | 支持灾备重建 |
| **顺序性** | 单线程消费保证顺序 |

---

## 📚 相关文档

- [LEDGER_SNAPSHOT_DECOUPLED_ARCHITECTURE.md](../LEDGER_SNAPSHOT_DECOUPLED_ARCHITECTURE.md) - 解耦架构设计

---

**模块定位**：独立的账户快照服务  
**端口**：8085  
**数据库**：exchange_snapshot  
**Redis**：database 1  
**对标**：Binance / OKX / Bybit级别

