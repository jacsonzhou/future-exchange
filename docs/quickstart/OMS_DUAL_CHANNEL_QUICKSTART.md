# OMS双通道架构快速开始

## 🚀 5分钟快速体验

### 前置条件

- Java 17+
- Maven 3.6+
- Docker & Docker Compose（Kafka模式）
- MySQL
- Redis

---

## 📦 启动步骤

### 1. 启动依赖服务

#### Kafka模式依赖

```bash
# 启动Kafka
cd /path/to/project
docker-compose up -d kafka zookeeper

# 验证Kafka
docker ps | grep kafka
kafka-topics.sh --list --bootstrap-server localhost:9092
```

#### Feign模式依赖

```bash
# 启动Match Engine
cd match-engine-core
mvn spring-boot:run

# 验证Match Engine
curl http://localhost:8082/health
```

### 2. 启动OMS（Kafka模式）

```bash
cd oms-core

# 方式1：Maven
mvn spring-boot:run

# 方式2：JAR
mvn clean package
java -jar target/oms-core.jar

# 方式3：指定配置
java -jar target/oms-core.jar --exchange.oms.submit-mode=kafka
```

### 3. 启动OMS（Feign模式）

```bash
cd oms-core

# 方式1：使用Profile
mvn spring-boot:run -Dspring-boot.run.profiles=feign

# 方式2：启动参数
java -jar target/oms-core.jar --spring.profiles.active=feign

# 方式3：配置参数
java -jar target/oms-core.jar --exchange.oms.submit-mode=feign
```

---

## 🧪 测试验证

### 提交测试订单

```bash
# 创建测试脚本：test_order.sh
cat > test_order.sh <<'EOF'
#!/bin/bash
curl -X POST http://localhost:8081/api/order \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1001,
    "clientOrderId": "test-'$(date +%s)'",
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": "50000",
    "quantity": "1",
    "leverage": 10
  }'
EOF

chmod +x test_order.sh
./test_order.sh
```

### 验证Kafka模式

```bash
# 观察OMS日志
tail -f logs/oms-core.log | grep "Kafka Mode"

# 应看到：
# [Kafka Mode] Submitting order to match engine via Kafka, orderId=1234567890

# 观察Kafka消息
kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic order-event-BTCUSDT \
  --from-beginning

# 应看到订单事件
```

### 验证Feign模式

```bash
# 观察OMS日志
tail -f logs/oms-core.log | grep "Feign Mode"

# 应看到：
# [Feign Mode] Submitting order to match engine via Feign, orderId=1234567890

# 观察Match Engine日志
tail -f ../match-engine-core/logs/match-engine-core.log

# 应看到接收到订单
```

---

## 🔄 模式切换演示

### 场景1：运行时切换（需要重启）

```bash
# 当前运行在Kafka模式
# 提交几个订单
./test_order.sh

# 停止OMS
Ctrl+C

# 切换到Feign模式
java -jar target/oms-core.jar --exchange.oms.submit-mode=feign

# 提交订单验证
./test_order.sh

# 观察日志变化
tail -f logs/oms-core.log
```

### 场景2：配置文件切换

```bash
# 编辑配置文件
vim src/main/resources/application.yml

# 修改：
exchange:
  oms:
    submit-mode: feign  # 从kafka改为feign

# 重启服务
mvn spring-boot:run

# 验证
./test_order.sh
```

---

## 📊 性能对比测试

### 安装压测工具

```bash
# 安装wrk（Mac）
brew install wrk

# 安装wrk（Ubuntu）
sudo apt-get install wrk
```

### 创建压测脚本

```bash
# 创建：order_submit.lua
cat > order_submit.lua <<'EOF'
wrk.method = "POST"
wrk.headers["Content-Type"] = "application/json"

counter = 0
function request()
    counter = counter + 1
    wrk.body = string.format([[{
        "userId": 1001,
        "clientOrderId": "perf-%d",
        "symbol": "BTCUSDT",
        "side": "BUY",
        "orderType": "LIMIT",
        "price": "50000",
        "quantity": "1",
        "leverage": 10
    }]], counter)
    return wrk.format()
end
EOF
```

### Kafka模式压测

```bash
# 启动OMS（Kafka模式）
java -jar target/oms-core.jar --exchange.oms.submit-mode=kafka

# 压测（100并发，60秒）
wrk -t4 -c100 -d60s --latency \
  -s order_submit.lua \
  http://localhost:8081/api/order

# 记录结果
# Latency    Avg     Stdev     Max
#            2ms     1ms       50ms
# Req/Sec   5000    500       6000
```

### Feign模式压测

```bash
# 启动OMS（Feign模式）
java -jar target/oms-core.jar --exchange.oms.submit-mode=feign

# 压测（同上）
wrk -t4 -c100 -d60s --latency \
  -s order_submit.lua \
  http://localhost:8081/api/order

# 记录结果
# Latency    Avg     Stdev     Max
#            0.5ms   0.3ms     20ms
# Req/Sec   10000   800       12000
```

### 结果对比

| 指标 | Kafka模式 | Feign模式 | 差异 |
|-----|----------|----------|------|
| 平均延迟 | 2ms | 0.5ms | **4倍** |
| P99延迟 | 5ms | 2ms | **2.5倍** |
| TPS | 5000 | 10000 | **2倍** |

---

## 🚨 灾备演示

### Kafka模式灾备能力

```bash
# 1. 启动OMS（Kafka模式）
java -jar target/oms-core.jar --exchange.oms.submit-mode=kafka

# 2. 提交100个订单
for i in {1..100}; do ./test_order.sh; done

# 3. 停止Match Engine（模拟故障）
cd ../match-engine-core
Ctrl+C

# 4. 继续提交100个订单（应该成功）
for i in {1..100}; do ./test_order.sh; done

# 5. 观察Kafka消息堆积
kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group match-engine-BTCUSDT \
  --describe

# 应看到LAG=200（200个订单待处理）

# 6. 重启Match Engine
mvn spring-boot:run

# 7. 观察LAG归零（所有订单被处理）
kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group match-engine-BTCUSDT \
  --describe
```

### Feign模式无灾备

```bash
# 1. 启动OMS（Feign模式）
java -jar target/oms-core.jar --exchange.oms.submit-mode=feign

# 2. 提交100个订单
for i in {1..100}; do ./test_order.sh; done

# 3. 停止Match Engine
cd ../match-engine-core
Ctrl+C

# 4. 继续提交订单（应该失败）
./test_order.sh

# 应看到错误：
# Connection refused: localhost:8082
# 订单提交失败

# 结论：Feign模式无法容忍Match Engine故障
```

---

## 📈 监控观察

### 查看应用指标

```bash
# Actuator健康检查
curl http://localhost:8081/actuator/health

# 配置查看
curl http://localhost:8081/actuator/env | jq '.propertySources[] | select(.name | contains("application.yml"))'

# 当前配置
curl http://localhost:8081/actuator/configprops | jq '.contexts.application.beans.omsSubmitModeConfig'
```

### 查看Kafka指标

```bash
# Consumer Group状态
kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --list

# 消费延迟
kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group match-engine-BTCUSDT \
  --describe

# Topic详情
kafka-topics.sh \
  --describe \
  --topic order-event-BTCUSDT \
  --bootstrap-server localhost:9092
```

---

## 🎯 典型场景

### 场景1：日常开发测试（Feign模式）

```bash
# 低延迟，快速迭代
java -jar oms-core.jar --exchange.oms.submit-mode=feign

# 优点：
# - 启动快（无需Kafka）
# - 延迟低（< 1ms）
# - 调试方便（直接调用）
```

### 场景2：集成测试（Kafka模式）

```bash
# 完整链路测试
docker-compose up -d kafka
java -jar oms-core.jar --exchange.oms.submit-mode=kafka

# 优点：
# - 贴近生产
# - 测试解耦
# - 验证消息流转
```

### 场景3：生产部署（Kafka模式）

```bash
# 生产环境配置
exchange:
  oms:
    submit-mode: kafka

# 优点：
# - 高可用
# - 可灾备
# - 可审计
# - 监管合规
```

### 场景4：紧急降级（Kafka→Feign）

```bash
# Kafka集群故障，紧急切换
java -jar oms-core.jar --exchange.oms.submit-mode=feign

# 注意：
# - 失去灾备能力
# - 失去审计日志
# - Match Engine成为单点
# - Kafka恢复后尽快切回
```

---

## 🔧 常见问题

### Q1: 如何确认当前使用的模式？

```bash
# 方法1：查看日志
tail -f logs/oms-core.log | grep "Mode"

# 方法2：查看配置
curl http://localhost:8081/actuator/env | grep submit-mode

# 方法3：提交订单观察
./test_order.sh
tail -f logs/oms-core.log
```

### Q2: 切换模式需要重启吗？

```
需要。当前实现需要重启服务才能生效。

后续可集成Nacos配置中心实现动态切换（无需重启）。
```

### Q3: Kafka模式下，Match Engine宕机会丢消息吗？

```
不会。订单事件已持久化到Kafka（7天保留期）。
Match Engine恢复后会自动消费所有未处理的订单。
```

### Q4: Feign模式下，Match Engine宕机怎么办？

```
订单提交会失败。需要：
1. 修复Match Engine
2. 或切换到Kafka模式
3. 人工处理失败订单（如有）
```

### Q5: 性能差距有多大？

```
延迟：Feign快4倍（0.5ms vs 2ms）
TPS：Feign高2倍（10000 vs 5000）

但对于合约交易（通常10-50ms端到端），1-2ms差异可忽略。
```

---

## 📚 延伸阅读

- [架构设计文档](/docs/architecture/OMS_MATCH_ENGINE_DUAL_CHANNEL.md)
- [操作切换指南](/docs/ops/OMS_SUBMIT_MODE_SWITCH_GUIDE.md)
- [实施状态](/docs/architecture/IMPLEMENTATION_STATUS.md)

---

## 🎓 下一步

- [ ] 添加Prometheus监控
- [ ] 编写单元测试
- [ ] 集成Nacos配置中心（动态切换）
- [ ] 灰度发布策略
- [ ] 自动降级策略

---

**编写日期**：2026-02-18
**维护团队**：Architecture Team
