# WebSocket 端到端测试方案

## 概述

本文档描述交易所 WebSocket 推送系统的完整端到端测试方案，涵盖公有推送（行情数据）和私有推送（订单/账户数据）两大子系统。

## 系统架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           WebSocket E2E 测试架构                              │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────┐      ┌─────────────────┐      ┌─────────────────┐
│   测试用户   │ ──▶  │  API Gateway    │ ──▶  │    User Core    │
└─────────────┘      │    :8082        │      │    :8099        │
                     └─────────────────┘      └─────────────────┘
                               │                         │
                               │ JWT Token               │
                               ▼                         │
                     ┌─────────────────┐                │
                     │  Private Push   │ ◀──────────────┘
                     │    :8097        │   认证
                     └─────────────────┘
                               │
                               │ Kafka
                               ▼
                     ┌─────────────────┐      ┌─────────────────┐
                     │  OMS Core       │ ◀─── │  Match Engine   │
                     │    :8083        │ ───▶ │    :8084        │
                     └─────────────────┘      └─────────────────┘
                               │
                               │ Kafka
                               ▼
                     ┌─────────────────┐
                     │  Public Push    │ ◀── 行情数据
                     │    :8096        │
                     └─────────────────┘
```

## 数据流

### 1. 私有推送流（订单状态）

```
用户下单 → API Gateway → OMS Core → Kafka(order-events) → Match Engine
                                                            ↓
用户 WebSocket ◀─ Private Push ◀── Kafka(private-order-state) ◀─ OMS Core
```

### 2. 公有推送流（行情数据）

```
Match Engine → Kafka(market.depth/trade/ticker) → Public Push → 所有用户
```

## WebSocket 协议

### 公有推送 (ws://localhost:8096/ws/market)

**连接建立**
```
ws://localhost:8096/ws/market
```

**订阅请求**
```json
{
  "method": "SUBSCRIBE",
  "params": ["trade.BTCUSDT", "depth.BTCUSDT@100ms", "ticker.BTCUSDT"],
  "id": 1
}
```

**订阅响应**
```json
{
  "result": "success",
  "id": 1,
  "streams": ["trade.BTCUSDT", "depth.BTCUSDT@100ms", "ticker.BTCUSDT"]
}
```

**心跳**
```json
// 客户端发送
{"ping": 1704067200123}

// 服务端响应
{"pong": 1704067200123}
```

### 私有推送 (ws://localhost:8097/ws/private)

**连接建立（两种方式）**
```
// 方式1: Query Parameter
ws://localhost:8097/ws/private?token=eyJhbGciOiJIUzI1NiJ9...

// 方式2: Header
ws://localhost:8097/ws/private
Header: Authorization: Bearer <token>
```

**连接确认**
```json
{
  "e": "connectionAck",
  "userId": 9,
  "sessionId": "sess_abc123",
  "E": 1704067200123
}
```

**订阅请求**
```json
{
  "method": "SUBSCRIBE",
  "params": ["executionReport", "account", "balance", "position", "fundingFee"],
  "id": 1
}
```

**心跳**
```json
// 客户端发送
{"method": "PING", "id": 1}

// 服务端响应
{"method": "PONG", "id": 1, "E": 1704067200123}
```

## 测试场景

### 场景1: 公有推送连接测试

| 步骤 | 操作 | 预期结果 |
|------|------|----------|
| 1 | 连接 ws://localhost:8096/ws/market | 连接成功 |
| 2 | 发送订阅请求 | 收到订阅确认 |
| 3 | 等待 30 秒 | 收到至少一条 depth 快照 |
| 4 | 发送心跳 | 收到 pong 响应 |

### 场景2: 私有推送认证测试

| 步骤 | 操作 | 预期结果 |
|------|------|----------|
| 1 | 无 Token 连接 | 连接被拒绝，返回 401 |
| 2 | 无效 Token 连接 | 连接被拒绝，返回 401 |
| 3 | 有效 Token 连接 | 连接成功，收到 connectionAck |
| 4 | 过期 Token 连接 | 连接被拒绝，返回 401 |

### 场景3: 订单提交 + 执行报告推送

| 步骤 | 操作 | 预期结果 |
|------|------|----------|
| 1 | 登录获取 JWT | 返回有效 Token |
| 2 | 连接私有 WebSocket 并订阅 executionReport | 连接成功，订阅确认 |
| 3 | 通过 API Gateway 提交订单 | 订单创建成功，返回 OrderID |
| 4 | 等待推送 | 在 3 秒内收到 executionReport 消息 |
| 5 | 验证消息内容 | 包含正确的 orderId、status、clientOrderId |

### 场景4: 行情数据推送

| 步骤 | 操作 | 预期结果 |
|------|------|----------|
| 1 | 连接公有 WebSocket | 连接成功 |
| 2 | 订阅 depth.BTCUSDT@100ms | 收到深度快照 |
| 3 | 订阅 trade.BTCUSDT | 如果市场活跃，收到成交记录 |
| 4 | 取消订阅 | 停止接收对应频道消息 |

### 场景5: 并发连接测试

| 步骤 | 操作 | 预期结果 |
|------|------|----------|
| 1 | 50 个用户同时连接私有 WebSocket | 全部连接成功 |
| 2 | 每用户提交 10 笔订单 | 订单创建成功 |
| 3 | 验证消息投递 | 每个用户收到自己的执行报告 |
| 4 | 无消息丢失 | 订单数 == 执行报告数 |

## 消息格式

### 执行报告 (executionReport)

```json
{
  "stream": "executionReport",
  "data": {
    "e": "executionReport",
    "E": 1704067200123,
    "s": "BTCUSDT",
    "i": 283161102538248192,
    "c": "client-order-id-001",
    "S": "BUY",
    "o": "LIMIT",
    "f": "GTC",
    "q": "0.01",
    "p": "50000",
    "x": "NEW",
    "X": "NEW",
    "z": "0",
    "Z": "0",
    "l": "0",
    "L": "0",
    "n": "0",
    "N": "USDT",
    "T": 1704067200123,
    "t": null,
    "seq": 1
  },
  "seq": 1,
  "E": 1704067200123
}
```

### 深度数据 (depth)

```json
{
  "stream": "depth.BTCUSDT@100ms",
  "data": {
    "e": "depth",
    "E": 1704067200123,
    "s": "BTCUSDT",
    "U": 12345,
    "u": 12346,
    "b": [
      ["50000.00", "1.5"],
      ["49999.00", "2.0"]
    ],
    "a": [
      ["50001.00", "1.0"],
      ["50002.00", "0.5"]
    ]
  },
  "seq": 100,
  "E": 1704067200123
}
```

### 成交数据 (trade)

```json
{
  "stream": "trade.BTCUSDT",
  "data": {
    "e": "trade",
    "E": 1704067200123,
    "s": "BTCUSDT",
    "t": 10001,
    "p": "50000.50",
    "q": "0.01",
    "T": 1704067200123,
    "m": false
  },
  "seq": 50,
  "E": 1704067200123
}
```

## 测试环境配置

### 服务端口

| 服务 | 端口 | 类型 |
|------|------|------|
| API Gateway | 8082 | HTTP/WebSocket |
| User Core | 8099 | HTTP |
| OMS Core | 8083 | HTTP |
| Match Engine | 8084 | HTTP |
| Public Push | 8096 | WebSocket |
| Private Push | 8097 | WebSocket |

### Kafka Topics

| Topic | 用途 | 生产者 | 消费者 |
|-------|------|--------|--------|
| order-events | 订单命令流 | OMS Core | Match Engine |
| order-state-BTCUSDT | 订单状态 | Match Engine | OMS Core |
| private-order-state | 私有推送 | OMS Core | Private Push |
| market.depth.BTCUSDT | 深度数据 | Match Engine | Public Push |
| market.trade.BTCUSDT | 成交数据 | Match Engine | Public Push |
| market.ticker.BTCUSDT | 行情数据 | Match Engine | Public Push |

## 执行测试

### 快速测试

```bash
# 1. 启动所有服务（确保 Kafka、MySQL、Redis 已运行）
cd /Users/zhoufan/project/future-exchange
./start-all-services.sh

# 2. 运行 WebSocket E2E 测试
python3 websocket_e2e_test.py
```

### 手动测试步骤

```bash
# 1. 登录获取 Token
curl -X POST http://localhost:8082/api/v1/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser_ws_001","password":"Test@123456"}'

# 2. 使用返回的 Token 连接私有 WebSocket
# 使用 wscat 或浏览器 WebSocket 客户端
# ws://localhost:8097/ws/private?token=<token>

# 3. 订阅执行报告频道
# {"method": "SUBSCRIBE", "params": ["executionReport"], "id": 1}

# 4. 提交订单
curl -X POST http://localhost:8082/api/order/create \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "clientOrderId": "test-001",
    "symbol": "BTCUSDT",
    "side": "BUY",
    "type": "LIMIT",
    "price": "50000",
    "quantity": "0.01",
    "timeInForce": "GTC"
  }'

# 5. 在 WebSocket 客户端验证是否收到 executionReport 消息
```

## 常见问题排查

### 问题1: 私有 WebSocket 连接失败 (401)

**原因**: JWT Token 无效或过期

**排查步骤**:
1. 检查 User Core 日志：`grep "JWT" /tmp/user-core.log`
2. 检查 Private Push 日志：`grep "JWT" /tmp/private-push-core.log`
3. 确认 User Core 和 Private Push 使用相同的 JWT Secret

### 问题2: 订单提交成功但未收到执行报告

**原因**: Kafka Topic 未创建或消费者未启动

**排查步骤**:
1. 检查 Kafka Topics：`kafka-topics.sh --list --bootstrap-server localhost:9092`
2. 检查 OMS Core 日志：`grep "OrderStatePush" /tmp/oms-core.log`
3. 检查 Private Push 日志：`grep "OrderStateConsumer" /tmp/private-push-core.log`

### 问题3: 公有推送无数据

**原因**: Match Engine 未生成行情数据

**排查步骤**:
1. 检查 Match Engine 日志：`grep "market" /tmp/match-engine-core.log`
2. 检查 Kafka Topics 是否存在：`market.depth.BTCUSDT`
3. 检查 Public Push 日志：`grep "Received" /tmp/public-push-core.log`

## 性能指标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| 连接延迟 | < 100ms | 从请求到 connectionAck |
| 消息投递延迟 | < 200ms | 从事件发生到 WebSocket 推送 |
| 心跳响应 | < 50ms | 从 ping 到 pong |
| 并发连接 | > 10,000 | 单实例最大连接数 |
| 消息丢失率 | < 0.01% | 带 ACK 机制 |

## 测试报告模板

```markdown
# WebSocket E2E 测试报告

## 测试环境
- 日期: 2026-02-20
- 版本: v1.0.0
- 环境: dev

## 测试结果汇总

| 场景 | 状态 | 备注 |
|------|------|------|
| 公有推送连接 | ✅ 通过 | - |
| 私有推送认证 | ✅ 通过 | - |
| 订单提交+执行报告 | ✅ 通过 | - |
| 行情数据推送 | ⚠️ 待定 | 需 Market Maker 配合 |
| 并发连接 | ✅ 通过 | 50 用户并发 |

## 详细结果
...
```
