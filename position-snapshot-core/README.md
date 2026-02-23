# Position Snapshot Core

> **持仓快照服务** | Position Snapshot Service  
> Maintains position state, calculates PnL and risk metrics

---

## 🎯 核心定位

Position Snapshot Core 是**持仓状态的唯一快照服务**，负责：

1. ✅ **持仓状态维护**：实时更新用户持仓
2. ✅ **未实现盈亏计算**：基于标记价格计算浮动盈亏
3. ✅ **保证金比率计算**：计算维持保证金率
4. ✅ **强平价格计算**：计算触发强制平仓的价格
5. ✅ **风险事件生成**：输出RiskEvent供风控系统
6. ✅ **高性能查询**：Redis缓存毫秒级查询

---

## 📊 架构定位

```
Match Engine
    ↓ Kafka: trade-event
Position Snapshot Service
    ├─ 消费 TradeEvent
    ├─ 消费 MarkPriceEvent
    ├─ 计算 PnL / Margin
    ├─ 发布 RiskEvent
    ├─ 更新 MySQL
    └─ 更新 Redis
         ↓
Risk Gate / API / UI
```

---

## 🔥 核心功能

### 1. 持仓状态维护

```
开仓/加仓：
  size += quantity
  entryPrice = 加权平均

平仓/减仓：
  realizedPnl = (exitPrice - entryPrice) * closedSize
  size -= closedSize
```

### 2. 未实现盈亏计算

```
多头：unrealizedPnl = (markPrice - entryPrice) * size
空头：unrealizedPnl = (entryPrice - markPrice) * abs(size)
```

### 3. 保证金比率计算

```
marginRatio = equity / maintenanceMargin

强平条件：marginRatio < 1.0
```

### 4. 强平价格计算

```
多头：liquidationPrice = entryPrice - (equity - maintenanceMargin) / size
空头：liquidationPrice = entryPrice + (equity - maintenanceMargin) / abs(size)
```

---

## 📡 输入数据

### 1. TradeEvent（成交事件）

**Topic**: `trade-event`  
**Key**: symbol

```json
{
  "tradeId": "T001",
  "symbol": "BTCUSDT",
  "makerUserId": 1001,
  "takerUserId": 1002,
  "price": "50000.00",
  "quantity": "1.0"
}
```

### 2. MarkPriceEvent（标记价格）

**Topic**: `mark-price-{symbol}`  
**Key**: symbol

```json
{
  "markPriceId": "MP001",
  "symbol": "BTCUSDT",
  "markPrice": "50100.00"
}
```

---

## 📤 输出数据

### RiskEvent（风险事件）

**Topic**: `risk-event-topic`

```json
{
  "riskEventId": "R001",
  "userId": 1001,
  "symbol": "BTCUSDT",
  "eventType": "MARGIN_WARNING",
  "marginRatio": "1.05",
  "liquidationPrice": "48000.00"
}
```

**事件类型**：
- `MARGIN_WARNING`: 保证金率 < 1.2
- `LIQUIDATION_ALERT`: 保证金率 < 1.0

---

## 🗄️ 数据表

### position_snapshot

```sql
CREATE TABLE position_snapshot (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  size DECIMAL(32,16) NOT NULL,
  entry_price DECIMAL(32,16) NOT NULL,
  unrealized_pnl DECIMAL(32,16) NOT NULL,
  realized_pnl DECIMAL(32,16) NOT NULL,
  margin_ratio DECIMAL(10,4),
  liquidation_price DECIMAL(32,16),
  ...
  UNIQUE KEY uk_user_symbol (user_id, symbol)
) ENGINE=InnoDB;
```

### Redis缓存

```
Key: position:{userId}:{symbol}
Type: Hash

Fields:
  size, entryPrice, unrealizedPnl, 
  marginRatio, liquidationPrice
```

---

## 🚀 启动

```bash
cd position-snapshot-core
mvn spring-boot:run
```

**端口**：8086  
**数据库**：exchange_position  
**Redis**：database 2

---

## 📚 API接口

### 查询持仓

```bash
GET /internal/position/{userId}/{symbol}
```

### 查询所有持仓

```bash
GET /internal/position/{userId}/all
```

---

## ✅ 核心特性

| 特性 | 说明 |
|------|------|
| **实时计算** | TradeEvent触发持仓更新 |
| **动态估值** | MarkPrice触发PnL重算 |
| **风险事件** | 自动发布RiskEvent |
| **Redis缓存** | 毫秒级查询 |
| **幂等性** | tradeId/markPriceId去重 |
| **Replay** | 支持灾备重建 |

---

## 📝 相关文档

- [REQUIREMENTS.md](REQUIREMENTS.md) - 需求文档

---

**对标**：Binance / OKX / Bybit级别

