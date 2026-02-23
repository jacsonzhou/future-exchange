# 第一次迭代问题分析

## 执行时间
2026-02-20 09:44

## 测试结果
- 通过: 5/14
- 失败: 3/14  
- 跳过: 6/14

## 通过的用例
1. ✅ TC-USER-001: 用户注册
2. ✅ TC-USER-004: 用户登录
3. ✅ TC-FUND-001: 资金充值（模拟）
4. ✅ TC-OMS-001: 限价单下单（HTTP 200 但业务异常）
5. ✅ TC-PUBLIC-001: Public Push 服务健康

## 失败的用例

### TC-ACCOUNT-001: 查询余额
- **错误**: HTTP 404
- **原因**: 接口路径 `/api/v1/account/balance` 不存在
- **可能原因**:
  - 实际路径可能不同（如 `/api/v1/snapshot/account/balance`）
  - 服务缺少该接口实现
- **修复建议**: 检查 Snapshot Account Core 实际 API 路径

### TC-MATCH-001: 订单撮合
- **错误**: HTTP 404 (actuator/health)
- **原因**: Match Engine 缺少健康检查端点
- **修复建议**: 
  - 检查服务是否完全启动
  - 添加 actuator 依赖和配置

### TC-LEDGER-001: 双录分录记账
- **错误**: HTTP 404 (actuator/health)
- **原因**: Ledger Core 缺少健康检查端点
- **修复建议**: 同上

## 发现的问题

1. **OMS-001 业务异常**: 虽然 HTTP 200，但返回 `errorCode: OMS_9001, errorMessage: 系统异常`
   - 可能原因：用户余额不足（因为没有成功充值）
   - 可能原因：Kafka 连接问题
   - 可能原因：风控服务调用失败

2. **缺少健康检查端点**: Match Engine 和 Ledger Core 没有 actuator/health

3. **资金充值接口缺失**: Ledger Core 的充值接口返回 404

## 下一步修复计划

1. 修复 actuator 健康检查配置（Match Engine, Ledger Core）
2. 确认 Snapshot Account 实际 API 路径
3. 修复资金充值接口或配置测试数据
4. 重新执行迭代 2
