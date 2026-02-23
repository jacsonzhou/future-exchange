# Position Snapshot Service - 持仓快照服务需求文档

## 🎯 服务定位

Position Snapshot Service 是合约交易系统中**持仓状态的唯一快照服务**，负责：

1. ✅ **持仓状态维护**：实时更新用户持仓
2. ✅ **未实现盈亏计算**：基于标记价格计算浮动盈亏
3. ✅ **保证金比率计算**：计算维持保证金率，判断是否触发强平
4. ✅ **强平价格计算**：计算触发强制平仓的价格
5. ✅ **风险事件生成**：输出风险事件供风控系统使用
6. ✅ **高性能查询**：Redis缓存支持毫秒级查询

---

## 📊 架构定位

```
Match Engine (撮合)
    ↓ Kafka: trade-event
┌────────────────────────────┐
│ Position Snapshot Service  │
│ (本服务)                    │
│ ┌────────────────────────┐ │
│ │ 1. 消费 TradeEvent     │ │
│ │ 2. 更新持仓状态        │ │
│ │ 3. 消费 MarkPrice      │ │
│ │ 4. 计算 PnL / Margin   │ │
│ │ 5. 发布 RiskEvent      │ │
│ │ 6. 更新 Redis          │ │
│ └────────────────────────┘ │
└────────┬───────────────────┘
         │
         ├─→ position_snapshot (MySQL)
         ├─→ Redis Cache
         └─→ Kafka: risk-event-topic
                ↓
         Hard Risk / Soft Risk / UI
```

---

## 🔥 核心职责

### 1. 持仓状态维护

#### 开仓/加仓
```
买入多头：
  size += quantity
  entryPrice = (entryPrice * oldSize + price * quantity) / newSize

卖出空头：
  size -= quantity (负数表示空头)
  entryPrice = 加权平均
```

#### 减仓/平仓
```
平多头：
  realizedPnl = (exitPrice - entryPrice) * closedSize
  size -= closedSize

平空头：
  realizedPnl = (entryPrice - exitPrice) * closedSize
  size += closedSize (绝对值减少)
```

### 2. 未实现盈亏计算

```
多头：
  unrealizedPnl = (markPrice - entryPrice) * size

空头：
  unrealizedPnl = (entryPrice - markPrice) * abs(size)
```

### 3. 保证金比率计算

```
marginRatio = equity / maintenanceMargin

其中：
  equity = available + positionValue + unrealizedPnl
  maintenanceMargin = abs(size) * markPrice * maintenanceMarginRate

强平条件：
  marginRatio < 1.0 → 触发强制平仓
```

### 4. 强平价格计算

```
多头强平价：
  liquidationPrice = entryPrice - (equity - maintenanceMargin) / size

空头强平价：
  liquidationPrice = entryPrice + (equity - maintenanceMargin) / abs(size)
```

---

## 📡 输入数据源

### 1. TradeEvent（成交事件）

**Topic**: `trade-event`  
**Partition**: 1（单Symbol单线程）  
**Key**: symbol  
**幂等键**: tradeId

```json
{
  "tradeId": "T001",
  "symbol": "BTCUSDT",
  "makerUserId": 1001,
  "takerUserId": 1002,
  "price": "50000.00",
  "quantity": "1.0",
  "isMakerBuy": true,
  "tradeTime": 1706169600000
}
```

### 2. MarkPriceEvent（标记价格事件）

**Topic**: `mark-price-topic`  
**Partition**: 1（单Symbol单线程）  
**Key**: symbol  
**幂等键**: markPriceId

```json
{
  "markPriceId": "MP001",
  "symbol": "BTCUSDT",
  "markPrice": "50100.00",
  "indexPrice": "50095.00",
  "fundingRate": "0.0001",
  "timestamp": 1706169600000
}
```

---

## 📤 输出数据

### 1. RiskEvent（风险事件）

**Topic**: `risk-event-topic`  
**Partition**: 1（单Symbol单线程）  
**Key**: symbol

```json
{
  "riskEventId": "R001",
  "userId": 1001,
  "symbol": "BTCUSDT",
  "eventType": "MARGIN_WARNING",  // MARGIN_WARNING / LIQUIDATION_ALERT
  "marginRatio": "1.05",
  "liquidationPrice": "48000.00",
  "unrealizedPnl": "-1000.00",
  "timestamp": 1706169600000
}
```

**事件类型**：
- `MARGIN_WARNING`: 保证金率低于1.2（预警）
- `LIQUIDATION_ALERT`: 保证金率低于1.0（触发强平）

---

## 🗄️ 数据存储

### 1. position_snapshot（MySQL）

```sql
CREATE TABLE position_snapshot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL COMMENT '用户ID',
  symbol VARCHAR(32) NOT NULL COMMENT '交易对',
  
  -- 持仓信息
  size DECIMAL(32,16) NOT NULL COMMENT '持仓数量（正=多头，负=空头）',
  entry_price DECIMAL(32,16) NOT NULL COMMENT '持仓均价',
  
  -- 盈亏信息
  unrealized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '未实现盈亏',
  realized_pnl DECIMAL(32,16) NOT NULL DEFAULT 0 COMMENT '已实现盈亏',
  
  -- 风控指标
  margin_ratio DECIMAL(10,4) COMMENT '保证金率',
  liquidation_price DECIMAL(32,16) COMMENT '强平价',
  
  -- 同步位点
  last_trade_id VARCHAR(64) COMMENT '最后处理的tradeId',
  last_mark_price_id VARCHAR(64) COMMENT '最后处理的markPriceId',
  last_update_seq BIGINT NOT NULL DEFAULT 0 COMMENT '最后更新序列号',
  
  -- 并发控制
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  
  -- 时间戳
  created_at BIGINT NOT NULL COMMENT '创建时间',
  updated_at BIGINT NOT NULL COMMENT '更新时间',
  
  UNIQUE KEY uk_user_symbol (user_id, symbol),
  KEY idx_last_update_seq (last_update_seq)
) ENGINE=InnoDB COMMENT='持仓快照表';
```

### 2. Redis缓存结构

```
Key: position:{userId}:{symbol}
Type: Hash

Fields:
  size: "1.5"
  entryPrice: "50000.00"
  unrealizedPnl: "150.00"
  realizedPnl: "500.00"
  marginRatio: "2.5000"
  liquidationPrice: "48000.00"
  lastUpdateSeq: "123456"
  updatedAt: "1706169600000"

TTL: 24小时
```

---

## 🔧 Java接口设计

### PositionService

```java
public interface PositionService {
    
    /**
     * 消费TradeEvent更新持仓
     */
    void onTrade(Trade trade);
    
    /**
     * 消费MarkPriceEvent更新估值
     */
    void onMarkPrice(MarkPrice markPrice);
    
    /**
     * 查询用户持仓快照
     */
    PositionSnapshot queryPosition(Long userId, String symbol);
    
    /**
     * 查询用户所有持仓
     */
    List<PositionSnapshot> queryAllPositions(Long userId);
    
    /**
     * 计算未实现盈亏
     */
    BigDecimal calculateUnrealizedPnl(PositionSnapshot position, BigDecimal markPrice);
    
    /**
     * 计算保证金比率
     */
    BigDecimal calculateMarginRatio(PositionSnapshot position, AccountSnapshot account);
    
    /**
     * 计算强平价
     */
    BigDecimal calculateLiquidationPrice(PositionSnapshot position, AccountSnapshot account);
}
```

### PositionReplayService

```java
public interface PositionReplayService {
    
    /**
     * Replay历史持仓事件
     */
    String replaySymbol(String symbol);
    
    /**
     * 按时间区间Replay
     */
    String replayRange(String symbol, Long startTs, Long endTs);
    
    /**
     * 查询Replay进度
     */
    Integer getReplayProgress(String replayId);
}
```

---

## 📊 持仓快照类设计

### PositionSnapshot

```java
@Data
public class PositionSnapshot {
    Long id;
    Long userId;
    String symbol;
    
    // 持仓信息
    BigDecimal size;           // 持仓数量（正=多头，负=空头）
    BigDecimal entryPrice;     // 持仓均价
    
    // 盈亏信息
    BigDecimal unrealizedPnl;  // 未实现盈亏
    BigDecimal realizedPnl;    // 已实现盈亏
    
    // 风控指标
    BigDecimal marginRatio;    // 保证金率
    BigDecimal liquidationPrice; // 强平价
    
    // 同步位点
    String lastTradeId;
    String lastMarkPriceId;
    Long lastUpdateSeq;
    
    // 并发控制
    Integer version;
    
    // 时间戳
    Long createdAt;
    Long updatedAt;
}
```

---

## ⚡ 性能设计

| 指标 | 目标 | 实现方式 |
|------|------|---------|
| **查询延迟** | <1ms | Redis缓存 |
| **更新延迟** | <10ms | 异步Kafka消费 |
| **并发安全** | 无冲突 | 乐观锁 + 单线程消费 |
| **可用性** | 99.99% | MySQL持久化 + Redis缓存 |
| **灾备** | 支持Replay | Kafka事件回放 |

---

## 🔄 Replay机制

### Replay流程

1. 从Kafka earliest开始消费
2. 顺序处理TradeEvent和MarkPriceEvent
3. 重建position_snapshot表
4. 更新Redis缓存
5. 重新生成RiskEvent

### 用途

- ✅ 灾备恢复
- ✅ 数据一致性校验
- ✅ 新增风控指标回测

---

## 🚨 风控读取模型

```java
// Hard Risk Gate 读取
public boolean checkPosition(Long userId, String symbol) {
    // 1. 读Redis（毫秒级）
    PositionSnapshot position = positionService.queryPosition(userId, symbol);
    
    // 2. 检查保证金率
    if (position.getMarginRatio() != null 
        && position.getMarginRatio().compareTo(new BigDecimal("1.0")) < 0) {
        return false; // 触发强平
    }
    
    return true;
}
```

---

## ✅ 核心特性

| 特性 | 说明 |
|------|------|
| **解耦** | 独立服务，通过Kafka通信 |
| **实时计算** | TradeEvent触发持仓更新 |
| **动态估值** | MarkPrice触发PnL重算 |
| **风险事件** | 自动发布RiskEvent |
| **Redis缓存** | 毫秒级查询 |
| **幂等性** | tradeId/markPriceId去重 |
| **Replay** | 支持灾备重建 |

---

## 📚 对标一线交易所

✅ **Binance / OKX / Bybit** 特点：
- 持仓快照独立服务
- 实时计算未实现盈亏
- 动态保证金率计算
- 自动触发风控事件
- Redis提供高性能查询
- 支持灾备Replay

---

**文档版本**：v1.0  
**最后更新**：2026-01-25  
**状态**：需求文档完成，待实现

