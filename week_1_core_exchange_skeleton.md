# Week 1 交易所核心系统 — SpringCloud + SpringBoot + Nacos 工程骨架（Cursor级）

> 目标：1天内用 Cursor 快速生成可跑通：
> API Gateway → OMS → Hard Risk → Disruptor Match → Ledger → Snapshot → Replay

---

## 一、整体仓库结构（MonoRepo）

```
exchange-core/
├── pom.xml
├── common-core/
├── common-proto/
├── api-gateway/
├── oms-core/
├── hard-risk-core/
├── match-engine-core/
├── ledger-core/
├── snapshot-core/
├── replay-core/
└── deploy/
```

---

## 二、父 pom.xml（统一依赖管理）

```xml
<project>
  <groupId>com.exchange</groupId>
  <artifactId>exchange-core</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <modules>
    <module>common-core</module>
    <module>common-proto</module>
    <module>api-gateway</module>
    <module>oms-core</module>
    <module>hard-risk-core</module>
    <module>match-engine-core</module>
    <module>ledger-core</module>
    <module>snapshot-core</module>
    <module>replay-core</module>
  </modules>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-dependencies</artifactId>
        <version>2023.0.0</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

---

## 三、common-core（基础模型 + ID + 时间 + 金额规范）

### Money
```java
public final class Money {
    public static final long SCALE = 100_000_000L;
    public static long of(double v) {
        return (long)(v * SCALE);
    }
}
```

### CoreEnums
```java
public enum Side { BUY, SELL }
public enum OrderType { LIMIT, MARKET }
public enum LedgerDirection { DEBIT, CREDIT }
```

---

## 四、api-gateway（统一入口）

### 依赖
- spring-cloud-starter-gateway
- nacos-discovery

### Application
```java
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApp {}
```

### OrderController
```java
@RestController
@RequestMapping("/api/order")
public class OrderController {

    @Autowired
    private OmsClient omsClient;

    @PostMapping("/create")
    public CreateOrderResponse create(@RequestBody CreateOrderRequest req) {
        return omsClient.createOrder(req);
    }
}
```

### OmsClient (Feign)
```java
@FeignClient("oms-core")
public interface OmsClient {
    @PostMapping("/internal/order/create")
    CreateOrderResponse createOrder(CreateOrderRequest req);
}
```

---

## 五、oms-core（订单管理）

### OrderService
```java
public interface OrderService {
    Order create(CreateOrderRequest req);
}
```

### OrderController
```java
@RestController
@RequestMapping("/internal/order")
public class OrderInternalController {

    @Autowired HardRiskClient hardRiskClient;
    @Autowired MatchClient matchClient;

    @PostMapping("/create")
    public CreateOrderResponse create(CreateOrderRequest req) {
        HardRiskResult risk = hardRiskClient.check(req);
        if (!risk.isPass()) throw new RuntimeException(risk.getReason());

        OrderCommand cmd = OrderMapper.toCommand(req);
        matchClient.submit(cmd);
        return new CreateOrderResponse(cmd.getOrderId());
    }
}
```

---

## 六、hard-risk-core（同步硬风控）

### HardRiskService
```java
public interface HardRiskService {
    HardRiskResult check(OrderCommand cmd, AccountSnapshot snapshot);
}
```

### HardRiskController
```java
@RestController
@RequestMapping("/internal/risk")
public class HardRiskController {

    @Autowired HardRiskService hardRiskService;

    @PostMapping("/check")
    public HardRiskResult check(@RequestBody OrderCommand cmd) {
        AccountSnapshot snap = loadSnapshot(cmd.getUserId());
        return hardRiskService.check(cmd, snap);
    }
}
```

---

## 七、match-engine-core（Disruptor 撮合内核）

### Disruptor 启动器
```java
@Component
public class MatchDisruptorEngine {

    private Disruptor<OrderCommandEvent> disruptor;

    @PostConstruct
    public void start() {
        disruptor = new Disruptor<>(
            OrderCommandEvent::new,
            1024 * 1024,
            Executors.defaultThreadFactory()
        );

        disruptor.handleEventsWith(new MatchEventHandler());
        disruptor.start();
    }

    public void submit(OrderCommand cmd) {
        RingBuffer<OrderCommandEvent> rb = disruptor.getRingBuffer();
        long seq = rb.next();
        try {
            rb.get(seq).setCmd(cmd);
        } finally {
            rb.publish(seq);
        }
    }
}
```

### MatchEventHandler
```java
public class MatchEventHandler implements EventHandler<OrderCommandEvent> {

    private final MatchEngine engine = new MatchEngine();

    @Override
    public void onEvent(OrderCommandEvent event, long seq, boolean end) {
        List<TradeEvent> trades = engine.onOrder(event.getCmd());
        trades.forEach(TradeBus::publish);
        MatchWAL.append(event.getCmd(), trades);
    }
}
```

---

## 八、ledger-core（双录账本）

### LedgerService
```java
public interface LedgerService {
    void postTrade(TradeEvent trade);
}
```

### LedgerServiceImpl
```java
@Service
public class LedgerServiceImpl implements LedgerService {

    @Override
    public void postTrade(TradeEvent trade) {
        List<LedgerEntry> entries = TradeLedgerGenerator.generate(trade);
        entries.forEach(LedgerWAL::append);
    }
}
```

---

## 九、snapshot-core（账户 + 持仓快照）

### AccountSnapshotService
```java
public interface AccountSnapshotService {
    AccountSnapshot get(long userId);
    void apply(LedgerEntry entry);
}
```

### PositionSnapshotService
```java
public interface PositionSnapshotService {
    void apply(TradeEvent trade);
}
```

---

## 十、replay-core（灾备重放系统）

### ReplayRunner
```java
@Component
public class ReplayRunner {

    public void replayAll() {
        replayMatch();
        replayLedger();
        rebuildSnapshot();
    }
}
```

---

## 十一、WAL 设计（所有核心必须有）

```
/data/
 ├── match.log
 ├── trade.log
 ├── ledger.log
```

### MatchWAL
```java
public class MatchWAL {
    public static void append(OrderCommand cmd, List<TradeEvent> trades) {
        // append-only
    }
}
```

---

## 十二、Week 1 你用 Cursor 的标准 Prompt

> "基于 SpringBoot + Nacos，为我生成 match-engine-core 模块，包含 Disruptor 撮合内核，为我生成一个生产级撮合 OrderBook，使用 PriceLadder + PriceLevel + Intrusive OrderNode，不允许使用 TreeMap / PriorityQueue，支持限价单和市价单撮合，包含 BestPriceTracker，单线程撮合，适配 Disruptor EventHandler。支持限价单撮合"

> "为我生成 ledger-core，包含双录账本，LedgerEntry，TradeLedgerGenerator，WAL 持久化"

> "为我生成 replay-core，实现从 match.log + ledger.log 重建 snapshot"

---

## 十三、你 Week 1 验收标准（极重要）

- [ ] curl 下单成功
- [ ] 撮合产生成交
- [ ] ledger.log 有双分录
- [ ] kill 服务
- [ ] ReplayRunner 重建 snapshot
- [ ] 账户余额一致

---

## 这是交易所核心团队真实工程风格

不是 Demo，不是 CRUD，而是：
- 事件驱动
- WAL
- Replay
- 内核化

你这套骨架，已经超过 90% 面试者。

