# 价格服务与市场服务 - 实现总结

## 1. 新创建模块概览

### 1.1 Index Price Core (指数价格服务)
- **端口**: 8093
- **数据库**: exchange_market
- **核心功能**: 
  - 从多个外部交易所采集现货价格
  - 计算加权平均指数价格
  - 异常值过滤
  - 发布 `index-price-update` 事件

### 1.2 Mark Price Core (标记价格服务)  
- **端口**: 8094
- **数据库**: exchange_market
- **核心功能**:
  - 基于指数价格和OrderBook计算公允价格
  - EMA平滑处理（防止价格剧烈波动）
  - 消费 `index-price-update` 事件
  - 发布 `mark-price-update` 事件

### 1.3 Market Price Core (行情服务)
- **端口**: 8095  
- **数据库**: exchange_market
- **核心功能**:
  - K线数据计算与存储
  - OrderBook深度聚合
  - 24小时统计数据
  - 为WebSocket Gateway提供数据

## 2. 服务间依赖关系

```
外部交易所API (Binance/OKX/Coinbase)
         │
         ▼
┌─────────────────────┐
│ Index Price Service │◄─── 5秒更新
│     (Port: 8093)    │
└──────────┬──────────┘
           │ index-price-update (Kafka)
           ▼
┌─────────────────────┐
│  Mark Price Service │◄─── 1秒更新，EMA平滑
│     (Port: 8094)    │
└──────────┬──────────┘
           │ mark-price-update (Kafka)
           ▼
┌──────────────────────────────────────────────────────────┐
│                      Consumers                            │
├──────────────────────────────────────────────────────────┤
│  Funding Rate Service  │  资金费率计算                    │
│  Position Service      │  强平价格计算、未实现盈亏         │
│  TP/SL Service         │  止盈止损触发判断                │
│  Risk Monitor          │  风险评估                       │
│  WebSocket Gateway     │  实时推送                       │
└──────────────────────────────────────────────────────────┘
```

## 3. 数据库表结构

### exchange_market 数据库

```sql
-- 指数价格相关表
CREATE TABLE t_index_price_config (...);
CREATE TABLE t_index_price (...);
CREATE TABLE t_index_price_component (...);

-- 标记价格相关表  
CREATE TABLE t_mark_price (...);

-- 行情数据相关表
CREATE TABLE t_kline (...);
CREATE TABLE t_orderbook_snapshot (...);
CREATE TABLE t_ticker_24h (...);
```

## 4. Kafka Topics

### 价格相关 Topics

| Topic | 生产者 | 消费者 | 更新频率 | 保留时间 |
|-------|--------|--------|---------|---------|
| `index-price-update` | index-price-core | mark-price-core, funding-rate-core | 5秒 | 1天 |
| `mark-price-update` | mark-price-core | funding-rate-core, position-core, tp-sl-core | 1秒 | 1天 |

### 行情相关 Topics

| Topic | 生产者 | 消费者 | 说明 |
|-------|--------|--------|------|
| `trade-topic` | match-engine-core | market-price-core | 成交数据 |
| `orderbook-snapshot-topic` | match-engine-core | market-price-core | OrderBook深度 |
| `kline-topic` | market-price-core | websocket-gateway | K线数据 |
| `ticker-topic` | market-price-core | websocket-gateway | 24小时统计 |

## 5. 关键设计决策

### 5.1 为什么指数价格和标记价格要分离？

1. **不同的高可用要求**
   - 指数价格：可使用缓存降级（上次有效价格）
   - 标记价格：必须实时可用（影响强平）

2. **不同的更新频率**
   - 指数价格：5秒更新（外部API限制）
   - 标记价格：1秒更新（平滑处理）

3. **单一职责原则**
   - 每个服务只做一件事，便于独立优化

### 5.2 标记价格的EMA平滑

```java
// EMA公式: EMA_today = α * Price_today + (1-α) * EMA_yesterday
// α = 2/(N+1), N=60 (约60个时间单位)
// 用于防止标记价格剧烈波动导致误强平
```

### 5.3 指数价格的多源加权

```
Binance: 40% (流动性最高)
OKX: 35%
Coinbase: 25%

剔除偏离中位数超过5%的价格
至少需要2个有效成分才能计算
```

## 6. 端口分配更新

| 服务 | 端口 | 状态 |
|------|------|------|
| api-gateway | 8080 | 已存在 |
| oms-core | 8081 | 已存在 |
| hard-risk-core | 8082 | 已存在 |
| match-engine-core | 8083 | 已存在 |
| ledger-core | 8084 | 已存在 |
| snapshot-account-core | 8085 | 已存在 |
| position-snapshot-core | 8086 | 已存在 |
| replay-core | 8087 | 已存在 |
| funding-rate-core | 8088 | 已存在 |
| tp-sl-core | 8089 | 已存在 |
| margin-mode-core | 8090 | 已存在 |
| adl-core | 8091 | 已存在 |
| market-maker-core | 8092 | 已存在 |
| **index-price-core** | **8093** | **新增** |
| **mark-price-core** | **8094** | **新增** |
| **market-price-core** | **8095** | **新增** |
| **websocket-gateway** | **8096** | 待实现 |
| **notification-service** | **8097** | 待实现 |

## 7. 待实现模块

### 7.1 WebSocket Gateway (Port: 8096)
- 百万级并发WebSocket连接管理
- 公有推送（行情数据）
- 私有推送（用户数据）
- 心跳保活机制

### 7.2 Notification Service (Port: 8097)
- 站内信 (In-App)
- App Push (Firebase/APNS)
- 短信 (Twilio)
- 邮件 (SendGrid)

## 8. 启动顺序

```bash
# 1. 基础设施
mysql, redis, kafka, nacos

# 2. 核心服务
match-engine-core (8083)
ledger-core (8084)

# 3. 价格服务（按顺序）
index-price-core (8093)   # 先启动，作为价格源
mark-price-core (8094)    # 依赖index-price
funding-rate-core (8088)  # 依赖index-price和mark-price

# 4. 其他服务
oms-core (8081)
hard-risk-core (8082)
snapshot-account-core (8085)
position-snapshot-core (8086)
tp-sl-core (8089)
adl-core (8091)

# 5. 待实现
market-price-core (8095)
websocket-gateway (8096)
notification-service (8097)
```

---

*文档版本: v1.0*  
*更新日期: 2026-02-18*
