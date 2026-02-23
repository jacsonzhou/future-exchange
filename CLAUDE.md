# 项目规范与架构指南

> 本文档定义合约交易系统的开发规范、架构原则和最佳实践。
> 最后更新：2026-02-19（Gateway 架构升级至 Reactive）

---

## 开发流程

1. 每个功能/修复必须创建独立分支，命名规范：`feat/xxx` 或 `fix/xxx`
2. commit message 格式：`feat: xxx` / `fix: xxx` / `docs: xxx`
3. 完成后自动创建 PR，关联对应 issue
4. PR 描述需包含：改动内容、测试方法

---

## 合约交易完整链路分析

### 📋 链路梳理概述

本文档重新梳理合约交易所的核心架构与交易链路，强调强一致账本、硬风控前置与软风控异步闭环。

### 🧭 合约交易核心架构

```
                    ┌─────────────────────────────────────────┐
                    │        API Gateway (8080)               │
                    │  ┌─────────────────────────────────────┐│
                    │  │  Spring Cloud Gateway (Reactive)    ││
                    │  │  - Netty NIO (10万+ 并发)            ││
                    │  │  - 纯路由模式 (无业务代码)            ││
                    │  └─────────────────────────────────────┘│
                    │                                         │
                    │  Filters:                               │
                    │  1. TraceGatewayFilter   (链路追踪)     │
                    │  2. AuthGatewayFilter    (JWT认证)      │
                    │  3. RequestRateLimiter   (Redis限流)    │
                    │  4. CircuitBreaker       (熔断降级)     │
                    │  5. RewritePath          (路径重写)     │
                    │                                         │
                    │  路径映射:                              │
                    │  /api/order/* → lb://oms-core           │
                    │  /api/v1/user/* → lb://user-core        │
                    │  /api/v1/market/* → lb://market-price   │
                    └─────────────┬───────────────────────────┘
                                  │
                                  │ 直接路由 (无 Feign)
                                  ▼
                    ┌─────────────────────────────────────────┐
                    │     OMS Core (8081)                     │
                    │  Order Management Service               │
                    │                                         │
                    │  - 订单生命周期管理                       │
                    │  - 幂等性检查 (Redis)                    │
                    │  - 保证金预扣计算                         │
                    │  - 发送 OrderCommand 到 Kafka            │
                    │                                         │
                    │  接口路径: /api/v1/oms/order/*            │
                    │  Headers: X-User-Id (从 Gateway 透传)    │
                    └─────────────┬───────────────────────────┘
                                  │
                        (同步 Feign 调用)
                                  │
                                  ▼
          ┌─────────────────────────────────────────────────┐
          │        Hard Risk Core (8082)                     │
          │        同步硬风控网关                             │
          │                                                 │
          │  - 可用保证金检查 (Account Snapshot)             │
          │  - 仓位存在校验 (Position Snapshot)              │
          │  - 最大仓位/杠杆限制                             │
          │  - 用户黑名单检查 (Redis)                        │
          │                                                 │
          │  原则: 同步阻断，< 3ms 延迟                      │
          └─────────────┬───────────────────────────────────┘
                                  │
                        (Kafka 异步)
                                  │
                                  ▼
┌──────────────────────────────────────────────────────────────────────┐
│                  Match Engine Core (8083)                            │
│                  撮合引擎内核                                         │
│                                                                      │
│  架构: Disruptor RingBuffer + 单线程撮合                              │
│                                                                      │
│  - OrderBook (内存价格-时间优先)                                      │
│  - WAL 日志持久化 (灾备恢复)                                          │
│  - Protobuf 序列化                                                   │
│                                                                      │
│  输出:                                                               │
│  - trade-topic (成交事件) → Ledger Core                             │
│  - order-state-topic (状态更新) → OMS                               │
│  - match-event-topic (行情) → Market Price Core                     │
└─────────────┬────────────────────────────────────────────────────────┘
              │
     (Kafka / Event Bus)
              │
              ▼
┌──────────────────────────────────────────────────────────────────────┐
│                  Ledger Core (8084)                                  │
│                  权威账本核心 (Clearing Core)                         │
│                                                                      │
│  原则: Ledger = 唯一真实来源 (Source of Truth)                       │
│                                                                      │
│  - 双录分录记账 (借贷必平衡)                                          │
│  - 资金账 Ledger (Account Balance)                                   │
│  - 持仓账 Ledger (Position)                                          │
│  - 手续费 Ledger (Fee Record)                                        │
│                                                                      │
│  输出:                                                               │
│  - trade-entry-topic → Snapshot Services                            │
└─────────────┬────────────────────────────────────────────────────────┘
              │
         (Kafka)
              │
              ▼
┌──────────────────────────────────────────────────────────────────────┐
│            Snapshot Services (派生系统 / 只读副本)                     │
│                                                                      │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐      │
│  │ Account Snapshot│  │Position Snapshot│  │  Risk Snapshot  │      │
│  │    (8085)       │  │    (8086)       │  │                 │      │
│  │  - 余额查询     │  │  - 持仓查询     │  │  - 保证金率     │      │
│  │  - 可用资金     │  │  - 开仓均价     │  │  - 强平价格     │      │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘      │
│                                                                      │
│  注意: 这些服务只消费 Ledger 事件，维护查询视图，非权威数据源          │
└─────────────┬────────────────────────────────────────────────────────┘
              │
              ▼
┌──────────────────────────────────────────────────────────────────────┐
│            Soft Risk Engine (异步软风控)                              │
│                                                                      │
│  输入事件:                                                           │
│  - PositionDelta (持仓变化)                                          │
│  - MarkPrice (标记价格) ← MarkPrice Core (8098)                     │
│  - AccountDelta (资金变化)                                           │
│  - MarketStats (市场统计) ← Market Price Core (8095)                │
│                                                                      │
│  功能:                                                               │
│  - 风险规则引擎                                                       │
│  - 强平计算 (Liquidation)                                            │
│  - ADL 计算 (Auto-Deleveraging)                                      │
│  - 熔断检测 (Circuit Breaker)                                        │
│                                                                      │
│  输出:                                                               │
│  - liquidation-command → Liquidation Core (8088)                    │
│  - adl-command → ADL Core (8090)                                    │
└─────────────┬────────────────────────────────────────────────────────┘
              │
              ▼
┌──────────────────────────────────────────────────────────────────────┐
│          Liquidation Core (8088) / ADL Core (8090)                   │
│                                                                      │
│  - 强平订单生成 (reduceOnly=true, orderSource=LIQUIDATION)          │
│  - ADL 减仓订单生成 (orderSource=ADL)                                │
│  - 调用 OMS Internal API: POST /internal/order/createLiquidation     │
│  - 回流 OMS → 进入正常撮合流程                                        │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 架构关键原则

1. **硬风控前置、同步阻断**：所有下单必须先过 Hard Risk Core，确保保证金与仓位约束可用。
2. **Gateway 纯路由模式**：API Gateway 只做认证、限流、路由，不处理业务逻辑。
3. **用户信息 Header 透传**：Gateway 认证后，通过 `X-User-Id`、`X-Account-Id` Header 透传给下游服务。
4. **撮合与账本解耦**：撮合只生成事件，权威状态统一由 Ledger Core 负责落地。
5. **账本为唯一真实来源**：资金、持仓、手续费、保险基金均以 Ledger 为准。
6. **派生系统只做快照**：Account/Position/Risk/Market 均由账本事件派生，可重建。
7. **软风控异步闭环**：基于多源事件计算强平、ADL、熔断并回流 OMS。

---

## 交易链路映射（从请求到清算）

| 步骤 | 服务 | 关键操作 | 技术细节 |
|-----|------|---------|---------|
| 1 | **API Gateway** | 鉴权、限流、路由 | Spring Cloud Gateway, Redis 限流 |
| 2 | **OMS Core** | 订单生命周期、幂等检查 | MySQL + Redis 幂等键 |
| 3 | **Hard Risk Core** | 同步风控检查 | Feign 同步调用, < 3ms |
| 4 | **Match Engine** | 内存撮合生成成交 | Disruptor, 单线程 |
| 5 | **Ledger Core** | 双录分录记账 | MySQL 事务, 借贷平衡 |
| 6 | **Snapshot Services** | 消费事件维护快照 | Kafka Consumer |
| 7 | **Soft Risk Engine** | 异步风险计算 | 规则引擎, 流计算 |
| 8 | **Liquidation/ADL** | 生成系统订单回流 | OMS Internal API |

---

## ✅ 服务实现状态

### 核心交易链路服务

| 序号 | 服务名称 | 端口 | 实现状态 | 关键功能 |
|-----|---------|------|---------|---------|
| 1 | **API Gateway** | 8080 | ✅ 已实现 | Spring Cloud Gateway (Reactive), 纯路由 |
| 2 | **OMS Core** | 8081 | ✅ 已实现 | 订单生命周期、幂等性、前置风控 |
| 3 | **Hard Risk Core** | 8082 | ✅ 已实现 | 同步硬风控、余额检查、黑名单 |
| 4 | **Match Engine Core** | 8083 | ✅ 已实现 | Disruptor 撮合、内存 OrderBook |
| 5 | **Ledger Core** | 8084 | ✅ 已实现 | 双录分录、权威账本 |
| 6 | **Account Snapshot** | 8085 | ✅ 已实现 | 资金快照服务 (非权威) |
| 7 | **Position Snapshot** | 8086 | ✅ 已实现 | 持仓快照服务 (非权威) |
| 8 | **Replay Core** | 8087 | ✅ 已实现 | WAL 日志重放、灾备恢复 |
| 9 | **Liquidation Core** | 8088 | ✅ 已实现 | 强平执行器 |
| 10 | **TP-SL Core** | - | ✅ 已实现 | 止盈止损服务 |

### 行情与数据服务

| 序号 | 服务名称 | 端口 | 实现状态 | 关键功能 |
|-----|---------|------|---------|---------|
| 11 | **Market Price Core** | 8095 | ✅ 已实现 | Market Data Engine: K线、Ticker、OrderBook |
| 12 | **Public Push Core** | 8096 | ✅ 已实现 | WebSocket 推送、订阅管理 |
| 13 | **Index Price Core** | 8097 | ✅ 已实现 | 指数价格计算 |
| 14 | **Mark Price Core** | 8098 | ✅ 已实现 | 标记价格、资金费率 |

### 用户与账户服务

| 序号 | 服务名称 | 端口 | 实现状态 | 关键功能 |
|-----|---------|------|---------|---------|
| 15 | **User Core** | 8099 | ✅ 已实现 | 用户管理、JWT 签发 |
| 16 | **Margin Mode Core** | 8100 | ✅ 已实现 | 保证金模式管理 |

### 其他服务

| 序号 | 服务名称 | 端口 | 实现状态 | 关键功能 |
|-----|---------|------|---------|---------|
| 17 | **ADL Core** | - | ⚠️ 部分实现 | 自动减仓 |
| 18 | **Funding Rate Core** | - | ✅ 已实现 | 资金费率结算 |

---

## 🔍 关键 Kafka Topic

### 撮合输入 (OMS → Match Engine)

| Topic名称 | 生产者 | 消费者 | 消息内容 | 分区策略 |
|----------|-------|-------|---------|---------|
| `order-event-{symbol}` | OMS Core | Match Engine | 订单命令 (OrderCommand) | 单分区，按 symbol |

### 撮合输出 (Match Engine → 全系统)

| Topic名称 | 生产者 | 消费者 | 消息内容 |
|----------|-------|-------|---------|
| `trade-event` | Match Engine | Ledger Core | 成交事件 (TradeEvent) |
| `order-state-{symbol}` | Match Engine | OMS Core | 订单状态更新 |
| `match-event` | Match Engine | Market Price Core | 撮合事件 (行情生成) |

### 账本输出 (Ledger → Snapshots)

| Topic名称 | 生产者 | 消费者 | 消息内容 |
|----------|-------|-------|---------|
| `trade-entry-{symbol}` | Ledger Core | Account/Position Snapshot | 账本分录事件 |
| `account-change-topic` | Ledger Core | Risk Engine | 资金变化 |

### 风控输出 (Soft Risk → Executors)

| Topic名称 | 生产者 | 消费者 | 消息内容 |
|----------|-------|-------|---------|
| `liquidation-command` | Risk Engine | Liquidation Core | 强平指令 |
| `adl-command` | Risk Engine | ADL Core | ADL 指令 |

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

### 场景2: 强平触发流程

```
用户A持有多仓，价格下跌，保证金率 < 维持保证金率

步骤:
1. Mark Price Core (8098) 推送标记价格
2. Risk Monitor 计算: 保证金率 3% < 维持保证金率 5%
3. 触发强平，发送 liquidation-command
4. Liquidation Core (8088) 接收指令
5. 调用 OMS Internal API: POST /internal/order/createLiquidation
   - reduceOnly=true
   - orderSource=LIQUIDATION
   - 跳过风控检查
6. 进入 Match Engine 撮合
7. Ledger Core 记账 (强平盈亏)
8. Position Snapshot 更新

结果: ✅ 自动风险处置，保险基金或 ADL 兜底
```

---

## 🛠️ 开发约束与最佳实践

### API Gateway 约束

```yaml
# ✅ 正确: Gateway 只做路由和横切面功能
spring:
  cloud:
    gateway:
      routes:
        - id: order-service
          uri: lb://oms-core
          predicates:
            - Path=/api/order/**
          filters:
            - RewritePath=/api/order/(?<segment>.*), /api/v1/oms/order/\${segment}

# ❌ 错误: Gateway 不应包含业务 Controller
@RestController
@RequestMapping("/api/order")
public class OrderController {  // 不要这样做！
    @PostMapping("/create")
    public OrderResponse createOrder(...) {  // 业务逻辑应在 OMS
        // ...
    }
}
```

### 用户信息透传规范

```java
// Gateway: AuthGatewayFilter
ServerHttpRequest mutatedRequest = request.mutate()
    .header("X-User-Id", String.valueOf(userId))
    .header("X-Account-Id", accountId != null ? String.valueOf(accountId) : "")
    .header("X-Username", username)
    .build();

// OMS: Controller 接收
@PostMapping("/submit")
public SubmitOrderResponse submitOrder(
    @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
    @RequestHeader(value = "X-Request-Id", required = false) String requestId,
    @RequestHeader(value = "X-User-Id") Long userId,  // Gateway 透传
    @RequestHeader(value = "X-Account-Id", required = false) Long accountId,
    @RequestBody SubmitOrderRequest request) {
    // ...
}
```

### 服务间调用规范

| 调用方向 | 推荐方式 | 说明 |
|---------|---------|------|
| Gateway → OMS | 直接路由 (Reactive) | Gateway 不通过 Feign |
| OMS → Hard Risk | Feign (同步) | 需要同步等待结果 |
| OMS → Match Engine | Kafka (异步) | 高吞吐，削峰填谷 |
| Match Engine → Ledger | Kafka (异步) | 事件驱动 |
| Ledger → Snapshots | Kafka (异步) | 最终一致 |

### 金额处理规范

```java
// ✅ 正确: 使用 long 存储金额 (8位小数)
public class Money {
    public static final long SCALE = 100_000_000L;
    
    public static long of(double value) {
        return (long) (value * SCALE);
    }
    
    public static long multiply(long a, long b) {
        return (a * b) / SCALE;
    }
}

// 使用示例
long price = Money.of(50000.50);      // 50000.50000000
long quantity = Money.of(0.01);       // 0.01000000
long amount = Money.multiply(price, quantity);

// ❌ 错误: 使用 double 或 BigDecimal 存储
private double price;  // 精度丢失风险
```

### 幂等性实现

```java
@Service
public class OrderService {
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    public SubmitOrderResponse submitOrder(SubmitOrderRequest request) {
        String idempotentKey = "idempotent:" + request.getClientOrderId();
        
        // 1. 检查幂等键
        Boolean success = redisTemplate.opsForValue()
            .setIfAbsent(idempotentKey, "PROCESSING", Duration.ofMinutes(5));
        
        if (!Boolean.TRUE.equals(success)) {
            // 已存在，返回缓存结果或等待
            return getCachedResult(idempotentKey);
        }
        
        try {
            // 2. 执行业务逻辑
            Order order = createOrder(request);
            
            // 3. 缓存结果
            redisTemplate.opsForValue().set(idempotentKey, serializeResult(order), 
                Duration.ofMinutes(10));
            
            return SubmitOrderResponse.success(order);
            
        } catch (Exception e) {
            // 失败时删除幂等键，允许重试
            redisTemplate.delete(idempotentKey);
            throw e;
        }
    }
}
```

### 数据库设计规范

```sql
-- 表名规范: t_ 前缀
CREATE TABLE t_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id VARCHAR(64) NOT NULL COMMENT '业务订单ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    symbol VARCHAR(20) NOT NULL COMMENT '交易对',
    
    -- 金额字段使用 BIGINT (8位小数)
    price BIGINT NOT NULL COMMENT '价格 (精度8位)',
    quantity BIGINT NOT NULL COMMENT '数量 (精度8位)',
    filled_quantity BIGINT NOT NULL DEFAULT 0 COMMENT '已成交数量',
    
    -- 状态使用 ENUM
    status TINYINT NOT NULL COMMENT '0:NEW,1:PARTIAL,2:FILLED,3:CANCELLED',
    
    -- 时间戳
    created_at BIGINT NOT NULL COMMENT '创建时间 (毫秒时间戳)',
    updated_at BIGINT NOT NULL COMMENT '更新时间 (毫秒时间戳)',
    
    -- 索引
    UNIQUE KEY uk_order_id (order_id),
    KEY idx_user_symbol (user_id, symbol),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### Kafka 消费规范

```java
@Component
@Slf4j
public class TradeEventConsumer {
    
    @KafkaListener(topics = "trade-event", groupId = "ledger-service")
    public void onTradeEvent(TradeEvent event) {
        log.info("[TradeConsumer] Received: tradeId={}, symbol={}", 
            event.getTradeId(), event.getSymbol());
        
        try {
            // 1. 幂等性检查 (tradeId 唯一)
            if (ledgerEntryMapper.existsByTradeId(event.getTradeId())) {
                log.warn("[TradeConsumer] Duplicate trade: {}", event.getTradeId());
                return;
            }
            
            // 2. 处理业务
            processTrade(event);
            
        } catch (Exception e) {
            log.error("[TradeConsumer] Failed to process: {}", event.getTradeId(), e);
            // 根据业务选择: 重试 / 死信队列 / 告警
            throw e;  // 抛出异常触发重试
        }
    }
}
```

### 异常处理规范

```java
// 自定义异常
public class OmsException extends RuntimeException {
    private final OmsErrorCode errorCode;
    
    public OmsException(OmsErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}

// 全局异常处理器
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    @ExceptionHandler(OmsException.class)
    public ResponseEntity<ErrorResponse> handleOmsException(OmsException e) {
        log.warn("[OmsException] code={}, message={}", e.getErrorCode(), e.getMessage());
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse(e.getErrorCode().getCode(), e.getMessage()));
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("[SystemException] ", e);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse(500, "Internal server error"));
    }
}
```

---

## 🔧 环境配置

### 端口分配

| 服务 | 端口 | 说明 |
|------|------|------|
| API Gateway | 8080 | 统一入口 (Reactive) |
| OMS Core | 8081 | 订单管理 |
| Hard Risk Core | 8082 | 硬风控 |
| Match Engine Core | 8083 | 撮合引擎 |
| Ledger Core | 8084 | 权威账本 |
| Account Snapshot | 8085 | 资金快照 |
| Position Snapshot | 8086 | 持仓快照 |
| Replay Core | 8087 | 重放服务 |
| Liquidation Core | 8088 | 强平服务 |
| Market Price Core | 8095 | 行情生成 |
| Public Push Core | 8096 | 公有推送 |
| Index Price Core | 8097 | 指数价格 |
| Mark Price Core | 8098 | 标记价格 |
| User Core | 8099 | 用户服务 |

### 启动顺序

```bash
# 1. 基础设施
mysql -u root -p
redis-server
nacos-start.sh
kafka-server-start.sh

# 2. 核心服务 (必须按序)
java -jar match-engine-core.jar      # 撮合引擎先启动
java -jar ledger-core.jar            # 账本服务
java -jar snapshot-account-core.jar  # 快照服务
java -jar snapshot-position-core.jar # 快照服务

# 3. 风控与订单
java -jar hard-risk-core.jar         # 硬风控
java -jar oms-core.jar               # OMS

# 4. 行情与推送
java -jar market-price-core.jar      # 行情生成
java -jar public-push-core.jar       # 公有推送

# 5. 入口
java -jar api-gateway.jar            # API Gateway (最后)
```

---

## 📚 相关文档

| 文档 | 说明 |
|------|------|
| `AGENTS.md` | AI Agent 项目指南 |
| `PROJECT_STRUCTURE.md` | 项目结构说明 |
| `KAFKA_DUAL_CHANNEL_ARCHITECTURE.md` | Kafka 双通道架构 |
| `LEDGER_SNAPSHOT_DECOUPLED_ARCHITECTURE.md` | 账本与快照解耦 |
| `OMS_MATCH_ENGINE_INTEGRATION.md` | OMS 与撮合集成 |
| `TRADE-FLOW-ANALYSIS.md` | 交易链路分析 |
| `PUBLIC_PUSH_ARCHITECTURE.md` | 公有推送架构 |

---

*最后更新：2026-02-19*
