# 私有推送系统实现总结

> **版本**: v1.0.0  
> **最后更新**: 2026-02-19  
> **状态**: 已实现

---

## 1. 概述

私有推送系统 (Private Push System) 已完成核心功能实现，提供实时的用户私有数据推送服务，包括：

- **订单状态推送** (executionReport): 完全成交、部分成交、撤单、拒绝
- **账户资金变化** (account): 余额变动实时通知
- **持仓变化** (position): 仓位变动实时通知

---

## 2. 架构设计

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              客户端层 (Clients)                              │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐            │
│  │  Web App│  │Mobile App│  │ 量化程序 │  │ 第三方  │  │  其他   │            │
│  └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘            │
│       └────────────┴────────────┴────────────┴────────────┘                 │
│                              WSS (TLS)                                      │
└──────────────────────────────┬──────────────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────────────┐
│                      私有推送服务 (Private Push Service)                     │
│                              Port: 8099                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     WebSocket Gateway                                │   │
│  │  - 用户认证 (JWT)                                                    │   │
│  │  - Session管理                                                       │   │
│  │  - 心跳检测                                                          │   │
│  │  - ACK确认机制                                                       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     Subscription Manager                             │   │
│  │  - 频道订阅管理 (executionReport, account, position)                  │   │
│  │  - 用户-频道映射                                                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     Message Dispatcher                               │   │
│  │  - 消息路由 (userId → sessions)                                       │   │
│  │  - ACK与重发机制                                                      │   │
│  │  - 限流控制                                                           │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     Kafka Consumer                                   │   │
│  │  - private-order-state (订单状态)                                    │   │
│  │  - private-account-change (账户变化)                                 │   │
│  │  - private-position-change (持仓变化)                                │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 核心模块

### 3.1 新增文件清单

```
# 需求文档
docs/requirements/07_private_push_requirements.md         # PRD需求文档
docs/requirements/07_private_push_frontend_guide.md       # 前端实现指南

# 事件定义 (common-proto)
common-proto/src/main/java/com/exchange/common/proto/event/PrivatePushEvent.java

# OMS增强
oms-core/src/main/java/com/exchange/oms/publisher/OrderStatePushPublisher.java
oms-core/src/main/java/com/exchange/oms/dto/OrderStateEventDTO.java          (增强)
oms-core/src/main/java/com/exchange/oms/consumer/OrderStateConsumer.java     (增强)

# 私有推送服务模块
private-push-core/
├── pom.xml
├── src/main/java/com/exchange/privatepush/
│   ├── PrivatePushApplication.java
│   ├── config/
│   │   ├── WebSocketConfig.java
│   │   └── PrivatePushProperties.java
│   ├── handler/
│   │   └── PrivateWebSocketHandler.java
│   ├── service/
│   │   ├── SessionManager.java
│   │   ├── SubscriptionManager.java
│   │   └── MessageDispatcher.java
│   ├── consumer/
│   │   └── OrderStateConsumer.java
│   └── model/
│       └── SessionMetadata.java
└── src/main/resources/
    └── application.yml

# Kafka初始化脚本
init_kafka.sh                                               (增强)
pom.xml                                                     (增强)
```

---

## 4. 数据流详解

### 4.1 订单部分成交推送流

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                              订单部分成交流程                                   │
└──────────────────────────────────────────────────────────────────────────────┘

用户下单 BUY 1.0 BTC @ 50000

T0: 用户下单
    客户端 ──REST──▶ OMS ──Kafka──▶ Match Engine
    前端本地创建订单 (临时ID)，显示在"当前委托"

T1: 订单进入撮合
    Match Engine 撮合: 成交 0.3 BTC
    
    Match Engine ──Kafka──▶ OMS (order-state-BTCUSDT)
    {
        "orderId": 12345,
        "status": "PARTIALLY_FILLED",
        "filledQuantityDelta": "0.3",
        "lastFilledPrice": "50000",
        "tradeId": 987654321
    }

T2: OMS处理
    OMS Consumer:
    - 更新订单状态 (MySQL)
    - 记录状态日志
    - 发布到 private-order-state Topic
    
    OMS ──Kafka──▶ Private Push (private-order-state)
    {
        "e": "executionReport",
        "E": 1708326400123,
        "s": "BTCUSDT",
        "i": 12345,
        "X": "PARTIALLY_FILLED",
        "x": "TRADE",
        "q": "1.0",
        "z": "0.3",           // 累计已成交
        "l": "0.3",           // 本次成交
        "L": "50000",
        "seq": 10001
    }

T3: 私有推送服务处理
    Private Push Consumer:
    - 解析 userId
    - 查找用户会话
    - 检查订阅 (executionReport)
    - 生成序列号
    - 发送 WebSocket 消息
    
    WebSocket ──▶ 客户端
    {
        "stream": "executionReport",
        "data": { ... },
        "seq": 10001,
        "E": 1708326400150
    }

T4: 客户端处理
    OrderStateManager:
    - 检查 seq (去重)
    - 更新订单状态
    - 触发 UI 更新
    - 发送 ACK
    
    UI 显示: "已成交 0.3/1.0 (30%)"

T5: 第二次成交 0.5 BTC (同上流程)
    UI 显示: "已成交 0.8/1.0 (80%)"

T6: 第三次成交 0.2 BTC (完全成交)
    UI 显示: "已完成"
    订单从"当前委托"移到"历史委托"
```

---

## 5. 前端部分成交处理

### 5.1 核心逻辑

```typescript
// 关键：使用服务端累计值
class OrderStateManager {
  handleExecutionReport(report: ExecutionReport, seq: number) {
    // 1. 去重检查
    if (seq <= this.lastSeq) return;
    this.lastSeq = seq;
    
    // 2. 获取订单
    const order = this.orders.get(report.i);
    if (!order) return;
    
    // 3. 更新状态 (关键：使用服务端累计值 z)
    order.status = report.X;
    order.filledQuantity = report.z;  // 累计已成交
    
    // 4. 触发事件
    if (report.X === 'PARTIALLY_FILLED') {
      this.emit('orderPartiallyFilled', {
        order,
        thisFill: report.l,      // 本次成交
        totalFilled: report.z    // 累计成交
      });
    }
  }
}
```

### 5.2 UI 更新示例

```typescript
// React 组件
const OrderRow = ({ order }) => {
  const fillPercentage = useMemo(() => {
    const filled = parseFloat(order.filledQuantity);
    const total = parseFloat(order.quantity);
    return (filled / total) * 100;
  }, [order.filledQuantity, order.quantity]);
  
  return (
    <tr>
      <td>{order.symbol}</td>
      <td>
        <ProgressBar percentage={fillPercentage} />
        <span>{order.filledQuantity}/{order.quantity}</span>
      </td>
      <td><StatusBadge status={order.status} /></td>
    </tr>
  );
};
```

---

## 6. ACK 机制

### 6.1 为什么需要 ACK？

- **确保消息到达**: 客户端必须确认收到消息
- **自动重发**: 未 ACK 的消息自动重发 (最多3次)
- **断线检测**: 多次重发失败断开连接

### 6.2 流程

```
服务端 ──WebSocket──▶ 客户端: { seq: 10001, data: {...} }
                         │
                         ▼
                      处理消息
                         │
                         ▼
客户端 ──WebSocket──▶ 服务端: { method: "ACK", seq: 10001 }

(如果服务端 5s 内未收到 ACK)
服务端: 重发消息 (retryCount++)
(如果 retryCount >= 3)
服务端: 断开连接，让客户端重连
```

---

## 7. 端口与配置

| 服务 | 端口 | 说明 |
|------|------|------|
| Public Push | 8096 | 公有行情推送 |
| **Private Push** | **8099** | **私有数据推送** |
| OMS | 8081 | 订单管理 |
| Match Engine | 8083 | 撮合引擎 |

### 7.1 Kafka Topics

| Topic | 分区数 | 说明 |
|-------|--------|------|
| private-order-state | 100 | 订单状态推送 (按 userId 分区) |
| private-account-change | 100 | 账户变化推送 |
| private-position-change | 100 | 持仓变化推送 |

---

## 8. 启动顺序

```bash
# 1. 启动基础设施
zookeeper-server-start.sh config/zookeeper.properties
kafka-server-start.sh config/server.properties
redis-server

# 2. 初始化 Kafka Topics
./init_kafka.sh

# 3. 启动核心服务
cd match-engine-core && mvn spring-boot:run      # Port 8083
cd ledger-core && mvn spring-boot:run            # Port 8084
cd snapshot-account-core && mvn spring-boot:run  # Port 8085
cd position-snapshot-core && mvn spring-boot:run # Port 8086

# 4. 启动 OMS
cd oms-core && mvn spring-boot:run               # Port 8081

# 5. 启动推送服务
cd public-push-core && mvn spring-boot:run       # Port 8096
cd private-push-core && mvn spring-boot:run      # Port 8099

# 6. 启动网关
cd api-gateway && mvn spring-boot:run            # Port 8080
```

---

## 9. 测试验证

### 9.1 WebSocket 连接测试

```javascript
const ws = new WebSocket('ws://localhost:8099/ws/private?token=12345');

ws.onopen = () => {
  // 订阅 executionReport
  ws.send(JSON.stringify({
    method: 'SUBSCRIBE',
    params: ['executionReport'],
    id: 1
  }));
};

ws.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log('Received:', data);
  
  // 发送 ACK
  if (data.seq) {
    ws.send(JSON.stringify({
      method: 'ACK',
      seq: data.seq,
      id: 2
    }));
  }
};
```

### 9.2 下单测试

```bash
# 创建限价买单
curl -X POST http://localhost:8080/api/order/create \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer 12345" \
  -d '{
    "userId": 12345,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": "5000000000000",
    "quantity": "100000000"
  }'
```

---

## 10. 后续优化

### 10.1 已规划

- [ ] Redis Pub/Sub 集群支持
- [ ] 消息持久化 (离线消息)
- [ ] 多终端同步
- [ ] 监控大盘

### 10.2 注意事项

1. **序列号生成**: 生产环境应使用 Redis 分布式序列号
2. **JWT 验证**: 当前为简化实现，生产环境需完善
3. **限流**: 需根据实际场景调整限流阈值

---

*文档结束*
