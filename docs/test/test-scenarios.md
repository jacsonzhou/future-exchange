---
# 测试规范

## 测试链路
api-geteway(网关)->user-core（用户注册）
api-geteway(网关)->user-core（用户登录）
->snapshot-account-core(获取资金余额)
->oms-core(下单)->hardrisk(风控)
->oms-core->match-core(撮合)->ledger-core（双录账本）->snapshot-account-core/snapshot-position-core
->oms-core->match-core(撮合)->market-price-core->公有推送：盘口，k线，公有成交
->oms-core->match-core(撮合)->private-push-core私有推送
## 测试要求
1. 每个功能必须有单元测试
2. 集成测试覆盖完整链路
3. WebSocket 测试需模拟连接、断线、重连
4. 测试用例文档放在 /docs/test-cases.md
5. 测试报告自动生成到 /test-reports/

## 测试流程
1. 执行测试 → 生成报告
2. 失败的用例自动标记 ❌
3. 修复代码
4. 重跑失败用例（回归）
5. 全部通过后执行全量回归
6. 生成最终测试报告

## 测试命令
- 单元测试：npm test
- 集成测试：npm run test:integration
- E2E 测试：npm run test:e2e
```

## 🎯 完整链路测试场景

### 场景1: 正常开仓交易

```
用户A下单买入 0.01 BTC @ 50000 USDT，杠杆10x

步骤:
1. API Gateway (8080)
- JWT 认证通过
- X-User-Id: 10001 透传给 OMS
- 限流检查通过
- 路由到 OMS: /api/v1/oms/order/submit

2. OMS Core (8081)
- 参数校验
- 幂等性检查 (Redis: idempotent:{clientOrderId})
- 保证金预扣计算
- 调用 Hard Risk Core (Feign)

3. Hard Risk Core (8082)
- 查询 Account Snapshot (Redis)
- 检查可用余额 >= 保证金
- 检查杠杆限制
- 检查用户黑名单
- 返回 RiskCheckResult

4. OMS Core 发送 Kafka
- Topic: order-event-BTCUSDT
- Message: OrderCommand

5. Match Engine Core (8083)
- Disruptor 消费 OrderCommand
- OrderBook 撮合
- 生成 TradeEvent
- 写 WAL: match.log
- 发送 trade-topic

6. Ledger Core (8084)
- 消费 trade-topic
- 双录分录记账:
借: 持仓 BTC 0.01
贷: 保证金 USDT 500
- 发送 trade-entry-topic

7. Snapshot Services (8085/8086)
- 消费 trade-entry-topic
- 更新 Redis 快照
- 更新 MySQL 快照表

8. Risk Monitor (Soft Risk)
- 消费 position-delta + mark-price
- 计算保证金率
- 无风险，不触发强平

结果: ✅ 链路完整，资金/持仓一致
```