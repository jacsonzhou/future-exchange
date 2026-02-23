# Snapshot-Account-Core 重构完成总结

## ✅ 重构内容

### 1. 模块重命名

```bash
snapshot-core → snapshot-account-core
```

**原因**：明确职责，本模块只负责账户快照，不包含持仓快照

---

### 2. 删除Position相关代码

✅ **已删除**：
- `PositionSnapshotService.java`
- `PositionSnapshotServiceImpl.java`

**原因**：账户快照和持仓快照应该由不同服务管理，保持单一职责

---

### 3. 配置更新

#### pom.xml

```xml
<artifactId>snapshot-account-core</artifactId>
<name>Snapshot Account Core</name>
<description>Account snapshot service - decoupled from Ledger Core</description>
```

#### application.yml

```yaml
spring:
  application:
    name: snapshot-account-core
```

#### 父pom.xml

```xml
<modules>
    ...
    <module>snapshot-account-core</module>
    ...
</modules>
```

---

### 4. 新增文件

✅ **README.md** - 模块说明文档
✅ **quickstart.sh** - 快速启动脚本
✅ **account_snapshot_schema.sql** - 数据库建表脚本

---

## 📊 模块定位

```
┌─────────────────────────┐
│ Snapshot-Account-Core   │
│ (独立服务)               │
│                         │
│ 🎯 职责：               │
│ 1. 消费 Ledger Event    │
│ 2. 更新账户快照(MySQL)   │
│ 3. 更新Redis缓存         │
│ 4. 供风控查询            │
│ 5. 支持Replay重建        │
│                         │
│ ❌ 不包含：             │
│ - 持仓快照管理           │
│ - Ledger记账逻辑        │
└─────────────────────────┘
```

---

## 🔧 核心组件

| 组件 | 职责 |
|------|------|
| `AccountSnapshot` | 账户快照实体 |
| `AccountSnapshotService` | 业务服务接口 |
| `AccountSnapshotServiceImpl` | 业务实现 |
| `TradeEntryEventConsumer` | Kafka消费者 |
| `ReplayService` | Replay重建 |
| `SnapshotInternalController` | REST API |

---

## 📡 数据流

```
Ledger-Core
    ↓ Kafka: trade-entry-{symbol}
Snapshot-Account-Core
    ├─ 消费 TradeEntryEvent
    ├─ 更新 account_snapshot (MySQL)
    └─ 更新 snapshot:{userId} (Redis)
         ↓
Risk Gate / API
    └─ 查询账户快照
```

---

## 🗄️ 数据表

### account_snapshot

```sql
CREATE TABLE account_snapshot (
  user_id BIGINT PRIMARY KEY,
  currency VARCHAR(16) NOT NULL,
  
  available DECIMAL(32,16) NOT NULL,      -- 可用余额
  frozen DECIMAL(32,16) NOT NULL,         -- 冻结余额
  position_margin DECIMAL(32,16) NOT NULL, -- 持仓保证金
  unrealized_pnl DECIMAL(32,16),          -- 未实现盈亏
  realized_pnl DECIMAL(32,16),            -- 已实现盈亏
  equity DECIMAL(32,16) NOT NULL,         -- 权益
  margin_ratio DECIMAL(10,4),             -- 保证金率
  
  last_biz_seq BIGINT NOT NULL,           -- 幂等性
  last_entry_id BIGINT,
  last_trade_id VARCHAR(64),
  
  version INT NOT NULL DEFAULT 0,         -- 乐观锁
  
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  
  UNIQUE KEY uk_user_currency (user_id, currency)
) ENGINE=InnoDB;
```

---

## 🚀 启动方式

```bash
cd snapshot-account-core
./quickstart.sh
```

或手动启动：

```bash
mvn spring-boot:run
```

**端口**：8085  
**数据库**：exchange_snapshot  
**Redis**：database 1

---

## 📚 API接口

### 查询账户快照

```bash
GET /internal/snapshot/account/{userId}
```

### 触发Replay

```bash
POST /internal/snapshot/replay/symbol
{
  "symbol": "BTCUSDT"
}
```

---

## ✅ 核心特性

| 特性 | 说明 |
|------|------|
| **单一职责** | 只负责账户快照 |
| **解耦** | 与Ledger完全解耦 |
| **Redis缓存** | 毫秒级查询 |
| **幂等性** | bizSeq递增校验 |
| **乐观锁** | version字段 |
| **Replay** | 支持灾备重建 |

---

## 🎯 下一步（可选）

如果需要持仓快照管理，可以创建独立的 `snapshot-position-core` 模块：

```
snapshot-position-core/
  ├─ PositionSnapshot实体
  ├─ PositionSnapshotService
  └─ 消费Position相关Event
```

**原则**：账户和持仓分离，各自独立维护

---

## 📝 文档

- [README.md](snapshot-account-core/README.md) - 模块说明
- [LEDGER_SNAPSHOT_DECOUPLED_ARCHITECTURE.md](LEDGER_SNAPSHOT_DECOUPLED_ARCHITECTURE.md) - 架构设计

---

**重构完成时间**：2026-01-25  
**状态**：✅ 模块重命名和职责明确完成

