# ADL系统快速集成指南

## 🚀 5分钟快速启动

### 步骤1：替换文件（1分钟）

```bash
cd /Users/zhoufan/project/future-exchange/adl-core/src/main/java/com/exchange/adl

# 备份旧文件
mv service/impl/AdlServiceImpl.java service/impl/AdlServiceImpl.backup
mv job/AdlRankingJob.java job/AdlRankingJob.backup

# 使用集成版本
mv service/impl/AdlServiceImplIntegrated.java service/impl/AdlServiceImpl.java
mv job/AdlRankingJobUpdated.java job/AdlRankingJob.java
```

### 步骤2：配置服务地址（1分钟）

编辑 `src/main/resources/application.yml`：

```yaml
service:
  position:
    url: http://localhost:8084  # 修改为你的Position Service地址
  clearing:
    url: http://localhost:8085  # 修改为你的Clearing Service地址
```

### 步骤3：启动服务（1分钟）

```bash
# 编译
mvn clean package -DskipTests

# 启动
java -jar target/adl-core-1.0.0.jar
```

### 步骤4：验证集成（2分钟）

```bash
# 1. 检查ADL排名计算是否正常
curl http://localhost:8090/api/v1/adl/ranking?symbol=BTCUSDT&side=LONG

# 预期响应：
{
  "code": 0,
  "data": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "rankings": [...]
  }
}

# 2. 检查保险基金
curl http://localhost:8090/api/v1/adl/insurance-fund?symbol=BTCUSDT

# 预期响应：
{
  "code": 0,
  "data": {
    "symbol": "BTCUSDT",
    "balance": "1000000.00",
    "status": "SAFE"
  }
}
```

✅ **完成！ADL系统已集成并运行。**

---

## 📋 核心API接口

### 1. 查询ADL排名

```bash
GET /api/v1/adl/ranking?symbol=BTCUSDT&side=LONG&userId=10001

响应示例：
{
  "code": 0,
  "data": {
    "symbol": "BTCUSDT",
    "side": "LONG",
    "rankings": [
      {
        "rank": 1,
        "userId": "10***01",
        "qty": "1.5",
        "pnlRatio": "15.50%",
        "effectiveLeverage": 10,
        "adlScore": 1550.00,
        "riskLevel": 5
      }
    ],
    "myRank": 5,
    "adlZone": false,
    "riskLevel": 3
  }
}
```

### 2. 查询保险基金

```bash
GET /api/v1/adl/insurance-fund?symbol=BTCUSDT

响应示例：
{
  "code": 0,
  "data": {
    "symbol": "BTCUSDT",
    "currency": "USDT",
    "balance": "1000000.00",
    "availableBalance": "950000.00",
    "totalIncome": "2000000.00",
    "totalExpense": "1000000.00",
    "status": "SAFE"
  }
}
```

### 3. 查询ADL历史

```bash
GET /api/v1/adl/history?symbol=BTCUSDT&limit=100

响应示例：
{
  "code": 0,
  "data": {
    "items": [
      {
        "adlExecutionId": "ADL_1708243200000_abc123",
        "symbol": "BTCUSDT",
        "adlPrice": "48000.00",
        "adlQty": "1.0",
        "affectedUsers": 3,
        "executedAt": 1708243200000
      }
    ],
    "total": 1
  }
}
```

---

## 🔄 完整交易链路测试

### 测试场景：模拟穿仓触发ADL

```bash
# 1. 发送强平完成事件到Kafka
kafka-console-producer --broker-list localhost:9092 \
  --topic liquidation-completed-topic

# 输入以下JSON（注意格式化）：
{
  "liquidationId": "LIQ_TEST_001",
  "userId": 10001,
  "symbol": "BTCUSDT",
  "side": "LONG",
  "bankruptPrice": 48000.00,
  "markPrice": 48000.00,
  "originalQty": 2.0,
  "filledQty": 0.0,
  "remainingQty": 2.0,
  "bankruptLoss": 5000.00,
  "isBankrupt": true,
  "completedAt": 1708243200000,
  "timestamp": 1708243200000,
  "eventId": "EVT_TEST_001"
}

# 2. 观察日志输出
tail -f logs/adl-service.log

# 预期日志：
# Processing liquidation event: liquidationId=LIQ_TEST_001
# Bankruptcy record created: id=1, loss=5000.00
# Insurance fund covered: amount=5000.00
# 或者
# Insurance fund insufficient, triggering ADL
# ADL executed successfully: userId=10002, qty=1.0
```

---

## 🛠 故障排查

### 问题1：ADL排名为空

**症状：**
```bash
curl http://localhost:8090/api/v1/adl/ranking?symbol=BTCUSDT&side=LONG
# 返回 rankings: []
```

**排查步骤：**
```bash
# 1. 检查Position Service是否正常
curl http://localhost:8084/health

# 2. 检查Position Service是否有盈利持仓
curl http://localhost:8084/api/v1/position/query-profitable \
  -X POST \
  -H "Content-Type: application/json" \
  -d '{"symbol":"BTCUSDT","side":"LONG","onlyProfitable":true}'

# 3. 检查ADL排名计算Job是否正常运行
# 查看日志：grep "ADL ranking calculated" logs/adl-service.log

# 4. 手动触发排名计算
curl -X POST http://localhost:8090/internal/adl/calculate-ranking \
  -H "Content-Type: application/json" \
  -d '{"symbol":"BTCUSDT","side":"LONG"}'
```

### 问题2：ADL执行失败

**症状：**
```
日志显示：Clearing failed for ADL: adlExecutionId=ADL_xxx, message=...
```

**排查步骤：**
```bash
# 1. 检查Clearing Service是否正常
curl http://localhost:8085/health

# 2. 检查Clearing Service日志
tail -f ../clearing-service/logs/clearing.log

# 3. 验证ADL记账请求格式
# 查看日志中的请求详情

# 4. 检查幂等性
# 查询是否已存在相同bizSeq的记录
curl http://localhost:8085/internal/clearing/check?bizSeq=ADL_xxx
```

### 问题3：二次校验总是失败

**症状：**
```
日志显示：ADL candidate validation failed: userId=xxx, positionId=xxx
```

**排查步骤：**
```bash
# 1. 检查Position Service返回的持仓数据
curl http://localhost:8084/api/v1/position/get-by-id?positionId=50001

# 2. 确认持仓状态
# - 持仓是否还存在？
# - 持仓是否还在盈利？
# - 账户是否被冻结？

# 3. 检查ADL排名队列数据新鲜度
SELECT rank_updated_at FROM adl_ranking_queue WHERE position_id = 50001;
# 如果超过5秒，说明排名数据过期
```

---

## 📊 监控配置（可选）

### 使用Prometheus监控

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true

# 关键指标
- adl_ranking_calculation_duration_seconds    # ADL排名计算耗时
- adl_execution_total                          # ADL执行总次数
- adl_execution_success_total                  # ADL执行成功次数
- adl_execution_failure_total                  # ADL执行失败次数
- insurance_fund_balance                       # 保险基金余额
- position_service_call_duration_seconds       # Position Service调用耗时
- clearing_service_call_duration_seconds       # Clearing Service调用耗时
```

---

## 🎯 下一步

### 推荐的优化项：

1. **添加Redis缓存**
   ```yaml
   # 缓存ADL排名到Redis，减少数据库查询
   spring:
     cache:
       type: redis
       redis:
         time-to-live: 5000  # 5秒过期
   ```

2. **添加熔断器**
   ```yaml
   # 使用Resilience4j保护服务调用
   resilience4j:
     circuitbreaker:
       instances:
         positionService:
           sliding-window-size: 10
           failure-rate-threshold: 50
   ```

3. **添加分布式锁**
   ```java
   // 使用Redisson防止ADL并发执行
   @Autowired
   private RedissonClient redisson;

   RLock lock = redisson.getLock("adl_lock_" + liquidationId);
   if (lock.tryLock(30, TimeUnit.SECONDS)) {
       try {
           executeAdl(...);
       } finally {
           lock.unlock();
       }
   }
   ```

---

## 📚 相关文档

- [ADL需求文档](requirements/04_adl_requirements.md)
- [ADL需求补充](requirements/04_adl_requirements_supplement.md)
- [ADL实现总结](ADL_IMPLEMENTATION_SUMMARY.md)
- [ADL集成完成总结](ADL_INTEGRATION_COMPLETE.md)

---

## ❓ 常见问题

**Q1: ADL多久更新一次排名？**
A: 默认每5秒更新一次，可通过配置修改。

**Q2: ADL执行时如何保证幂等性？**
A: 使用`adlExecutionId`作为唯一标识，Clearing Service会去重。

**Q3: 如果Position Service宕机怎么办？**
A: ADL排名计算会失败，但不影响已有排名的使用。建议配置熔断器和降级策略。

**Q4: 保险基金耗尽后会怎样？**
A: 系统会完全依赖ADL分摊穿仓损失。建议监控保险基金余额并及时补充。

**Q5: ADL会影响多少用户？**
A: 最多50人/批次，最多10批次，总计最多500人。可通过配置调整。

---

## 🆘 获取帮助

如有问题，请查看：
1. 日志文件：`logs/adl-service.log`
2. 数据库表：`adl_ranking_queue`, `adl_execution`, `bankruptcy_record`
3. Kafka消息：`liquidation-completed-topic`, `adl-trigger-topic`, `adl-executed-topic`

技术支持：
- GitHub Issues: https://github.com/your-repo/issues
- 内部文档：Confluence ADL专区
