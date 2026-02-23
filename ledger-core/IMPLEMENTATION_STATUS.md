# 🔥 Ledger Core 生产级实现文档

## 已完成模块

### 1. ✅ SQL表结构（生产级）
- `sql/ledger_schema_production.sql`
- 分库分表设计（user_id % 128 + 按月分表）
- 全局序列号表
- 对账和Replay表

### 2. ✅ 核心实体类
- `LedgerEntry`：双录分录（含biz_seq、幂等键、成对分录）
- `AccountSnapshot`：账户快照（含权益、版本号、校验哈希）
- `LedgerAccount`：账户主表
- `LedgerReconciliationLog`：对账记录
- `LedgerReplayLog`：Replay记录

### 3. ✅ 枚举类
- `AccountType`：账户类型（用户账户+系统账户）
- `BusinessType`：业务类型（成交、Funding、强平等）

---

## 待实现模块

### 1. Mapper层
- `LedgerEntryMapper`
- `AccountSnapshotMapper`
- `LedgerAccountMapper`
- `LedgerReconciliationLogMapper`
- `LedgerReplayLogMapper`

### 2. Service核心服务
- `LedgerService`：核心业务逻辑
  - `applyTrade(Trade trade)`：应用成交
  - `applyFunding(FundingEvent)`：应用Funding
  - `applyLiquidation(LiquidationEvent)`：应用强平

### 3. Kafka消费者
- `TradeEventConsumer`：消费trade-event
- 写入LedgerEntry
- 更新AccountSnapshot

### 4. 对账Job
- `ReconciliationJob`：每日对账
- 对比LedgerEntry vs AccountSnapshot

### 5. Replay机制
- `ReplayService`：Replay服务
- 从LedgerEntry重建AccountSnapshot

---

## 核心设计原则

### 1. 真相源分层
```
TradeEvent (撮合事实)
    ↓
LedgerEntry (双录账本事实) ← 唯一资金真相
    ↓
AccountSnapshot (账户视图) ← 供API/风控查询
```

### 2. 双录记账
- 每笔业务生成2条分录（借+贷）
- SUM(debit) = SUM(credit)
- 借方=资产增加，贷方=资产减少

### 3. 分库分表
- 分库：user_id % 128
- 分表：ledger_entry_YYYYMM
- 全局序列：biz_seq保证Replay顺序

### 4. 幂等性
- idempotent_key：TRADE:tradeId:userId:accountType
- 防止Kafka重复消费

### 5. Replay机制
```sql
SELECT * FROM ledger_entry_YYYYMM 
WHERE biz_seq > ? 
ORDER BY biz_seq ASC;
```

### 6. 对账系统
```sql
SUM(ledger_entry.debit - credit) 
= 
account_snapshot.available + frozen + position_margin
```

---

## 核心流程

### 1. 成交处理流程
```
Match Engine生成TradeEvent
    ↓
Kafka: trade-event
    ↓
LedgerService.applyTrade()
    ↓
生成2条LedgerEntry（买方+卖方）
    ↓
更新AccountSnapshot（available, position_margin）
    ↓
计算equity（风控）
```

### 2. 双录分录示例

**用户A买入 0.1 BTC @ 43000**

| 用户 | 账户类型 | debit | credit | 说明 |
|------|---------|-------|--------|------|
| A | USER_AVAILABLE | 0 | 4300 | 可用减少 |
| A | USER_POSITION_MARGIN | 4300 | 0 | 持仓增加 |

**用户B卖出（对手方）**

| 用户 | 账户类型 | debit | credit | 说明 |
|------|---------|-------|--------|------|
| B | USER_POSITION_MARGIN | 0 | 4300 | 持仓减少 |
| B | USER_AVAILABLE | 4300 | 0 | 可用增加 |

**验证**：
```
总借方 = 4300 + 4300 = 8600
总贷方 = 4300 + 4300 = 8600
✅ 借贷平衡！
```

---

## 性能指标

| 指标 | 目标值 |
|------|--------|
| Ledger写入QPS | 10万+ |
| AccountSnapshot查询延迟 | < 1ms |
| Replay速度 | 10万条/秒 |
| 对账时间 | < 5分钟 |
| 分库数量 | 128个 |
| 单表数据量 | < 5000万行 |

---

## 监控告警

### 1. 借贷平衡检查
```sql
SELECT SUM(debit) - SUM(credit) AS balance_check
FROM ledger_entry_202601;
-- 结果必须为0！
```

### 2. 同步延迟监控
```sql
SELECT 
  user_id,
  MAX(biz_seq) - last_ledger_seq AS lag
FROM account_snapshot
WHERE lag > 1000;
```

### 3. 对账差异告警
```sql
SELECT COUNT(*) 
FROM ledger_reconciliation_log
WHERE status = 1 AND check_date = CURDATE();
```

---

## 下一步实现计划

1. ✅ SQL表结构
2. ✅ 实体类和枚举
3. ⏳ Mapper层（继续实现）
4. ⏳ Service核心服务
5. ⏳ Kafka消费者
6. ⏳ 对账Job
7. ⏳ Replay机制
8. ⏳ 配置文件和文档

---

**这就是Binance/OKX/Bybit级别的Ledger架构！** 🚀

