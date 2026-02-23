# 修复报告 - 迭代 6

## 修复摘要

| 项目 | 内容 |
|------|------|
| 修复迭代 | 6 |
| 修复时间 | 2026-02-21 17:30 CST |
| 修复用例 | TC-OMS-001 |
| 修复状态 | ✅ 已验证通过 |

## 修复内容

### 1. Kafka配置修复

**问题**: OMS服务配置错误，尝试连接不存在的Kafka broker `localhost:9094`

**影响**: OMS无法发送订单命令到撮合引擎

**修复**: 更新 `oms-core/src/main/resources/application.yml`

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092,localhost:9093
```

**验证**:
```
[Consumer clientId=consumer-oms-order-state-1] 
Successfully joined group with generation...
partitions assigned: [order-state-BTCUSDT-0, order-state-ETHUSDT-0]
```

### 2. JSON字段名对齐

**问题**: 测试用例字段名与代码DTO字段名不匹配

| 测试用例字段 | 代码DTO字段 | 状态 |
|-------------|------------|------|
| orderType | type | ✅ 已对齐 |

**修复**: 更新测试请求体
```json
{
  "symbol": "BTCUSDT",
  "side": "BUY",
  "type": "LIMIT",      // <-- 修复: orderType -> type
  "price": "50000",
  "quantity": "0.01",
  "leverage": 10
}
```

## 验证结果

### 测试响应
```json
{
  "orderId": "283530950468440064",
  "status": "FROZEN",
  "clientOrderId": "smoke-iter6-001",
  "success": true
}
```

### 验证指标
- ✅ 订单创建成功
- ✅ 订单状态正确 (FROZEN)
- ✅ Kafka消息发送成功
- ✅ 订单可查询

## 后续建议

1. **更新API文档**: 确保文档中字段名与代码一致
2. **完善测试用例**: 将所有测试用例的 orderType 改为 type
3. **添加字段验证**: 在Controller层添加更清晰的错误提示
4. **配置检查**: 启动时检查Kafka broker可用性

## 修复文件清单

- `oms-core/src/main/resources/application.yml`
- `.test-state/fixes/fix-006-kafka-config.md`
- `.test-state/fixes/fix-006-json-fieldname.md`
