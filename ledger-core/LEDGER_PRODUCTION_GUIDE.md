# Ledger Core 生产级实现文档

## 📖 总览

Ledger Core 是整个交易所的**资金真相源**（Single Source of Truth），采用双录记账（Double-Entry Bookkeeping）体系，支持分库分表、Replay重建、实时对账等企业级特性。

**对标标准**：Binance / OKX / Bybit 级别

---

## 🏗️ 核心架构

```
Match Engine (撮合)
     ↓ Kafka: trade-event
Ledger Core (消费)
     ↓ 双录分录
LedgerEntry (真相源)
     ↓ 聚合
AccountSnapshot (性能层)
     ↓ 查询
Risk Engine / API / UI
```

### 数据层次

| 层级 | 是否真相 | 是否可重建 | 用途 |
|------|---------|-----------|------|
| **LedgerEntry** | ✅ | ✅ | 审计、对账、Replay |
| **AccountSnapshot** | ❌ (派生) | ✅ | 风控查询、API查询 |
| ~~account.balance~~ | ❌ 禁止 | ❌ | - |

---

## 📊 数据库表设计

### 1. ledger_entry_YYYYMM (分库分表)

**分库策略**：按 `user_id % 128` 分库  
**分表策略**：按月分表（如 `ledger_entry_202601`）

```sql
CREATE TABLE ledger_entry_202601 (
  entry_id BIGINT PRIMARY KEY COMMENT '分录ID（Snowflake）',
  
  user_id BIGINT NOT NULL COMMENT '用户ID',
  account_type TINYINT NOT NULL COMMENT '账户类型',
  currency VARCHAR(16) NOT NULL COMMENT '币种',
  
  debit DECIMAL(32,16) NOT NULL COMMENT '借方金额',
  credit DECIMAL(32,16) NOT NULL COMMENT '贷方金额',
  
  pair_entry_id BIGINT COMMENT '成对分录ID',
  balance_after DECIMAL(32,16) COMMENT '分录后余额',
  
  business_type VARCHAR(32) NOT NULL COMMENT '业务类型',
  ref_trade_id VARCHAR(64) COMMENT '关联成交ID',
  ref_order_id BIGINT COMMENT '关联订单ID',
  
  biz_seq BIGINT NOT NULL COMMENT '全局业务序列号（Replay核心）',
  idempotent_key VARCHAR(128) COMMENT '幂等键',
  
  created_at BIGINT NOT NULL COMMENT '创建时间',
  
  KEY idx_user_time (user_id, created_at),
  KEY idx_biz_seq (biz_seq),
  UNIQUE KEY uk_idempotent (idempotent_key)
) ENGINE=InnoDB COMMENT='双录分录表';
```

**关键字段说明**：

- `biz_seq`：全局递增序列号，用于 Replay 时按顺序回放
- `idempotent_key`：幂等键，防止 Kafka 重复消费
- `pair_entry_id`：成对分录 ID，借贷分录相互关联

### 2. account_snapshot

```sql
CREATE TABLE account_snapshot (
  user_id BIGINT PRIMARY KEY COMMENT '用户ID',
  currency VARCHAR(16) NOT NULL COMMENT '币种',
  
  available DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '可用余额',
  frozen DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '冻结保证金',
  position_margin DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '持仓占用保证金',
  unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '未实现盈亏',
  realized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '已实现盈亏',
  equity DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '权益',
  
  last_ledger_seq BIGINT NOT NULL DEFAULT 0 COMMENT '最后同步biz_seq',
  last_ledger_entry_id BIGINT COMMENT '最后同步entry_id',
  
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  checksum VARCHAR(64) COMMENT '校验哈希',
  
  updated_at BIGINT NOT NULL COMMENT '更新时间',
  
  UNIQUE KEY uk_user_currency (user_id, currency)
) ENGINE=InnoDB COMMENT='账户快照';
```

**关键字段说明**：

- `equity = available + frozen + position_margin + unrealized_pnl`
- `version`：乐观锁，防止并发更新
- `last_ledger_seq`：用于增量同步和断点续传

### 3. ledger_reconciliation_log（对账记录）

```sql
CREATE TABLE ledger_reconciliation_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  check_date DATE NOT NULL COMMENT '对账日期',
  user_id BIGINT NOT NULL COMMENT '用户ID',
  currency VARCHAR(16) NOT NULL COMMENT '币种',
  ledger_balance DECIMAL(32,16) NOT NULL COMMENT 'Ledger计算余额',
  snapshot_balance DECIMAL(32,16) NOT NULL COMMENT '快照余额',
  diff_amount DECIMAL(32,16) NOT NULL COMMENT '差异',
  status TINYINT NOT NULL COMMENT '0=正常,1=差异,2=已修复',
  check_start_seq BIGINT COMMENT '检查起始biz_seq',
  check_end_seq BIGINT COMMENT '检查结束biz_seq',
  created_at BIGINT NOT NULL COMMENT '创建时间',
  
  KEY idx_user_date (user_id, check_date)
) ENGINE=InnoDB COMMENT='对账记录';
```

### 4. ledger_replay_log（Replay记录）

```sql
CREATE TABLE ledger_replay_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  replay_id VARCHAR(64) NOT NULL COMMENT 'Replay任务ID',
  start_biz_seq BIGINT NOT NULL COMMENT '起始biz_seq',
  end_biz_seq BIGINT NOT NULL COMMENT '结束biz_seq',
  status TINYINT NOT NULL COMMENT '0=进行中,1=成功,2=失败',
  start_time BIGINT NOT NULL COMMENT '开始时间',
  end_time BIGINT COMMENT '结束时间',
  remark VARCHAR(255) COMMENT '备注',
  
  UNIQUE KEY uk_replay_id (replay_id)
) ENGINE=InnoDB COMMENT='Replay记录';
```

---

## 🔥 双录记账示例

### 成交示例（用户A买入，用户B卖出）

假设：
- 用户A买入 1 BTC，价格 10000 USDT
- 用户B卖出 1 BTC，价格 10000 USDT
- 手续费：A=10 USDT，B=10 USDT

**分录**：

| entry_id | user_id | account_type | debit | credit | business_type | ref_trade_id |
|----------|---------|--------------|-------|--------|---------------|-------------|
| 1 | A | USER_AVAILABLE | 0 | 10000 | TRADE_SETTLE | T001 |
| 2 | A | USER_POSITION_MARGIN | 10000 | 0 | TRADE_SETTLE | T001 |
| 3 | B | USER_POSITION_MARGIN | 0 | 10000 | TRADE_SETTLE | T001 |
| 4 | B | USER_AVAILABLE | 10000 | 0 | TRADE_SETTLE | T001 |
| 5 | A | USER_AVAILABLE | 0 | 10 | TRADE_FEE | T001 |
| 6 | SYS | EXCHANGE_FEE | 10 | 0 | TRADE_FEE | T001 |
| 7 | B | USER_AVAILABLE | 0 | 10 | TRADE_FEE | T001 |
| 8 | SYS | EXCHANGE_FEE | 10 | 0 | TRADE_FEE | T001 |

**校验**：`SUM(debit) = SUM(credit) = 20020`

---

## 🔄 核心流程

### 1. 消费 TradeEvent（TradeEventConsumer）

```java
@KafkaListener(topics = "trade-event", groupId = "ledger-service")
public void consumeTradeEvent(String message) {
    TradeDTO trade = objectMapper.readValue(message, TradeDTO.class);
    ledgerService.applyTrade(trade);
}
```

### 2. 应用成交（LedgerServiceImpl）

```java
@Transactional
public void applyTrade(TradeDTO trade) {
    // 1. 检查幂等性（idempotent_key）
    // 2. 生成双录分录（买方+卖方，各2条）
    // 3. 写入 ledger_entry 表
    // 4. 更新 account_snapshot
    // 5. 记录手续费分录
}
```

### 3. 每日对账（LedgerReconciliationJob）

```java
@Scheduled(cron = "0 0 2 * * ?")
public void dailyReconciliation() {
    // 1. 查询所有用户
    // 2. 从 LedgerEntry 计算余额
    // 3. 从 AccountSnapshot 读取余额
    // 4. 比对差异
    // 5. 记录对账结果
    // 6. 告警（如有差异）
}
```

### 4. Replay重建（LedgerReplayServiceImpl）

```java
@Async
public String replayAll(Long startBizSeq, Long endBizSeq) {
    // 1. 清空 AccountSnapshot
    // 2. 按 biz_seq 顺序读取 LedgerEntry
    // 3. 累积计算余额
    // 4. 批量写入 AccountSnapshot
    // 5. 记录 Replay 日志
}
```

---

## 📡 Kafka消费

### Topic: trade-event

**消费者**：`TradeEventConsumer`

**Message格式**：

```json
{
  "tradeId": "T001",
  "symbol": "BTCUSDT",
  "makerUserId": 1001,
  "takerUserId": 1002,
  "makerOrderId": 10001,
  "takerOrderId": 10002,
  "price": "10000.00",
  "quantity": "1.0",
  "isMakerBuy": true,
  "makerFee": "10.00",
  "takerFee": "10.00",
  "tradeTime": 1706169600000,
  "matchSequence": 123456
}
```

---

## 🛡️ 风控读取

**Hard Risk Gate** 读取 `AccountSnapshot`，而不是 `LedgerEntry`。

```java
@GetMapping("/account/{userId}")
public AccountSnapshot getAccountSnapshot(@PathVariable Long userId) {
    return ledgerService.getAccountSnapshot(userId);
}
```

**风控判断**：

```java
// 1. 下单检查
available >= initialMarginRequired

// 2. 强平检查
marginRatio = equity / maintenanceMargin
if (marginRatio < 1.0) {
    triggerLiquidation();
}
```

---

## 📈 性能优化

### 1. 分库分表

- **分库**：`user_id % 128`
- **分表**：按月（`ledger_entry_202601`）

### 2. 批量写入

```java
ledgerEntryMapper.batchInsert(entries);
```

### 3. Redis缓存

- `biz_seq` 生成器：`Redis INCR`
- Replay进度：`Redis String`

### 4. 异步Replay

```java
@Async(value = "replayExecutor")
public String replayAll(Long startBizSeq, Long endBizSeq) {
    // 异步执行，不阻塞主线程
}
```

---

## 🚨 告警机制

### 1. 对账差异告警

```java
if (diffCount > 0) {
    log.error("⚠️ ⚠️ ⚠️ Found {} users with balance diff!", diffCount);
    // TODO: 发送钉钉/邮件/Slack告警
}
```

### 2. 借贷不平衡告警

```java
if (SUM(debit) - SUM(credit) != 0) {
    log.error("❌ ❌ ❌ Debit-Credit imbalance!");
    // TODO: 紧急告警！立即停机！
}
```

---

## 🔧 API接口

### 查询账户快照

```
GET /internal/ledger/account/{userId}
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
  "lastLedgerSeq": 123456,
  "version": 10,
  "updatedAt": 1706169600000
}
```

### 冻结保证金

```
POST /internal/ledger/freeze

{
  "userId": 1001,
  "currency": "USDT",
  "amount": "1000.00",
  "orderId": 10001
}
```

### 解冻保证金

```
POST /internal/ledger/unfreeze

{
  "userId": 1001,
  "currency": "USDT",
  "amount": "1000.00",
  "orderId": 10001
}
```

### 触发全量Replay

```
POST /internal/ledger/replay/all

{
  "startBizSeq": 0,
  "endBizSeq": -1
}
```

### 重建单个用户

```
POST /internal/ledger/replay/user/{userId}?fromBizSeq=0
```

### 查询Replay进度

```
GET /internal/ledger/replay/progress/{replayId}
```

---

## 📝 配置文件

见 `application.yml`：

```yaml
ledger:
  default-currency: USDT
  sharding:
    db-count: 128
    table-months: 12
  replay:
    batch-size: 10000
    thread-pool-size: 4
  reconciliation:
    enabled: true
    cron: "0 0 2 * * ?"
    diff-threshold: 0.00000001
```

---

## 🧪 测试

### 1. 启动服务

```bash
cd ledger-core
mvn spring-boot:run
```

### 2. 初始化数据库

```bash
mysql -u root -p < ../sql/ledger_schema_production.sql
```

### 3. 启动Kafka

```bash
../init_kafka.sh
```

### 4. 发送测试成交

```bash
curl -X POST http://localhost:9092/trade-event \
  -H "Content-Type: application/json" \
  -d '{
    "tradeId": "T001",
    "makerUserId": 1001,
    "takerUserId": 1002,
    ...
  }'
```

---

## 📚 核心代码文件清单

### Entity（实体）
- `LedgerEntry.java` - 双录分录
- `AccountSnapshot.java` - 账户快照
- `LedgerAccount.java` - 账户主表
- `LedgerReconciliationLog.java` - 对账记录
- `LedgerReplayLog.java` - Replay记录

### Enum（枚举）
- `AccountType.java` - 账户类型
- `BusinessType.java` - 业务类型

### Mapper（持久层）
- `LedgerEntryMapper.java` - 分录查询
- `AccountSnapshotMapper.java` - 快照查询
- `LedgerAccountMapper.java` - 账户查询
- `LedgerReconciliationLogMapper.java` - 对账查询
- `LedgerReplayLogMapper.java` - Replay查询

### Service（服务层）
- `LedgerService.java` - 核心服务接口
- `LedgerServiceImpl.java` - 核心服务实现
- `LedgerReplayService.java` - Replay服务接口
- `LedgerReplayServiceImpl.java` - Replay服务实现

### Consumer（消费者）
- `TradeEventConsumer.java` - 消费成交事件

### Job（定时任务）
- `LedgerReconciliationJob.java` - 对账任务

### Controller（控制器）
- `LedgerInternalController.java` - 内部接口

### Config（配置）
- `JacksonConfig.java` - JSON序列化配置
- `MybatisPlusConfig.java` - MyBatis配置
- `SchedulingConfig.java` - 任务调度配置

---

## 🎯 总结

✅ **双录记账**：借贷必平衡，符合会计准则  
✅ **分库分表**：支持百亿级数据  
✅ **Replay机制**：可重建，灾备恢复  
✅ **实时对账**：每日自动对账，差异告警  
✅ **幂等性**：防止重复消费  
✅ **乐观锁**：并发安全  
✅ **生产级**：对标 Binance / OKX / Bybit

---

**文档版本**：v1.0  
**最后更新**：2026-01-25



