# OMS与Match Engine交互架构完整说明

## 🎯 核心问题

**OMS（订单管理系统）和Match Engine（撮合引擎）是如何交互的？**

## 📋 完整交互流程

### 标准流程图

```
用户 → API Gateway
         ↓
      OMS Core
         ↓
    [1] 参数校验
         ↓
    [2] 幂等检查
         ↓
    [3] 创建订单（状态=NEW）
         ↓
    [4] 调用Hard Risk Gate（同步）
         ↓
         PASS → 继续
         REJECT → 拒单返回
         ↓
    [5] 调用Account Service冻结资金（同步）
         ↓
         SUCCESS → 更新状态=FROZEN
         FAIL → 拒单返回
         ↓
    [6] 发送OrderEvent到Match Engine ⭐
         ↓
         Kafka Topic: order-event-{symbol}
         或
         gRPC/Feign直连
         ↓
  Match Engine Core
         ↓
    Disruptor RingBuffer
         ↓
    MatchingProcessor（单线程撮合）
         ↓
    OrderBook撮合
         ↓
    生成TradeEvent ⭐
         ↓
    [7] 发布TradeEvent（Kafka）
         ↓
         ├─→ Ledger Service（资金结算）
         ├─→ Position Service（持仓更新）
         └─→ Clearing Service（清算）
         ↓
    [8] 发布OrderStateEvent（回传OMS）⭐
         ↓
    OMS接收状态更新
         ↓
    更新订单状态（PARTIALLY_FILLED / FILLED）
```

## 🔑 两种交互模式

### 模式A：Feign/gRPC 直连（当前实现）

**优点**：
- ✅ 实现简单
- ✅ 延迟最低
- ✅ 适合Demo/MVP

**缺点**：
- ❌ 无法Replay
- ❌ 无顺序日志
- ❌ Match Engine宕机=订单丢失
- ❌ 无法灾备恢复

**代码实现**：

```java
// OMS侧
@FeignClient(name = "match-engine-core")
public interface MatchEngineClient {
    @PostMapping("/internal/match/submit")
    void submitOrder(@RequestBody OrderCommand command);
}

// OMS发送订单
matchEngineClient.submitOrder(orderCommand);

// Match Engine接收
@PostMapping("/internal/match/submit")
public void submitOrder(@RequestBody OrderCommand command) {
    disruptorEngine.submitOrderCommand(command);
}
```

### 模式B：Kafka中间层（生产级推荐）⭐

**优点**：
- ✅ 顺序日志（Per Symbol）
- ✅ 可Replay
- ✅ 可灾备
- ✅ 可对账
- ✅ 解耦OMS与Match
- ✅ Active-Active支持

**缺点**：
- ⚠️ 延迟增加0.5-2ms（可接受）
- ⚠️ 需要Kafka集群

**架构图**：

```
OMS → Kafka Topic: order-event-BTCUSDT (单分区)
                    ↓
              Match Engine消费
                    ↓
              Disruptor处理
                    ↓
              OrderBook撮合
                    ↓
Match Engine → Kafka Topic: trade-event (全局)
                    ↓
              Ledger/Position/Clearing消费
                    
Match Engine → Kafka Topic: order-state-BTCUSDT
                    ↓
              OMS消费更新状态
```

## 💻 完整代码实现（推荐：Kafka模式）

### 1. OMS发送订单到Kafka

```java
// OMS侧：OrderEventPublisher
@Component
public class OrderEventPublisher {
    
    @Autowired
    private KafkaTemplate<String, OrderCommand> kafkaTemplate;
    
    public void publishOrderEvent(OrderCommand command) {
        String topic = "order-event-" + command.getSymbol();
        
        // 使用orderId作为key，保证同一订单的event顺序
        kafkaTemplate.send(topic, command.getOrderId().toString(), command);
        
        log.info("[OMS] Published order event, orderId={}, topic={}", 
            command.getOrderId(), topic);
    }
}

// OMS Service中调用
public SubmitOrderResponse submitOrder(SubmitOrderRequest request) {
    // ... 前面的风控、冻结等逻辑 ...
    
    // 11. 发送到Kafka
    OrderCommand command = buildOrderCommand(order);
    orderEventPublisher.publishOrderEvent(command);
    
    return SubmitOrderResponse.success(...);
}
```

### 2. Match Engine从Kafka消费

```java
// Match Engine侧：OrderEventConsumer
@Component
@Slf4j
public class OrderEventConsumer {
    
    @Autowired
    private DisruptorEngine disruptorEngine;
    
    @Value("${match.symbol}")
    private String symbol;
    
    /**
     * 消费订单事件
     * 
     * Topic: order-event-{symbol}
     * 单分区：保证顺序
     */
    @KafkaListener(
        topics = "order-event-#{@matchEngineConfig.symbol}",
        groupId = "match-engine-#{@matchEngineConfig.symbol}",
        concurrency = "1"  // 单线程消费，保证顺序
    )
    public void consumeOrderEvent(OrderCommand command) {
        log.info("[MatchEngine] Consume order event, orderId={}, type={}", 
            command.getOrderId(), command.getEventType());
        
        // 提交到Disruptor
        disruptorEngine.submitOrderCommand(command);
    }
}
```

### 3. Match Engine发布成交事件

```java
// Match Engine侧：TradePublisher（增强版）
@Component
public class TradePublisher {
    
    @Autowired
    private KafkaTemplate<String, Trade> tradeKafkaTemplate;
    
    @Autowired
    private KafkaTemplate<String, OrderStateEvent> stateKafkaTemplate;
    
    /**
     * 发布成交事件
     */
    public void publishTrade(Trade trade) {
        String topic = "trade-event";
        
        // TradeEvent是全局的，所有服务都会消费
        tradeKafkaTemplate.send(topic, trade.getTradeId(), trade);
        
        log.info("[TradePublisher] Published trade, tradeId={}, symbol={}", 
            trade.getTradeId(), trade.getSymbol());
    }
    
    /**
     * 发布订单状态事件（回传OMS）
     */
    public void publishOrderState(OrderStateEvent event) {
        String topic = "order-state-" + event.getSymbol();
        
        stateKafkaTemplate.send(topic, event.getOrderId().toString(), event);
        
        log.info("[TradePublisher] Published order state, orderId={}, status={}", 
            event.getOrderId(), event.getStatus());
    }
}
```

### 4. OMS消费订单状态更新

```java
// OMS侧：OrderStateConsumer
@Component
@Slf4j
public class OrderStateConsumer {
    
    @Autowired
    private OmsOrderMapper orderMapper;
    
    /**
     * 消费订单状态事件（来自Match Engine）
     */
    @KafkaListener(
        topics = "order-state-BTCUSDT,order-state-ETHUSDT",  // 监听多个Symbol
        groupId = "oms-order-state",
        concurrency = "4"  // 可并发消费不同Symbol
    )
    public void consumeOrderState(OrderStateEvent event) {
        log.info("[OMS] Consume order state, orderId={}, status={}, filledDelta={}", 
            event.getOrderId(), event.getStatus(), event.getFilledQuantityDelta());
        
        // 更新订单状态
        OmsOrder order = orderMapper.selectById(event.getOrderId());
        if (order == null) {
            log.warn("[OMS] Order not found, orderId={}", event.getOrderId());
            return;
        }
        
        // 更新已成交数量
        if (event.getFilledQuantityDelta() != null) {
            order.setFilledQuantity(
                order.getFilledQuantity().add(event.getFilledQuantityDelta())
            );
        }
        
        // 更新状态
        switch (event.getStatus()) {
            case "PARTIALLY_FILLED":
                order.setStatus(3);
                break;
            case "FILLED":
                order.setStatus(4);
                break;
            case "CANCELED":
                order.setStatus(5);
                break;
        }
        
        order.setUpdatedAt(System.currentTimeMillis());
        orderMapper.updateById(order);
        
        log.info("[OMS] Order state updated, orderId={}, newStatus={}", 
            event.getOrderId(), order.getStatus());
    }
}
```

## 📊 关键Topic设计

### 1. order-event-{symbol}

**作用**：OMS → Match Engine

**特性**：
- 每个Symbol一个Topic
- 单分区（保证顺序）
- Key = orderId

**消息类型**：
- ORDER_SUBMIT
- ORDER_CANCEL
- ORDER_FORCE_CANCEL

### 2. trade-event

**作用**：Match Engine → 全系统

**特性**：
- 全局Topic
- 多分区（按tradeId分区）
- 🔥 资金唯一来源

**消费者**：
- Ledger Service（资金结算）
- Position Service（持仓更新）
- Clearing Service（清算）
- WebSocket Service（实时推送）

### 3. order-state-{symbol}

**作用**：Match Engine → OMS

**特性**：
- 每个Symbol一个Topic
- 单分区（按orderId分区）
- Key = orderId

**消息类型**：
- PARTIALLY_FILLED
- FILLED
- CANCELED

## 🔄 完整交互时序

### 场景1：限价单完全成交

```
1. 用户下单：BUY BTCUSDT, price=43000, qty=0.1
   ↓
2. OMS创建订单（orderId=10001, status=NEW）
   ↓
3. OMS调用Hard Risk → PASS
   ↓
4. OMS冻结资金 → SUCCESS
   ↓
5. OMS发送到Kafka：
   Topic: order-event-BTCUSDT
   Message: {
     "eventType": "ORDER_SUBMIT",
     "orderId": 10001,
     "side": "BUY",
     "price": "43000",
     "quantity": "0.1"
   }
   ↓
6. Match Engine消费 → Disruptor → MatchingProcessor
   ↓
7. OrderBook撮合 → 找到卖单20001（price=43000, qty=0.1）
   ↓
8. 生成TradeEvent：
   {
     "tradeId": "BTCUSDT-xxx-1",
     "makerOrderId": 20001,
     "takerOrderId": 10001,
     "price": "43000",
     "quantity": "0.1"
   }
   ↓
9. Match Engine发布：
   Topic: trade-event
   → Ledger消费，进行资金结算
   ↓
10. Match Engine发布：
    Topic: order-state-BTCUSDT
    Message: {
      "orderId": 10001,
      "status": "FILLED",
      "filledQuantityDelta": "0.1"
    }
    ↓
11. OMS消费状态更新
    ↓
12. OMS更新订单（orderId=10001, status=FILLED）
```

## ⚡ 性能考虑

### Kafka延迟

- **单机Kafka**：~0.5ms
- **集群Kafka (3节点)**：~1-2ms
- **跨数据中心**：~5-10ms

### 优化建议

1. **Kafka配置优化**：
```properties
# Producer
acks=1                    # 不需要all（牺牲极端情况的可靠性换取性能）
linger.ms=0              # 不等待batch
compression.type=none     # 不压缩

# Consumer
fetch.min.bytes=1        # 不等待batch
fetch.max.wait.ms=0      # 立即返回
```

2. **Topic分区策略**：
- order-event-{symbol}：单分区（保证顺序）
- trade-event：多分区（提高吞吐）

3. **本地Kafka集群**：
- Match Engine和Kafka部署在同一机房
- 使用SSD磁盘

## 🎯 推荐方案

### 当前阶段（MVP/Demo）：
✅ **使用Feign直连**
- 简单快速
- 易于调试
- 满足功能验证

### 生产阶段：
✅ **必须切换到Kafka**
- 可重放
- 可灾备
- 可审计
- 合规要求

## 📝 TODO清单

### OMS侧需要添加：
- [ ] OrderEventPublisher（发送订单到Kafka）
- [ ] OrderStateConsumer（消费状态更新）
- [ ] Kafka配置

### Match Engine侧需要添加：
- [ ] OrderEventConsumer（从Kafka消费订单）
- [ ] TradePublisher增强（发送到Kafka）
- [ ] Kafka配置

### 新增模块：
- [ ] Ledger Service（消费TradeEvent）
- [ ] Position Service（消费TradeEvent）
- [ ] Clearing Service（消费TradeEvent）

---

## 总结

**关键点**：
1. ⭐ **OMS和Match Engine通过Event解耦**
2. ⭐ **生产环境必须使用Kafka（或等价的顺序日志）**
3. ⭐ **TradeEvent是资金的唯一来源**
4. ⭐ **顺序保证：Kafka单分区 + Disruptor单线程**
5. ⭐ **确定性：相同输入 → 相同输出 → 可重放**

**当前实现vs理想实现**：
- 当前：Feign直连（适合Demo）
- 理想：Kafka中间层（生产级）

我会为您补充完整的Kafka集成代码！

