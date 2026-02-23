# 服务端口映射表

> 本文档定义了系统中所有微服务的统一端口分配，确保配置一致性。

## 端口分配表

| 服务名称 | 端口 | 用途 | 说明 |
|---------|------|------|------|
| **api-gateway** | 8080 | 对外接口入口 | API网关，统一认证/限流/路由 |
| **oms-core** | 8081 | 订单管理系统 | 订单生命周期管理 |
| **hard-risk-core** | 8082 | 硬风控服务 | 同步前置风控检查 |
| **match-engine-core** | 8083 | 撮合引擎 | Disruptor高性能撮合 |
| **ledger-core** | 8084 | 账本服务 | 双录分录记账 |
| **snapshot-account-core** | 8085 | 账户快照服务 | 账户余额快照维护 |
| **position-snapshot-core** | 8086 | 持仓快照服务 | 持仓快照/浮盈浮亏计算 |
| **replay-core** | 8087 | 重放服务 | WAL日志重放/灾备恢复 |
| **liquidation-core** | 8088 | 强平服务 | 自动强平执行 |
| **adl-core** | 8089 | ADL服务 | 自动减仓 |
| **margin-mode-core** | 8090 | 保证金模式服务 | 全仓/逐仓模式管理 |
| **tp-sl-core** | 8091 | 止盈止损服务 | 条件单触发管理 |
| **funding-rate-core** | 8092 | 资金费率服务 | 资金费率计算/结算 |
| **market-maker-core** | 8093 | 做市商服务 | 做市策略执行 |
| **index-price-core** | 8094 | 指数价格服务 | 多交易所指数价格 |
| **market-price-core** | 8095 | 行情生成服务 | OrderBook/K线/Ticker计算 |
| **public-push-core** | 8096 | 公有推送服务 | WebSocket行情推送 |
| **private-push-core** | 8097 | 私有推送服务 | WebSocket订单/持仓推送 |
| **mark-price-core** | 8098 | 标记价格服务 | 合约标记价格计算 |

## 端口段分配

| 端口段 | 用途 |
|--------|------|
| 8080-8089 | 核心交易服务（撮合/账本/风控/订单） |
| 8090-8099 | 业务扩展服务（保证金/强平/ADL/资金费率） |
| 8100-8199 | 预留（后续扩展） |

## 配置检查清单

在修改配置文件时，请确保：

1. **服务自身端口**与上表一致
2. **下游服务调用**使用正确的端口
3. **Nacos服务名**与端口对应
4. **Kafka consumer group**使用服务名区分

## 常见问题

### Q: 为什么position-snapshot-core使用8086而不是8084？
A: 
- 8084 分配给 ledger-core（账本服务）
- 8086 分配给 position-snapshot-core（持仓快照服务）
- 历史遗留问题导致 margin-mode-core 曾错误配置为8084，已修复

### Q: 如何验证端口配置正确？
A: 运行以下命令检查：
```bash
# 检查所有服务的端口配置
grep -r "server.port" */src/main/resources/application.yml

# 检查服务间调用配置
grep -r "url: http://localhost" */src/main/resources/application.yml
```

## 变更记录

| 日期 | 变更内容 | 变更人 |
|------|---------|--------|
| 2024-02-19 | 修复 margin-mode-core position服务端口 8084→8086 | System |
| 2024-02-19 | 统一Kafka消费模式为手动ACK | System |
