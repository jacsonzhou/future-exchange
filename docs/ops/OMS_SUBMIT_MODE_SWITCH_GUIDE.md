# OMS提交模式切换操作指南

## 📋 概述

OMS支持两种提交模式：
- **Kafka模式**（生产推荐）：异步解耦，延迟1-2ms
- **Feign模式**（降级/测试）：同步直连，延迟<1ms

本指南提供模式切换的操作步骤和注意事项。

---

## 🔧 快速切换

### 方法1：配置文件切换（推荐）

#### 切换到Kafka模式（默认）

```yaml
# application.yml
exchange:
  oms:
    submit-mode: kafka
```

```bash
# 重启服务
systemctl restart oms-core
# 或
java -jar oms-core.jar
```

#### 切换到Feign模式

```yaml
# application.yml
exchange:
  oms:
    submit-mode: feign
```

```bash
# 重启服务
systemctl restart oms-core
```

### 方法2：启动参数切换

```bash
# Kafka模式
java -jar oms-core.jar --exchange.oms.submit-mode=kafka

# Feign模式
java -jar oms-core.jar --exchange.oms.submit-mode=feign
```

### 方法3：环境变量切换

```bash
# Kafka模式
export EXCHANGE_OMS_SUBMIT_MODE=kafka
java -jar oms-core.jar

# Feign模式
export EXCHANGE_OMS_SUBMIT_MODE=feign
java -jar oms-core.jar
```

### 方法4：配置中心动态切换（生产推荐）

#### 使用Nacos配置中心

```bash
# 登录 Nacos 控制台
http://localhost:8848/nacos

# 修改配置
Data ID: oms-core.yml
Group: DEFAULT_GROUP
Namespace: dev

配置内容：
exchange:
  oms:
    submit-mode: kafka  # 或 feign
```

**优点**：无需重启，实时生效（需要应用支持配置热更新）

---

## 📊 切换验证

### 1. 检查当前模式

```bash
# 查看日志（提交订单时会打印模式）
tail -f /var/log/oms-core/oms-core.log | grep "Mode"

# Kafka模式日志示例：
# [Kafka Mode] Submitting order to match engine via Kafka, orderId=1234567890

# Feign模式日志示例：
# [Feign Mode] Submitting order to match engine via Feign, orderId=1234567890
```

### 2. 功能验证

```bash
# 提交测试订单
curl -X POST http://localhost:8081/api/order \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1001,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "price": "50000",
    "quantity": "1",
    "leverage": 10,
    "clientOrderId": "test-001"
  }'

# 观察响应和日志
```

### 3. Kafka模式验证

```bash
# 查看Kafka消息
kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic order-event-BTCUSDT \
  --from-beginning

# 应该看到订单事件：
# {"eventType":"ORDER_SUBMIT","orderId":1234567890,...}
```

### 4. Feign模式验证

```bash
# 查看Match Engine日志（应有直接HTTP调用）
tail -f /var/log/match-engine-core/match-engine-core.log

# 应该看到：
# Received order from Feign: orderId=1234567890
```

---

## 🚨 紧急降级场景

### 场景1：Kafka集群故障

**问题**：Kafka集群不可用，订单无法提交

**解决方案**：切换到Feign模式

```bash
# 方式1：配置中心（推荐）
# Nacos: exchange.oms.submit-mode = feign

# 方式2：重启应用
java -jar oms-core.jar --exchange.oms.submit-mode=feign

# 方式3：配置文件（需重启）
vim application.yml
# 修改 submit-mode: feign
systemctl restart oms-core
```

**恢复步骤**（Kafka修复后）：
```bash
# 1. 验证Kafka可用性
kafka-topics.sh --list --bootstrap-server localhost:9092

# 2. 切换回Kafka模式
# Nacos: exchange.oms.submit-mode = kafka

# 3. 观察日志验证
tail -f /var/log/oms-core/oms-core.log | grep "Kafka Mode"
```

### 场景2：Match Engine不可用

**问题**：Match Engine宕机

**Kafka模式**：
- ✅ 订单正常提交到Kafka队列
- ✅ Match Engine恢复后自动消费处理
- ✅ 无订单丢失

**Feign模式**：
- ❌ 订单提交失败
- ❌ 需要重试或人工处理

**建议**：使用Kafka模式，无需降级

---

## 📈 性能对比测试

### 延迟测试

```bash
# 准备测试脚本：order_submit.lua
wrk.method = "POST"
wrk.body   = '{"userId":1001,"symbol":"BTCUSDT","side":"BUY","price":"50000","quantity":"1"}'
wrk.headers["Content-Type"] = "application/json"

# Kafka模式压测
java -jar oms-core.jar --exchange.oms.submit-mode=kafka
wrk -t4 -c100 -d60s --latency -s order_submit.lua http://localhost:8081/api/order

# 记录结果：
# Latency P50: ~2ms
# Latency P99: ~5ms

# Feign模式压测
java -jar oms-core.jar --exchange.oms.submit-mode=feign
wrk -t4 -c100 -d60s --latency -s order_submit.lua http://localhost:8081/api/order

# 记录结果：
# Latency P50: ~0.5ms
# Latency P99: ~2ms
```

### TPS测试

```bash
# Kafka模式
ab -n 10000 -c 100 -p order.json \
  -T "application/json" http://localhost:8081/api/order

# Feign模式（同上）
```

---

## ⚙️ 配置优化

### Kafka模式优化

```yaml
spring:
  kafka:
    producer:
      # 低延迟配置
      acks: 1                          # 单副本确认
      linger-ms: 0                     # 不等待批量
      compression-type: none           # 不压缩
      batch-size: 16384                # 小批量
      max-in-flight-requests-per-connection: 5
```

### Feign模式优化

```yaml
feign:
  httpclient:
    enabled: true
    max-connections: 200               # 最大连接数
    max-connections-per-route: 50      # 每个路由最大连接
    connection-timeout: 5000           # 连接超时
    connection-timer-repeat: 3000      # 连接重试
  client:
    config:
      match-engine-core:
        connectTimeout: 1000           # 1秒连接超时
        readTimeout: 3000              # 3秒读取超时
        loggerLevel: basic
```

---

## 🔍 监控指标

### Kafka模式监控

```bash
# Prometheus指标
oms_kafka_send_success_total
oms_kafka_send_failure_total
oms_kafka_send_latency_seconds{quantile="0.99"}

# Grafana仪表盘查询
rate(oms_kafka_send_success_total[1m])
```

### Feign模式监控

```bash
# Prometheus指标
oms_feign_submit_success_total
oms_feign_submit_failure_total
oms_feign_submit_latency_seconds{quantile="0.99"}

# Grafana仪表盘查询
rate(oms_feign_submit_success_total[1m])
```

---

## ✅ 切换检查清单

### 切换前检查

- [ ] 确认目标模式（kafka/feign）
- [ ] 检查依赖服务状态（Kafka/Match Engine）
- [ ] 备份当前配置
- [ ] 通知相关团队（运维、测试）
- [ ] 准备回滚方案

### 切换步骤

- [ ] 修改配置（配置中心/配置文件）
- [ ] 重启服务（如需要）
- [ ] 验证服务启动成功
- [ ] 提交测试订单
- [ ] 检查日志模式标识
- [ ] 验证订单正常流转

### 切换后验证

- [ ] 功能验证：提交订单成功
- [ ] 性能验证：延迟在预期范围
- [ ] 监控验证：指标正常
- [ ] 日志验证：无错误日志
- [ ] 压测验证：TPS达标

---

## 🛠️ 故障排查

### 问题1：切换后服务无法启动

**可能原因**：
- 配置格式错误
- 依赖服务不可用（Kafka/Nacos）

**排查步骤**：
```bash
# 1. 检查配置
cat application.yml | grep submit-mode

# 2. 检查依赖服务
# Kafka
telnet localhost 9092

# Nacos
curl http://localhost:8848/nacos

# 3. 查看启动日志
tail -100 /var/log/oms-core/oms-core.log
```

### 问题2：切换后订单提交失败

**Kafka模式失败**：
```bash
# 检查Kafka连接
kafka-topics.sh --list --bootstrap-server localhost:9092

# 检查Topic存在
kafka-topics.sh --describe --topic order-event-BTCUSDT --bootstrap-server localhost:9092

# 查看Producer日志
tail -f /var/log/oms-core/oms-core.log | grep OrderEventPublisher
```

**Feign模式失败**：
```bash
# 检查Match Engine服务
curl http://localhost:8082/health

# 检查网络连通性
telnet localhost 8082

# 查看Feign调用日志
tail -f /var/log/oms-core/oms-core.log | grep MatchEngineClient
```

### 问题3：性能下降

**排查步骤**：
```bash
# 1. 检查当前模式
cat application.yml | grep submit-mode

# 2. 查看延迟指标
curl http://localhost:8081/actuator/metrics/oms.kafka.send.latency

# 3. 检查系统资源
top
iostat -x 1

# 4. 检查网络延迟
ping localhost
```

---

## 📞 联系方式

- **运维团队**：ops@example.com
- **架构团队**：arch@example.com
- **值班电话**：400-xxx-xxxx

---

## 附录：常用命令

```bash
# 查看当前配置
curl http://localhost:8081/actuator/env | jq '.propertySources[] | select(.name | contains("application.yml"))'

# 查看当前模式
curl http://localhost:8081/actuator/configprops | jq '.contexts.application.beans.omsSubmitModeConfig'

# 查看Kafka消费者组
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list

# 查看Kafka lag
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group match-engine-BTCUSDT --describe

# 查看服务日志
journalctl -u oms-core -f

# 重启服务
systemctl restart oms-core
systemctl status oms-core
```
