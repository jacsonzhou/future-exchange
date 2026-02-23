# Liquidation-Core 快速验证指南

> 1天紧急修复后的快速验证步骤

---

## 🚀 快速开始（5分钟）

### 1. 数据库初始化

```bash
# 进入项目目录
cd /Users/zhoufan/project/future-exchange/liquidation-core

# 执行数据库迁移
mysql -h localhost -u root -p123456 exchange_liquidation < src/main/resources/db/schema.sql

# 验证表结构
mysql -h localhost -u root -p123456 exchange_liquidation -e "SHOW TABLES;"
```

**预期输出**:
```
+-------------------------------------+
| Tables_in_exchange_liquidation     |
+-------------------------------------+
| t_liquidation_audit                 |
| t_liquidation_event                 |
| t_liquidation_execution             |
+-------------------------------------+
```

### 2. 配置环境变量

```bash
# 创建env文件
cat > .env <<EOF
# 数据库配置
DB_HOST=localhost
DB_PORT=3306
DB_NAME=exchange_liquidation
DB_USERNAME=root
DB_PASSWORD=123456

# Redis配置
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DB=0

# Kafka配置
KAFKA_SERVERS=localhost:9092
EOF

# 加载环境变量
export $(cat .env | xargs)
```

### 3. 编译打包

```bash
# 编译（跳过测试，加快速度）
mvn clean package -DskipTests

# 验证jar包
ls -lh target/liquidation-core-*.jar
```

### 4. 启动服务

```bash
# 启动
java -jar target/liquidation-core-1.0.0-SNAPSHOT.jar

# 等待日志显示: "Started LiquidationApplication in X seconds"
```

---

## ✅ 验证修复效果（10分钟）

### 验证1: 数据库字段检查

```sql
-- 检查version列（乐观锁）
DESC t_liquidation_execution;
-- 应该看到: version BIGINT DEFAULT 0

-- 检查审计表
DESC t_liquidation_audit;
-- 应该看到完整字段

-- 检查事件表
DESC t_liquidation_event;
-- 应该看到完整字段
```

**预期**: 所有新字段都存在 ✅

### 验证2: 配置检查

```bash
# 检查是否使用环境变量
grep '\${' src/main/resources/application.yml

# 应该看到：
# password: ${DB_PASSWORD:123456}
# host: ${REDIS_HOST:localhost}
# bootstrap-servers: ${KAFKA_SERVERS:localhost:9092}
```

**预期**: 所有敏感配置都使用环境变量 ✅

### 验证3: 代码检查

```bash
# 检查消息丢失修复
grep -n "throw new RuntimeException" src/main/java/com/exchange/liquidation/consumer/LiquidationTriggerConsumer.java

# 应该看到：throw new RuntimeException("Failed, will retry", e);
```

**预期**: 异常会抛出，不会被吞掉 ✅

### 验证4: Redis监控存储

```bash
# 启动服务后，模拟订单监控
redis-cli

# 查看监控key
KEYS liquidation:monitor:*

# 检查TTL
TTL liquidation:monitor:order:123456
# 应该返回剩余秒数（最多3600秒）
```

**预期**: 监控状态存储在Redis，有TTL ✅

### 验证5: 本地事件表

```sql
-- 模拟发送事件失败，检查本地事件表
SELECT * FROM t_liquidation_event
WHERE send_status = 'PENDING'
ORDER BY created_at DESC
LIMIT 5;

-- 检查重试机制
SELECT
    event_id,
    liquidation_id,
    retry_count,
    max_retry,
    next_retry_time,
    FROM_UNIXTIME(next_retry_time/1000) as next_retry_datetime
FROM t_liquidation_event
WHERE send_status = 'PENDING';
```

**预期**: 失败事件会记录，有重试计划 ✅

### 验证6: 审计日志

```sql
-- 检查审计日志记录
SELECT
    operation,
    operator_type,
    reason,
    success,
    FROM_UNIXTIME(created_at/1000) as created_datetime
FROM t_liquidation_audit
ORDER BY created_at DESC
LIMIT 10;
```

**预期**: 所有操作都有审计记录 ✅

---

## 🧪 功能测试（15分钟）

### 测试1: 幂等性测试

```bash
# 发送相同的强平触发事件（模拟重复）
# 第一次
curl -X POST http://localhost:8088/test/trigger-liquidation \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 12345,
    "positionId": 67890,
    "symbol": "BTCUSDT"
  }'

# 第二次（重复）
curl -X POST http://localhost:8088/test/trigger-liquidation \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 12345,
    "positionId": 67890,
    "symbol": "BTCUSDT"
  }'

# 检查日志
tail -f logs/liquidation-service.log | grep "Duplicate"
```

**预期日志**:
```
⚠️ [LiquidationService] Duplicate liquidation found in DB, positionId=67890
```

**验证**: 第二次请求被拒绝 ✅

### 测试2: 大仓位盈亏计算

```sql
-- 插入测试数据（大仓位）
INSERT INTO t_liquidation_execution (
    liquidation_id, user_id, position_id, symbol,
    margin_mode, position_side, trigger_type, trigger_price,
    order_type, side, quantity,
    entry_price, executed_price, executed_qty,
    bankruptcy_price, initial_margin,
    status, triggered_at, created_at, updated_at, version
) VALUES (
    'LIQ_TEST_LARGE', 12345, 99999, 'BTCUSDT',
    'ISOLATED', 1, 'MARGIN_RATIO', 5000000000000,
    'MARKET', 'SELL', 100000000000, -- 1000 BTC
    5000000000000, -- 入场价: 50000
    4900000000000, -- 成交价: 49000
    100000000000,  -- 成交量: 1000 BTC
    4400000000000, 500000000000,
    'FILLED', 1704067200000, 1704067200000, 1704067200000, 0
);

-- 触发盈亏计算（通过Java服务）
-- 检查是否溢出
SELECT realized_pnl FROM t_liquidation_execution WHERE liquidation_id = 'LIQ_TEST_LARGE';
```

**预期**: 盈亏计算正确，无溢出 ✅

**计算验证**:
- 价格差: 50000 - 49000 = 1000 USDT
- 数量: 1000 BTC
- 预期盈亏: -1000 × 1000 = -1,000,000 USDT (亏损)
- long表示: -100000000000000 (8位小数)

### 测试3: 乐观锁并发

```java
// 使用JMeter或ab压测工具
// 并发10个线程，同时更新同一条记录

// 预期：只有1个成功，其他9个失败（version不匹配）
```

**验证SQL**:
```sql
SELECT version FROM t_liquidation_execution WHERE id = 1;
-- version应该是1（只更新成功1次）
```

### 测试4: Redis故障降级

```bash
# 1. 停止Redis
redis-cli SHUTDOWN

# 2. 发送强平触发（应该降级到数据库幂等）
curl -X POST http://localhost:8088/test/trigger-liquidation \
  -H "Content-Type: application/json" \
  -d '{...}'

# 3. 检查日志
tail -f logs/liquidation-service.log | grep "Redis failed"
```

**预期日志**:
```
⚠️ [LiquidationService] Redis idempotency check failed, fallback to database
```

**验证**: 即使Redis故障，幂等性仍然工作 ✅

---

## 📊 性能验证（10分钟）

### 检查1: Kafka配置

```bash
# 连接Kafka，检查配置
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic liquidation-trigger-topic --group liquidation-service-group \
  --describe

# 检查生产者acks配置
# 应该看到：acks=all
```

### 检查2: 数据库连接池

```sql
-- 查看当前连接数
SHOW PROCESSLIST;

-- 检查最大连接数配置
SHOW VARIABLES LIKE 'max_connections';
```

**预期**:
- 连接数 < 50（新配置max-active=50）
- 无大量Sleep连接

### 检查3: Redis连接池

```bash
# 检查Redis连接数
redis-cli CLIENT LIST | wc -l

# 应该 < 50（新配置max-active=50）
```

---

## 🎯 关键指标监控

### 创建监控SQL

```sql
-- 1. 未发送事件数
SELECT COUNT(*) as pending_events
FROM t_liquidation_event
WHERE send_status = 'PENDING';
-- 预期：0 或很小的数字（正在重试中）

-- 2. 失败事件数（需人工介入）
SELECT COUNT(*) as failed_events
FROM t_liquidation_event
WHERE send_status = 'FAILED';
-- 预期：0（如果>0，需告警）

-- 3. 今日强平数
SELECT COUNT(*) as today_liquidations
FROM t_liquidation_execution
WHERE created_at >= UNIX_TIMESTAMP(CURDATE()) * 1000;

-- 4. 强平成功率
SELECT
    COUNT(CASE WHEN status = 'FILLED' THEN 1 END) as success,
    COUNT(*) as total,
    ROUND(COUNT(CASE WHEN status = 'FILLED' THEN 1 END) * 100.0 / COUNT(*), 2) as success_rate
FROM t_liquidation_execution
WHERE created_at >= UNIX_TIMESTAMP(CURDATE()) * 1000;
-- 预期成功率 > 99%

-- 5. 审计日志完整性
SELECT
    COUNT(DISTINCT liquidation_id) as liquidated,
    COUNT(DISTINCT CASE WHEN operation = 'CREATE' THEN liquidation_id END) as audited
FROM t_liquidation_execution e
LEFT JOIN t_liquidation_audit a ON e.liquidation_id = a.liquidation_id
WHERE e.created_at >= UNIX_TIMESTAMP(CURDATE()) * 1000;
-- 预期：audited = liquidated（每个强平都有审计记录）
```

---

## ⚠️ 常见问题

### Q1: 启动时报错 "Unknown column 'version'"

**原因**: 数据库schema未更新

**解决**:
```bash
mysql -h localhost -u root -p exchange_liquidation < src/main/resources/db/schema.sql
```

### Q2: 日志显示 "Redis connection failed"

**原因**: Redis未启动或密码错误

**解决**:
```bash
# 启动Redis
redis-server

# 或检查密码
export REDIS_PASSWORD=your_password
```

### Q3: Kafka消费lag一直增长

**原因**: 批量太大（max.poll.records=50可能仍太大）

**解决**:
```yaml
# 减小批量
max.poll.records: 10
```

### Q4: 数据库连接池耗尽

**原因**: 并发太高或连接泄漏

**解决**:
```yaml
# 增大连接池
max-active: 100
```

---

## 📞 支持

如有问题，请查看：
1. 详细修复文档: `CRITICAL_FIXES_SUMMARY.md`
2. 日志文件: `logs/liquidation-service.log`
3. 审计日志: `SELECT * FROM t_liquidation_audit`

---

**创建时间**: 2026-02-18
**文档版本**: v1.0
