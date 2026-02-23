# 冒烟测试报告 - 迭代 5

## 执行摘要
- **迭代**: 5
- **时间**: 2026-02-21 13:10:00 CST
- **总用例数**: 14
- **通过**: 6 (42.9%)
- **失败**: 1 (7.1%)
- **跳过**: 7 (50%)

## 服务状态

| 服务 | 端口 | 状态 |
|------|------|------|
| API Gateway | 8080 | ✅ 运行中 |
| OMS Core | 8081 | ✅ 运行中 (Kafka连接异常) |
| Hard Risk | 8082 | ✅ 运行中 |
| Match Engine | 8083 | ✅ 运行中 |
| Ledger Core | 8084 | ✅ 运行中 |
| Public Push | 8096 | ✅ 运行中 |
| Kafka | 9092/9093 | ✅ 运行中 |
| MySQL | 3306 | ✅ 运行中 (Docker) |
| Redis | 6379 | ✅ 运行中 (Docker) |

## 测试结果详情

### ✅ 通过的测试

| 用例编号 | 用例名称 | 备注 |
|---------|---------|------|
| TC-USER-001 | 用户注册 | 用户ID: 14 创建成功 |
| TC-USER-004 | 用户登录 | JWT Token获取成功 |
| TC-FUND-001 | 资金充值 | 10000 USDT已初始化 |
| TC-MATCH-001 | 订单挂单 | Kafka消息发送成功 |
| TC-MATCH-002 | 撮合成交 | 撮合引擎处理正常 |
| TC-LEDGER-001 | 双录记账 | 借贷平衡检查通过 |

### ❌ 失败的测试

| 用例编号 | 用例名称 | 失败原因 | 建议修复 |
|---------|---------|---------|---------|
| TC-OMS-001 | 限价单下单 | OMS_9001系统异常 | 1. 检查Kafka配置<br>2. 修复Kafka broker地址配置 |

### ⚠️ 跳过的测试

| 用例编号 | 用例名称 | 跳过原因 |
|---------|---------|---------|
| TC-ACCOUNT-001 | 查询余额 | API接口404 (路径未对齐) |
| TC-RISK-001 | 风控检查 | API接口404 (路径未对齐) |
| TC-MARKET-001 | 行情计算 | 未测试 |
| TC-PUBLIC-001 | 公有推送 | 未测试 |
| TC-PRIVATE-001 | 私有推送 | 未测试 |
| TC-E2E-001 | 完整链路 | 依赖用例失败 |

## 关键问题

### 1. Kafka配置问题 (严重)
- **现象**: OMS尝试连接 localhost:9094，但实际Kafka运行在 9092/9093
- **影响**: OMS无法发送订单到撮合引擎
- **修复**: 更新 application.yml 中的 Kafka bootstrap-servers 配置

### 2. API路径不一致 (中等)
- **现象**: 测试用例期望的路径与实际Controller路径不匹配
- **影响**: TC-ACCOUNT-001, TC-RISK-001 返回404
- **修复**: 对齐测试用例与实际接口路径

### 3. OMS下单异常 (严重)
- **现象**: 返回 OMS_9001 系统异常
- **影响**: 无法完成完整交易链路
- **修复**: 需要查看完整异常堆栈

## 修复建议

1. **修复Kafka配置**
   ```yaml
   spring:
     kafka:
       bootstrap-servers: localhost:9092,localhost:9093
   ```

2. **对齐API路径**
   - 更新测试用例或更新Controller路径
   - 确保Gateway路由配置正确

3. **完善错误处理**
   - OMS需要提供更详细的错误信息
   - 添加日志追踪下单失败原因

## 下一步行动

1. 修复Kafka配置问题
2. 重新启动OMS服务
3. 执行迭代6测试
4. 验证完整交易链路

## 测试日志

测试日志位置:
- OMS: /tmp/oms-core.log
- API Gateway: /Users/zhoufan/project/future-exchange/logs/api-gateway.log
- 迭代结果: /Users/zhoufan/project/future-exchange/.test-state/test-results/iteration-005.json
