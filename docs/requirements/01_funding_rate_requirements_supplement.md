# 资金费率结算系统 - 需求补充文档

## 1. 预估资金费率实时更新机制

### 1.1 更新策略
- **更新频率**: 每5秒更新一次
- **数据来源**: 实时标记价格 + 指数价格
- **存储方式**:
  - 数据库表: `t_funding_rate_estimate`（持久化）
  - Redis缓存: `funding:estimate:{symbol}`（快速查询）
  - 缓存TTL: 10秒

### 1.2 更新流程
```
定时任务(5秒) → 获取实时价格 → 计算预估费率 → 更新DB + Redis → 推送WebSocket
```

### 1.3 缓存失效策略
- Redis不可用时降级到数据库查询
- 数据超过10秒未更新则标记为stale

---

## 2. 对账机制详细设计

### 2.1 对账维度
1. **资金对账**: Ledger记账金额 = 用户资金费用明细总和
2. **仓位对账**: 结算前后仓位数量保持不变
3. **费率对账**: 历史费率记录完整性校验

### 2.2 对账任务
```
每日02:00 UTC执行对账任务
- 检查前一日所有结算记录
- 对比Ledger与明细表数据
- 生成对账报告
- 不一致时触发告警
```

### 2.3 不一致补偿方案
- **场景1**: 用户费用未扣除 → 创建补扣任务
- **场景2**: 费用重复扣除 → 创建退款任务
- **场景3**: Ledger丢失 → 补发Ledger事件

---

## 3. 降级方案

### 3.1 指数价格服务不可用
- **降级策略**: 使用最近1分钟内的缓存价格
- **缓存来源**: Redis `index:price:{symbol}:latest`
- **超时时间**: 1分钟缓存失效后跳过本次结算
- **告警**: 立即触发P0告警通知运维

### 3.2 Ledger服务不可用
- **降级策略**: 将结算任务加入重试队列
- **重试机制**: 指数退避 (1min, 5min, 15min, 30min, 1h)
- **最大重试**: 5次
- **失败处理**: 记录到异常表，人工介入

### 3.3 Position服务不可用
- **降级策略**: 跳过本次结算，等待下一周期
- **告警**: P0告警通知

---

## 4. 分布式锁与幂等性保证

### 4.1 分布式锁设计
- **锁实现**: Redis分布式锁（Redisson）
- **锁键名**: `funding:settlement:lock:{symbol}:{fundingTime}`
- **锁超时**: 60秒
- **加锁时机**: 在`fundingSettlement()`方法开始前

### 4.2 幂等性保证
- **方案1**: 通过唯一索引防止重复插入
  - `t_funding_rate_history` 唯一索引: `uk_symbol_time`
  - `t_user_funding_fee` 唯一索引: `uk_user_symbol_time`

- **方案2**: 数据库乐观锁
  - 在配置表增加`version`字段
  - 更新时检查版本号

- **方案3**: 任务执行记录表
  ```sql
  CREATE TABLE t_funding_settlement_task (
      symbol VARCHAR(32),
      funding_time BIGINT,
      status VARCHAR(16),
      PRIMARY KEY (symbol, funding_time)
  );
  ```

---

## 5. Kafka消息格式定义

### 5.1 资金费率计算完成事件 (funding-rate-calc)
```json
{
  "eventType": "FUNDING_RATE_CALCULATED",
  "eventTime": 1704067200000,
  "data": {
    "symbol": "BTCUSDT",
    "fundingTime": 1704067200000,
    "fundingRate": 10000,        // 0.01%
    "markPrice": 5005000000000,  // $50,050
    "indexPrice": 5000000000000, // $50,000
    "premiumIndex": 9000,        // 0.009%
    "totalLongQty": 100000000000,
    "totalShortQty": 100000000000
  }
}
```

### 5.2 资金费用结算事件 (funding-settlement)
```json
{
  "eventType": "FUNDING_FEE_SETTLED",
  "eventTime": 1704067200000,
  "data": {
    "symbol": "BTCUSDT",
    "fundingTime": 1704067200000,
    "totalUsers": 5000,
    "totalSettlementAmount": 50000000000,  // $50,000
    "longPayersCount": 2500,
    "shortPayersCount": 2500
  }
}
```

### 5.3 用户资金费用结算事件 (user-funding-fee)
```json
{
  "eventType": "USER_FUNDING_FEE",
  "eventTime": 1704067200000,
  "data": {
    "userId": 10001,
    "symbol": "BTCUSDT",
    "fundingTime": 1704067200000,
    "side": "LONG",
    "positionQty": 100000000,     // 1 BTC
    "fundingRate": 10000,         // 0.01%
    "fundingFee": 500000000,      // $5
    "marginMode": "CROSS"
  }
}
```

---

## 6. 配置参数说明

### 6.1 系统配置
```yaml
funding-rate:
  # 结算任务配置
  settlement:
    enabled: true
    cron: "0 0 0,8,16 * * ?"  # 每天00:00, 08:00, 16:00 UTC
    timeout: 60000             # 结算超时时间(ms)

  # 预估费率更新
  estimate:
    enabled: true
    fixed-rate: 5000           # 每5秒更新

  # 外部服务超时
  client:
    index-price:
      url: http://index-price-service:8089
      timeout: 3000
    mark-price:
      url: http://mark-price-service:8089
      timeout: 3000
    position:
      url: http://position-service:8084
      timeout: 5000
    ledger:
      url: http://ledger-service:8086
      timeout: 5000

  # 重试配置
  retry:
    max-attempts: 5
    initial-interval: 60000     # 1分钟
    multiplier: 2.0
    max-interval: 3600000       # 1小时

  # 分布式锁
  lock:
    enabled: true
    timeout: 60000              # 锁超时(ms)
```

### 6.2 Kafka配置
```yaml
spring:
  kafka:
    producer:
      bootstrap-servers: localhost:9092
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer

funding-rate:
  kafka:
    topics:
      funding-rate-calc: funding-rate-calc
      funding-settlement: funding-settlement
      user-funding-fee: user-funding-fee
```

---

## 7. 性能优化建议

### 7.1 批量处理
- 持仓数据批量获取（每批1000条）
- 用户费用批量插入（每批500条）
- Ledger批量记账（每批200条）

### 7.2 并行处理
- 多个symbol的结算任务并行执行（线程池）
- 线程池大小: `min(CPU核数, symbol数量)`

### 7.3 缓存优化
- 指数价格缓存1分钟
- 标记价格缓存30秒
- 配置数据缓存10分钟

---

## 8. 监控指标

### 8.1 核心指标
| 指标名称 | 类型 | 说明 |
|---------|------|------|
| funding_settlement_duration | Histogram | 结算耗时分布 |
| funding_settlement_success_rate | Gauge | 结算成功率 |
| funding_settlement_user_count | Counter | 参与结算用户数 |
| funding_settlement_amount | Counter | 结算总金额 |
| funding_rate_calculation_latency | Histogram | 费率计算延迟 |
| funding_external_service_timeout | Counter | 外部服务超时次数 |

### 8.2 告警规则
| 告警名称 | 条件 | 级别 |
|---------|------|------|
| 结算延迟超时 | duration > 60s | P0 |
| 结算失败率高 | failure_rate > 1% | P0 |
| 外部服务不可用 | timeout_count > 3 | P0 |
| 费率异常波动 | abs(rate) > 0.5% | P1 |
| 对账不一致 | reconciliation_failed | P1 |

---

## 9. 测试用例

### 9.1 单元测试用例
1. 资金费率计算逻辑
   - 正常费率计算
   - 费率上下限约束
   - 溢价指数计算精度

2. 资金费用结算逻辑
   - 多头支付场景
   - 空头支付场景
   - 零持仓跳过

3. 异常处理
   - 外部服务超时
   - 数据库异常
   - 重复结算幂等性

### 9.2 集成测试用例
1. 端到端结算流程
2. 定时任务触发
3. Kafka消息发布与消费
4. 分布式锁竞争场景

---

## 10. 发布计划

### Phase 1: 核心功能（Week 1-2）
- [ ] 资金费用结算核心逻辑
- [ ] 外部服务集成
- [ ] Kafka消息发布
- [ ] Mapper XML实现

### Phase 2: 稳定性增强（Week 3）
- [ ] 分布式锁
- [ ] 异常处理与重试
- [ ] 单元测试

### Phase 3: 运维支撑（Week 4）
- [ ] 对账任务
- [ ] 监控指标
- [ ] 告警配置

### Phase 4: 性能优化（Week 5）
- [ ] 批量处理优化
- [ ] 并行处理优化
- [ ] 压力测试
