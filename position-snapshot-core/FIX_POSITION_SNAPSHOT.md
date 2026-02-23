# Position-Snapshot-Core 修复说明

## 🔴 问题描述

原实现中，`Position-Snapshot-Core` 直接消费撮合引擎发布的 `trade-event` Topic：

```java
// 错误实现
@KafkaListener(topics = "trade-event")
public void consumeTradeEvent(TradeEvent event) {
    positionService.onTrade(event);  // 直接更新持仓
}
```

### 问题影响

1. **破坏Ledger唯一事实源原则**：持仓更新应该在 Ledger 记账成功后进行
2. **数据不一致风险**：如果撮合成功但 Ledger 记账失败，持仓会错误更新
3. **与 Account-Snapshot 行为不一致**：Account-Snapshot 正确消费 `trade-entry-{symbol}`

## ✅ 修复方案

### 1. 新增消费 Ledger 账本分录的 Consumer

**新增文件**：
- `TradeEntryEvent.java` - 账本分录事件 DTO
- `TradeEntryEventConsumer.java` - 账本分录消费者

**修改文件**：
- `PositionService.java` - 添加新方法 `onTradeEntryEvent`
- `PositionServiceImpl.java` - 实现新方法
- `TradeEventConsumer.java` - 标记为弃用并禁用
- `application.yml` - 更新配置

### 2. 核心变更

```java
// 新实现 - 正确
@KafkaListener(topics = "trade-entry-BTCUSDT,trade-entry-ETHUSDT")
public void consumeTradeEntryEvent(TradeEntryEvent event) {
    // 从账本分录中提取持仓变动
    positionService.onTradeEntryEvent(event);
}
```

### 3. 数据流对比

**修复前（错误）**：
```
Match Engine → trade-event → Position-Snapshot (直接更新)
                    ↓
Ledger-Core (可能失败，但持仓已更新)
```

**修复后（正确）**：
```
Match Engine → trade-event → Ledger-Core (记账成功)
                                   ↓
                         trade-entry-{symbol}
                                   ↓
                    Position-Snapshot (正确更新)
```

## 📋 详细变更

### 新增 DTO

```java
// TradeEntryEvent.java
@Data
public class TradeEntryEvent {
    private String tradeId;
    private Long sequence;
    private String symbol;
    private List<LedgerEntry> entries;  // 账本分录
    private Long eventTime;
    private Long bizSeq;
}
```

### 新增 Consumer

```java
// TradeEntryEventConsumer.java
@Component
public class TradeEntryEventConsumer {
    
    @KafkaListener(
        topics = "#{@tradeEntryEventConsumer.getTradeEntryTopics()}",
        groupId = "position-service",
        concurrency = "1"
    )
    public void consumeTradeEntryEvent(TradeEntryEvent event) {
        // 从分录中提取持仓变动并更新
        positionService.onTradeEntryEvent(event);
    }
}
```

### Service 新方法

```java
// PositionService.java
void onTradeEntryEvent(TradeEntryEvent event);

// PositionServiceImpl.java
@Override
@Transactional
public void onTradeEntryEvent(TradeEntryEvent event) {
    // 1. 从分录中提取持仓变动（POSITION_ASSET类型）
    // 2. 从分录中提取已实现盈亏（REALIZED_PNL类型）
    // 3. 更新持仓快照
    // 4. 更新Redis缓存
    // 5. 发布风险事件
}
```

### 配置更新

```yaml
# application.yml
position:
  kafka:
    # 修复：改为消费 Ledger 发布的账本分录
    trade-entry-symbols: BTCUSDT,ETHUSDT
```

## 🔄 迁移步骤

### 1. 部署新版本

部署修复后的 `position-snapshot-core`：

```bash
# 编译打包
mvn clean package -DskipTests

# 部署
java -jar position-snapshot-core-1.0.0-SNAPSHOT.jar
```

### 2. 验证消费

检查 Kafka 消费者组：

```bash
# 查看消费者组
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group position-service

# 应该看到消费的是 trade-entry-BTCUSDT 和 trade-entry-ETHUSDT
```

### 3. 监控数据一致性

对比 Ledger 和 Position-Snapshot 的数据：

```sql
-- 查询 Ledger 中的持仓变动
SELECT * FROM t_ledger_entry 
WHERE business_type = 'POSITION_ASSET' 
ORDER BY created_at DESC LIMIT 10;

-- 查询 Position-Snapshot 中的持仓
SELECT * FROM t_position_snapshot 
ORDER BY updated_at DESC LIMIT 10;
```

### 4. 清理旧消费者（可选）

确认新消费者工作正常后，可以删除 `TradeEventConsumer` 类：

```bash
# 停止旧消费者组
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --delete --group position-service-deprecated
```

## ⚠️ 注意事项

1. **价格信息获取**：
   - 账本分录中可能不包含成交价格
   - 需要从 `refTradeId` 查询或从其他方式获取
   - 当前实现使用简化逻辑，生产环境需要完善

2. **已实现盈亏处理**：
   - 从 `REALIZED_PNL` 类型的分录中提取
   - 正确更新持仓的 `realizedPnl` 字段

3. **幂等性保证**：
   - 使用 `bizSeq` 保证幂等
   - 数据库使用乐观锁防止并发问题

## 📊 验证清单

- [ ] 新消费者正常启动
- [ ] 消费 `trade-entry-{symbol}` Topic
- [ ] 持仓更新与 Ledger 一致
- [ ] 已实现盈亏正确计算
- [ ] Redis 缓存正确更新
- [ ] 风险事件正确发布
- [ ] 旧消费者已禁用

## 🔗 相关文档

- [Ledger-Core 设计文档](../ledger-core/README.md)
- [Kafka Topic 设计](../docs/KAFKA_DUAL_CHANNEL_ARCHITECTURE.md)
- [双录分录说明](../docs/LEDGER_SNAPSHOT_DECOUPLED_ARCHITECTURE.md)
