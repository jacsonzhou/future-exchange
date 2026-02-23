# AGENTS.md - 合约交易系统开发规范

> 本文档面向 AI Coding Agent，提供开发禁令、服务清单、调用规范等核心信息。

---

## 1. 开发禁令清单 ⚠️

**以下行为严格禁止，违反会导致系统故障：**

| 禁止项 | 错误示例 | 正确做法 |
|--------|---------|---------|
| ❌ 硬编码服务地址 | `http://localhost:8081` | 使用 `lb://oms-core` |
| ❌ 硬编码数据库密码 | `password: 123456` | 使用 `${DB_PASSWORD}` 环境变量 |
| ❌ 在代码中配置路由 | 在 Gateway 代码里写路由规则 | 配置在 **Nacos** |
| ❌ 直连其他服务 | 直接 new Service() 或注入其他服务 | 通过 **Feign** 或 **Kafka** |
| ❌ 绕过 Gateway 暴露服务 | 服务监听 `0.0.0.0` | 仅监听 `localhost` + Gateway 转发 |
| ❌ 使用 root 账号连数据库 | `username: root` | 创建独立业务账号 |
| ❌ 提交本地配置到代码库 | `application-local.yaml` | 添加到 `.gitignore` |

---

## 2. 服务端口清单

| 服务 | 端口   | 服务名 (spring.application.name) | 说明 |
|------|------|--------------------------------|------|
| api-gateway | 8082 | `api-gateway` | 对外接口入口 |
| oms-core | 8081 | `oms-core` | 订单管理 |
| hard-risk-core | 8082 | `hard-risk-core` | 硬风控 |
| match-engine-core | 8083 | `match-engine-core` | 撮合引擎 |
| ledger-core | 8084 | `ledger-core` | 账本服务 |
| snapshot-account-core | 8085 | `snapshot-account-core` | 账户快照 |
| position-snapshot-core | 8086 | `position-snapshot-core` | 持仓快照 |
| replay-core | 8087 | `replay-core` | 重放服务 |
| market-price-core | 8095 | `market-price-core` | 行情计算 |
| public-push-core | 8096 | `public-push-core` | 公有推送 |
| private-push-core | 8097 | `private-push-core` | 私有推送 |
| index-price-core | 8098 | `index-price-core` | 指数价格 |
| mark-price-core | 8099 | `mark-price-core` | 标记价格 |
| user-core | 8100 | `user-core` | 用户服务 |
| funding-rate-core | 8101 | `funding-rate-core` | 资金费率 |
| liquidation-core | 8102 | `liquidation-core` | 强平服务 |
| adl-core | 8103 | `adl-core` | ADL服务 |
| tp-sl-core | 8104 | `tp-sl-core` | 止盈止损 |

---

## 3. 基础设施配置

### Nacos（服务注册/配置中心）

| 配置项 | 值 |
|--------|-----|
| 地址 | http://localhost:8848/nacos |
| 账号 | nacos |
| 密码 | nacos |
| 命名空间 | 默认 public |
| 配置分组 | DEFAULT_GROUP |

### Docker 服务

| 组件 | 地址 | 账号 | 密码               |
|------|------|------|------------------|
| MySQL | localhost:3306 | root | root123456       |
| Redis | localhost:6379 | - | redis            |
| ClickHouse | localhost:8123 | default | ClickHouse123456 |
| Kafka | localhost:9092 | - | -                |

---

## 4. 服务调用规范

### 核心原则

- **同步调用** → 使用 **Feign** + `lb://服务名`
- **异步调用** → 使用 **Kafka** Topic
- **严禁** 直接 new 或注入其他服务的类

### 同步调用（Feign）

```java
// ✅ 正确：使用服务名，通过 Nacos 服务发现
@FeignClient(name = "ledger-core", path = "/api/ledger")
public interface LedgerClient {
    @PostMapping("/freeze")
    Result<Void> freeze(FreezeRequest request);
}

// ❌ 错误：硬编码 URL
@FeignClient(name = "ledger", url = "http://localhost:8084")
```

### 异步调用（Kafka）

```java
// ✅ 正确：使用 Kafka Topic，解耦服务
kafkaTemplate.send("order-event-" + symbol, orderCommand);

// ❌ 错误：直接调用其他服务
matchEngineService.submitOrder(order);  // 禁止！
```

### 核心调用链路

```
Client → API Gateway → OMS Core → Kafka:order-event → Match Engine
                                              ↓
                                        Kafka:trade-event
                                              ↓
                            Ledger Core → Kafka:trade-entry → Snapshot Services
```

### 服务发现配置

```yaml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_HOST:localhost}:${NACOS_PORT:8848}
        namespace: ${NACOS_NAMESPACE:}
        group: DEFAULT_GROUP
      config:
        server-addr: ${NACOS_HOST:localhost}:${NACOS_PORT:8848}
        namespace: ${NACOS_NAMESPACE:}
        group: DEFAULT_GROUP
        file-extension: yaml
```

---

## 5. API Gateway 配置规范

### 配置位置

**所有 Gateway 配置必须存储在 Nacos，禁止硬编码在代码中。**

### 路由配置（Nacos: `api-gateway.yaml`）

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: oms-core
          uri: lb://oms-core           # lb://服务名，负载均衡
          predicates:
            - Path=/api/order/**
          filters:
            - name: RequestRateLimiter  # 限流
              args:
                redis-rate-limiter.replenishRate: 1000
                redis-rate-limiter.burstCapacity: 2000

        # WebSocket 行情推送
        - id: public-push-core
          uri: lb:ws://public-push-core
          predicates:
            - Path=/ws/market/**
```

### 认证配置（Nacos）

```yaml
gateway:
  auth:
    enabled: true
    white-list:
      - /api/auth/login
      - /api/auth/register
      - /ws/market/**
    jwt:
      secret: ${JWT_SECRET}
      expiration: 86400
```

### 限流配置（Nacos）

```yaml
gateway:
  rate-limiter:
    enabled: true
    default-replenish-rate: 100
    default-burst-capacity: 200
    path-limits:
      /api/order/create: 50
      /api/order/cancel: 100
```

---

## 6. Git 工作流规范

### 分支命名

| 分支类型 | 命名规范 | 示例 |
|---------|---------|------|
| 功能分支 | `feat/{功能描述}` | `feat/order-cancel` |
| 修复分支 | `fix/{bug描述}` | `fix/ledger-balance-calc` |
| 热修复分支 | `hotfix/{问题描述}` | `hotfix/security-patch` |

### Commit Message 格式

`<type>: <description>`

| Type | 用途 | 示例 |
|------|------|------|
| `feat` | 新功能 | `feat: 添加订单撤销功能` |
| `fix` | Bug 修复 | `fix: 修复撮合引擎内存泄漏` |
| `docs` | 文档更新 | `docs: 更新 API 文档` |
| `refactor` | 重构 | `refactor: 优化 OrderBook 结构` |
| `test` | 测试相关 | `test: 添加撮合引擎单元测试` |
| `chore` | 构建/工具 | `chore: 升级 Spring Boot 版本` |

### PR 描述模板

```markdown
改动内容:
- 添加了 XXX 功能
- 修复了 YYY 问题

测试方法:
1. 启动所有服务
2. 执行测试脚本
3. 验证结果

关联 Issue: Closes #123
```

---

## 7. 核心设计原则

### 双录账本系统

Ledger 是唯一资金真相（Source of Truth），每笔业务生成两条分录，借贷必平衡。

```
买入开仓:
  借：持仓资产  1.0 BTC
  贷：保证金    50,000 USDT
```

### Kafka 双通道架构

- **输入通道**: `order-event-{symbol}` (OMS → Match)
- **输出通道**: `trade-event` (Match → 全系统)
- **账本通道**: `trade-entry-{symbol}` (Ledger → Snapshot)

**原则**：撮合输入与输出是独立通道，每个 symbol 独立 Topic，单分区保证顺序。

### 解耦架构

- **Ledger-Core** 只记账 + 发布 Event
- **Snapshot** 独立消费更新
- 两者完全解耦，Snapshot 异常不影响 Ledger

---

## 8. 代码规范

### 项目结构

```
com.exchange.{module}/
├── {Module}Application.java    # 启动类
├── config/                     # 配置类
├── controller/                 # HTTP 接口
├── service/                    # 业务逻辑
│   └── impl/                   # 实现类
├── entity/                     # 实体类（MyBatis）
├── mapper/                     # MyBatis Mapper
├── dto/                        # 数据传输对象
├── event/                      # 事件对象
├── client/                     # Feign 客户端
├── enums/                      # 枚举类
└── util/                       # 工具类
```

### 命名规范

| 类型 | 规范 | 示例 |
|------|------|------|
| 类名 | 大驼峰 | `OrderService`, `MatchEngine` |
| 方法/变量 | 小驼峰 | `submitOrder`, `orderId` |
| 常量 | 全大写下划线 | `MAX_ORDER_SIZE` |
| 数据库表 | t_ 前缀 | `t_order`, `t_ledger_entry` |
| 数据库字段 | 下划线分隔 | `order_id`, `create_time` |

### 金额处理规范

```java
// 使用 Money 工具类（8位小数）
long price = Money.of(50000.50);
long amount = Money.multiply(price, quantity);

// 精度系数
public static final long SCALE = 100_000_000L;
```

---

## 9. 快速参考

### 技术栈

| 组件 | 技术选型 | 版本 |
|------|---------|------|
| 语言 | Java | 17 |
| 微服务框架 | Spring Boot + Spring Cloud | 3.2.0 + 2023.0.0 |
| 服务注册/配置 | Nacos | 2.0+ |
| 高性能队列 | Disruptor | 3.4.4 |
| 消息队列 | Kafka | 3.x |
| 数据库 | MySQL | 8.0+ |
| 缓存 | Redis | 6.0+ |

### 构建命令

```bash
./build.sh                    # 一键编译
mvn clean package -DskipTests # 手动编译
```

### 初始化命令

```bash
./init_db.sh      # 初始化数据库
./init_kafka.sh   # 初始化 Kafka Topic
```

### 服务启动顺序

1. 基础设施：MySQL (3306) → Redis (6379) → Nacos (8848) → Kafka (9092)
2. 核心服务：Match Engine (8083) → Ledger (8084) → OMS (8081)
3. 外围服务：Snapshot → Risk → Price → Gateway → Push

### 测试下单

```bash
curl -X POST http://localhost:8080/api/order/create \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": 5000000000000,
    "quantity": 100000000,
    "leverage": 10
  }'
```

---

*文档版本: 精简版 | 专注开发规范*
