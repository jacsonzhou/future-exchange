# 架构更新总结 - 价格服务与市场服务设计

## 1. 问题澄清与决策

### Q1: index-price-core 和 mark-price-core 是否需要与资金费率合并？

**决策**: **不合并，保持分离但协同**

**理由**:
1. **单一职责原则**: 每个服务只做一件事
   - Index Price: 采集外部交易所价格，计算公允指数价格
   - Mark Price: 基于指数价格计算标记价格，EMA平滑
   - Funding Rate: 基于标记价格计算资金费率，执行结算

2. **不同的高可用要求**
   - Index Price: 可使用缓存降级（允许短暂滞后）
   - Mark Price: 必须实时可用（直接影响强平）
   - Funding Rate: 结算时间点必须精确执行

3. **不同的扩展策略**
   - Index Price: 对接更多交易所时可独立扩展
   - Mark Price: 需要更高频更新时可独立优化
   - Funding Rate: 结算时需要批量处理能力

### Q2: Market Service 的上游是谁？

**数据流**:
```
Match Engine (成交) ──► Market Service ──► WebSocket Gateway (推送)
       │                      │
       ▼                      ▼
Kafka: trade-topic    Kafka: kline-topic
                      Kafka: ticker-topic
```

**上游**:
- Match Engine (trade-topic): 实时成交数据
- Mark Price Service (mark-price-update): 标记价格
- Index Price Service (index-price-update): 指数价格

**下游**:
- WebSocket Gateway: 实时推送给客户端
- Risk Monitor: 市场统计数据

### Q3: 如何做公有推送和私有推送？

**公有推送 (Public Push)**:
- 频道: `trade@{symbol}`, `depth@{symbol}`, `kline@{symbol}`, `markPrice@{symbol}`
- 数据: 行情数据，所有用户可见
- 实现: Market Service → Kafka → WebSocket Gateway → 客户端

**私有推送 (Private Push)**:
- 频道: `executionReport`, `account`, `position`, `fundingFee`
- 数据: 用户个人数据，需认证订阅
- 实现: Ledger/OMS → Kafka → WebSocket Gateway → 特定用户

**设计文档**: 
- `WEBSOCKET_GATEWAY_DESIGN.md`: WebSocket网关完整设计
- `NOTIFICATION_SERVICE_DESIGN.md`: 通知服务完整设计

## 2. 新创建模块

### 2.1 index-price-core (Port: 8093)
**文件结构**:
```
index-price-core/
├── pom.xml
├── src/main/java/com/exchange/index/
│   ├── IndexPriceApplication.java
│   ├── controller/IndexPriceController.java
│   ├── service/
│   │   ├── IndexPriceService.java
│   │   └── impl/IndexPriceServiceImpl.java
│   ├── entity/
│   │   ├── IndexPrice.java
│   │   ├── IndexPriceComponent.java
│   │   └── IndexPriceConfig.java
│   ├── mapper/
│   │   ├── IndexPriceMapper.java
│   │   ├── IndexPriceComponentMapper.java
│   │   └── IndexPriceConfigMapper.java
│   ├── dto/IndexPriceDTO.java
│   ├── event/IndexPriceUpdateEvent.java
│   ├── producer/IndexPriceProducer.java
│   ├── component/ExternalPriceFetcher.java
│   └── job/IndexPriceCalculationJob.java
└── src/main/resources/
    ├── application.yml
    └── db/schema.sql
```

### 2.2 mark-price-core (Port: 8094)
**文件结构**:
```
mark-price-core/
├── pom.xml
├── src/main/java/com/exchange/markprice/
│   ├── MarkPriceApplication.java
│   ├── controller/MarkPriceController.java
│   ├── service/
│   │   ├── MarkPriceService.java
│   │   └── impl/MarkPriceServiceImpl.java
│   ├── entity/MarkPrice.java
│   ├── mapper/MarkPriceMapper.java
│   ├── dto/MarkPriceDTO.java
│   ├── event/MarkPriceUpdateEvent.java
│   ├── producer/MarkPriceProducer.java
│   ├── consumer/IndexPriceConsumer.java
│   └── job/MarkPriceCalculationJob.java
└── src/main/resources/
    ├── application.yml
    └── db/schema.sql
```

### 2.3 market-price-core (Port: 8095)
**文件结构**:
```
market-price-core/
├── pom.xml
├── src/main/java/com/exchange/market/
│   ├── MarketPriceApplication.java
│   ├── entity/
│   │   ├── Kline.java
│   │   └── Ticker24h.java
│   └── service/MarketDataService.java
└── src/main/resources/
    ├── application.yml
    └── db/schema.sql
```

## 3. 数据库变更

### exchange_market 数据库 (新建)

```sql
-- 指数价格相关表
CREATE TABLE t_index_price_config (...);
CREATE TABLE t_index_price (...);
CREATE TABLE t_index_price_component (...);

-- 标记价格相关表
CREATE TABLE t_mark_price (...);

-- 行情数据相关表
CREATE TABLE t_kline (...);
CREATE TABLE t_ticker_24h (...);
```

## 4. Kafka Topics 更新

### 新增 Topics

| Topic | 生产者 | 消费者 | 说明 |
|-------|--------|--------|------|
| `index-price-update` | index-price-core | mark-price-core, funding-rate-core | 指数价格更新（5秒） |
| `mark-price-update` | mark-price-core | funding-rate-core, position-core, tp-sl-core | 标记价格更新（1秒） |

### 现有 Topics 消费者更新

| Topic | 新增消费者 | 说明 |
|-------|-----------|------|
| `trade-topic` | market-price-core | 成交数据用于K线计算 |

## 5. 端口分配更新

| 服务 | 端口 | 状态 |
|------|------|------|
| index-price-core | 8093 | ✅ 新增 |
| mark-price-core | 8094 | ✅ 新增 |
| market-price-core | 8095 | ✅ 新增 |
| websocket-gateway | 8096 | 📝 设计完成 |
| notification-service | 8097 | 📝 设计完成 |

## 6. 启动顺序更新

```bash
# 1. 基础设施
mysql, redis, kafka, nacos

# 2. 价格服务（按依赖顺序）
index-price-core (8093)     # 作为价格源，最先启动
mark-price-core (8094)      # 依赖index-price
funding-rate-core (8088)    # 依赖index-price和mark-price

# 3. 核心服务
match-engine-core (8083)
ledger-core (8084)

# 4. 行情服务
market-price-core (8095)    # 依赖match-engine

# 5. 其他服务
oms-core (8081)
hard-risk-core (8082)
snapshot-account-core (8085)
position-snapshot-core (8086)
tp-sl-core (8089)
adl-core (8091)

# 6. 推送服务（待实现）
websocket-gateway (8096)
notification-service (8097)
```

## 7. 关键设计文档

### 7.1 价格服务架构
- **文件**: `docs/PRICE_SERVICE_ARCHITECTURE.md`
- **内容**: 指数价格、标记价格、资金费率服务的分离设计，数据流设计

### 7.2 WebSocket Gateway 设计
- **文件**: `docs/WEBSOCKET_GATEWAY_DESIGN.md`
- **内容**: 公有/私有推送机制，连接管理，消息协议

### 7.3 Notification Service 设计
- **文件**: `docs/NOTIFICATION_SERVICE_DESIGN.md`
- **内容**: 多渠道通知（Push/SMS/Email），限流与去重

### 7.4 价格与市场服务总结
- **文件**: `docs/PRICE_AND_MARKET_SERVICES_SUMMARY.md`
- **内容**: 新模块概览，服务间依赖，数据流

## 8. 数据闭环验证

### 8.1 价格数据闭环
```
外部交易所 → Index Price Service → Kafka → Mark Price Service → Kafka
                                                        ↓
                                              Funding Rate Service
                                                        ↓
                                              Ledger (资金费用记账)
```

### 8.2 行情数据闭环
```
Match Engine (成交) → Kafka → Market Service → Kafka → WebSocket Gateway → 客户端
                                    ↓
                              K线/深度/24h统计
```

## 9. 下一步工作

### 高优先级
1. 完成 market-price-core 模块的完整实现
2. 实现 WebSocket Gateway 模块
3. 实现 Notification Service 模块

### 中优先级
4. 完善 Kafka Consumer 在现有服务中的实现
5. 补充 Integration Tests 验证数据流
6. 添加监控指标和告警

### 低优先级
7. 性能优化（批量处理、缓存策略）
8. 压力测试
9. 灾备演练

---

*文档版本: v1.0*  
*更新日期: 2026-02-18*  
*作者: Product Manager & CTO*
