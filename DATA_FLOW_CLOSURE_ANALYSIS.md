# API Gateway → OMS → Match Engine 数据流闭环分析

## 🎯 核心问题

**api-gateway → oms-core → match-engine-core 数据流是否能够闭环？**

## 📊 当前数据流分析

### 完整数据流路径

```
┌─────────────────────────────────────────────────────────────────┐
│  1. API Gateway (8080)                                          │
│     POST /api/order/create                                      │
│     ↓ 同步 Feign 调用                                            │
└─────────────────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────────────────┐
│  2. OMS Core (8081)                                              │
│     - 参数校验                                                    │
│     - 幂等检查                                                    │
│     - 创建订单（NEW）                                             │
│     - 风控检查（同步）                                            │
│     - 冻结资金（同步）                                            │
│     - 更新状态=FROZEN                                             │
│     - 发送 OrderEvent → Kafka ⭐                                  │
│     ↓ 立即返回响应（状态=FROZEN）                                 │
└─────────────────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────────────────┐
│  3. Kafka: order-event-{symbol}                                 │
│     单分区，保证顺序                                              │
└─────────────────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────────────────┐
│  4. Match Engine Core (8083)                                     │
│     - Disruptor RingBuffer                                       │
│     - 单线程撮合                                                  │
│     - OrderBook 撮合                                              │
│     - 生成 TradeEvent → Kafka                                     │
│     - 生成 OrderStateEvent → Kafka ⭐                            │
└─────────────────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────────────────┐
│  5. Kafka: order-state-{symbol}                                 │
│     订单状态回传通道                                              │
└─────────────────────────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────────────────────────┐
│  6. OMS Core: OrderStateConsumer                                │
│     - 消费 order-state-{symbol}                                  │
│     - 更新订单状态（PARTIALLY_FILLED / FILLED）                  │
│     - 更新已成交数量                                              │
│     ⚠️ 但 api-gateway 无法实时知道状态变更                        │
└─────────────────────────────────────────────────────────────────┘
```

## ❌ 闭环问题分析

### 问题1：同步调用 vs 异步更新

**当前实现**：
- `api-gateway → oms-core`：**同步** Feign 调用，立即返回
- `oms-core → match-engine-core`：**异步** Kafka 发送
- `match-engine-core → oms-core`：**异步** Kafka 回传

**结果**：
- ✅ api-gateway 能立即知道订单提交成功（状态=FROZEN）
- ❌ api-gateway **无法实时知道**订单的最终成交状态（PARTIALLY_FILLED / FILLED）

### 问题2：缺少状态推送机制

**当前代码**：

```12:27:api-gateway/src/main/java/com/exchange/gateway/client/OmsClient.java
@FeignClient(name = "oms-core", path = "/internal/order")
public interface OmsClient {
    
    /**
     * 创建订单
     */
    @PostMapping("/create")
    CreateOrderResponse createOrder(@RequestBody CreateOrderRequest request);
    
    /**
     * 撤销订单
     */
    @PostMapping("/cancel")
    CreateOrderResponse cancelOrder(@RequestBody CancelOrderRequest request);
}
```

**问题**：
- OmsClient 只有 `createOrder` 和 `cancelOrder`
- **没有**订单状态推送接口
- **没有**WebSocket 连接

### 问题3：只能通过轮询查询

**当前实现**：

```237:259:oms-core/src/main/java/com/exchange/oms/service/impl/OmsServiceImpl.java
    @Override
    public QueryOrderResponse queryOrder(QueryOrderRequest request) {
        Long orderId = Long.parseLong(request.getOrderId());
        OmsOrder order = orderMapper.selectById(orderId);
        
        if (order == null || !order.getUserId().equals(request.getUserId())) {
            return null;
        }
        
        QueryOrderResponse response = new QueryOrderResponse();
        response.setOrderId(order.getId().toString());
        response.setClientOrderId(order.getClientOrderId());
        response.setSymbol(order.getSymbol());
        response.setSide(mapOrderSide(order.getSide()));
        response.setType(mapOrderType(order.getType()));
        response.setPrice(order.getPrice() != null ? order.getPrice().toPlainString() : null);
        response.setQuantity(order.getQuantity().toPlainString());
        response.setFilledQuantity(order.getFilledQuantity().toPlainString());
        response.setStatus(mapOrderStatus(order.getStatus()));
        response.setCreateTime(order.getCreatedAt());
        
        return response;
    }
```

**问题**：
- 客户端必须**主动轮询**查询订单状态
- 无法实时推送状态变更
- 增加服务器压力和延迟

## ✅ 解决方案

### 方案1：WebSocket 推送（推荐）⭐

**架构设计**：

```
┌─────────────────────────────────────────────────────────────────┐
│  API Gateway (8080)                                              │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  WebSocket Server                                          │  │
│  │  - 连接管理                                                │  │
│  │  - 订阅订单状态                                            │  │
│  │  - 推送状态变更                                            │  │
│  └────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                    ↑
                    │ Kafka: order-state-event
                    │
┌─────────────────────────────────────────────────────────────────┐
│  OMS Core (8081)                                                 │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  OrderStateConsumer                                        │  │
│  │  - 消费 order-state-{symbol}                               │  │
│  │  - 更新订单状态                                            │  │
│  │  - 发布 order-state-event → Kafka                          │  │
│  └────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

**实现步骤**：

1. **OMS Core 发布状态变更事件**：
```java
// OMS Core: OrderStateConsumer
@KafkaListener(topics = "order-state-{symbol}")
public void consumeOrderState(OrderStateEvent event) {
    // 更新订单状态
    orderMapper.updateStatus(...);
    
    // 发布状态变更事件到 Kafka
    orderStateEventPublisher.publish(event);
}
```

2. **API Gateway 消费并推送**：
```java
// API Gateway: OrderStatePushConsumer
@KafkaListener(topics = "order-state-event")
public void consumeAndPush(OrderStateEvent event) {
    // 查找订阅该订单的 WebSocket 连接
    WebSocketSession session = sessionManager.getSession(event.getUserId());
    
    // 推送状态变更
    session.sendMessage(new TextMessage(toJson(event)));
}
```

3. **客户端 WebSocket 连接**：
```javascript
const ws = new WebSocket('ws://api-gateway:8080/ws/order');
ws.onmessage = (event) => {
    const orderState = JSON.parse(event.data);
    console.log('Order state updated:', orderState);
};
```

**优点**：
- ✅ 实时推送，延迟 < 100ms
- ✅ 减少轮询压力
- ✅ 用户体验好

**缺点**：
- ⚠️ 需要维护 WebSocket 连接
- ⚠️ 需要处理连接断开和重连

### 方案2：长轮询（Long Polling）

**实现**：

```java
// API Gateway: OrderController
@GetMapping("/order/{orderId}/status")
public ResponseEntity<OrderStatusResponse> getOrderStatus(
        @PathVariable String orderId,
        @RequestParam(defaultValue = "5000") long timeout) {
    
    // 长轮询：等待状态变更或超时
    OrderStatusResponse response = orderStatusService.waitForStatusChange(
        orderId, timeout);
    
    return ResponseEntity.ok(response);
}
```

**优点**：
- ✅ 实现简单
- ✅ 兼容性好（HTTP）

**缺点**：
- ⚠️ 服务器资源占用（保持连接）
- ⚠️ 延迟较高（最多等待 timeout）

### 方案3：事件总线（Event Bus）

**实现**：

```java
// API Gateway: OrderStateEventBus
@Component
public class OrderStateEventBus {
    
    private final Map<Long, List<CompletableFuture<OrderStateEvent>>> 
        pendingRequests = new ConcurrentHashMap<>();
    
    // 客户端请求状态
    public CompletableFuture<OrderStateEvent> waitForStatusChange(
            Long orderId, long timeout) {
        CompletableFuture<OrderStateEvent> future = new CompletableFuture<>();
        pendingRequests.computeIfAbsent(orderId, k -> new ArrayList<>())
            .add(future);
        
        // 超时处理
        CompletableFuture.delayedExecutor(timeout, TimeUnit.MILLISECONDS)
            .execute(() -> future.completeExceptionally(new TimeoutException()));
        
        return future;
    }
    
    // 消费 Kafka 并完成 Future
    @KafkaListener(topics = "order-state-event")
    public void onOrderStateChange(OrderStateEvent event) {
        List<CompletableFuture<OrderStateEvent>> futures = 
            pendingRequests.remove(event.getOrderId());
        if (futures != null) {
            futures.forEach(f -> f.complete(event));
        }
    }
}
```

**优点**：
- ✅ 实时响应
- ✅ 不需要 WebSocket

**缺点**：
- ⚠️ 需要维护 Future 映射
- ⚠️ 内存占用

## 📋 推荐方案：WebSocket + Kafka

### 完整架构

```
┌─────────────────────────────────────────────────────────────────┐
│  Client                                                          │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  WebSocket Connection                                      │  │
│  │  ws://api-gateway:8080/ws/order                            │  │
│  └────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                    ↑ WebSocket
                    │
┌─────────────────────────────────────────────────────────────────┐
│  API Gateway (8080)                                              │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  WebSocketHandler                                          │  │
│  │  - 连接管理                                                │  │
│  │  - 订阅订单状态                                            │  │
│  └────────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  OrderStatePushConsumer                                    │  │
│  │  @KafkaListener(topics = "order-state-event")             │  │
│  │  - 消费状态变更事件                                        │  │
│  │  - 推送到 WebSocket                                        │  │
│  └────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                    ↑ Kafka: order-state-event
                    │
┌─────────────────────────────────────────────────────────────────┐
│  OMS Core (8081)                                                 │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  OrderStateConsumer                                        │  │
│  │  @KafkaListener(topics = "order-state-{symbol}")          │  │
│  │  - 更新订单状态                                            │  │
│  └────────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  OrderStateEventPublisher                                 │  │
│  │  - 发布 order-state-event → Kafka                          │  │
│  └────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                    ↑ Kafka: order-state-{symbol}
                    │
┌─────────────────────────────────────────────────────────────────┐
│  Match Engine Core (8083)                                        │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  TradePublisher                                            │  │
│  │  - 发布 order-state-{symbol}                               │  │
│  └────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### 实现要点

1. **Kafka Topic 设计**：
   - `order-state-{symbol}`：Match Engine → OMS（按 symbol 分区）
   - `order-state-event`：OMS → API Gateway（全局事件）

2. **WebSocket 连接管理**：
   - 用户ID → WebSocket Session 映射
   - 订单ID → 订阅列表
   - 心跳保活机制

3. **幂等性保证**：
   - 订单状态变更事件包含 sequence
   - 客户端去重处理

## 🎯 结论

### 当前状态

**❌ 数据流无法完全闭环**

- ✅ `api-gateway → oms-core → match-engine-core`：**单向数据流**正常
- ✅ `match-engine-core → oms-core`：**状态回传**正常
- ❌ `oms-core → api-gateway`：**缺少实时推送机制**

### 解决方案

**推荐使用 WebSocket + Kafka 实现完整闭环**：

1. OMS Core 消费 `order-state-{symbol}` 并发布 `order-state-event`
2. API Gateway 消费 `order-state-event` 并推送到 WebSocket
3. 客户端通过 WebSocket 实时接收订单状态变更

### 实施优先级

| 方案 | 优先级 | 实施难度 | 用户体验 |
|------|--------|---------|---------|
| WebSocket 推送 | ⭐⭐⭐⭐⭐ | 中 | 优秀 |
| 长轮询 | ⭐⭐⭐ | 低 | 良好 |
| 事件总线 | ⭐⭐⭐⭐ | 中 | 优秀 |

---

*最后更新：2024年2月*

