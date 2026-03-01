# 合约交易系统核心架构

> 基于 Spring Cloud + Disruptor 的高性能合约交易所核心系统

## 系统架构

```
exchage-core/
├── common-core/            # 基础模型、枚举、工具类
├── common-proto/           # DTO、请求响应模型
├── api-gateway/            # API网关（认证、限流、路由）
├── oms-core/               # 订单管理系统（订单生命周期）
├── hard-risk-core/         # 同步硬风控（下单前风控）
├── match-engine-core/      # 撮合引擎（Disruptor + 内存OrderBook）
├── ledger-core/            # 账本服务（双录分录）
├── snapshot-core/          # 快照服务（账户/持仓状态）
└── replay-core/            # 重放服务（灾备恢复）n
```

## 核心特性

### 1. 高性能撮合引擎
- **Disruptor RingBuffer**: 无锁队列，亚微秒级延迟
- **内存 OrderBook**: 价格分层 + 时间优先
- **单线程撮合**: 每个交易对独立线程，保证顺序性
- **WAL 日志**: 所有订单和成交持久化，支持灾备恢复

### 2. 双录账本系统
- **Double Entry**: 借贷平衡，每笔业务生成两条分录
- **不可变性**: 分录只能追加，不允许修改
- **幂等性**: 相同业务ID不重复记账
- **WAL 持久化**: 所有账本操作写入日志

### 3. 分层风控
- **Pre-Trade**: 下单前风控（余额、杠杆、限制检查）
- **In-Trade**: 撮合中风控（强平监控）
- **Post-Trade**: 成交后风控（风险率计算）

### 4. 灾备恢复
- **WAL 重放**: 从日志完整恢复系统状态
- **快照重建**: 从账本重建账户和持仓快照
- **对账审计**: 支持全量数据对账

## 核心调用链路

```
Client
  -> API Gateway (认证/限流)
    -> OMS (订单管理)
      -> Hard Risk (风控检查)
      -> Match Engine (撮合)
        -> Trade Event
          -> Ledger (结算)
            -> Snapshot (更新余额/持仓)
```

## 技术栈

| 组件 | 技术选型 | 说明 |
|------|---------|------|
| 微服务框架 | Spring Boot 3.2 + Spring Cloud 2023 | 主流微服务架构 |
| 服务注册 | Nacos | 服务发现、配置中心 |
| 高性能队列 | Disruptor 3.4 | LMAX 无锁队列 |
| 数据库 | MySQL 8.0 | 订单、账本持久化 |
| ORM | MyBatis-Plus | 简化数据库操作 |
| 缓存 | Redis | 快照、限流、黑名单 |
| 序列化 | FastJSON2 | 高性能JSON |

## 快速开始

### 1. 环境准备

```bash
# 安装 Java 17+
java -version

# 安装 Maven 3.8+
mvn -version

# 启动 MySQL
# 创建数据库
mysql -u root -p
CREATE DATABASE exchange_oms DEFAULT CHARSET utf8mb4;
CREATE DATABASE exchange_ledger DEFAULT CHARSET utf8mb4;

# 启动 Redis
redis-server

# 启动 Nacos
# 下载: https://github.com/alibaba/nacos/releases
cd nacos/bin
./startup.sh -m standalone
```

### 2. 初始化数据库

```bash
# 执行建表脚本
mysql -u root -p exchange_oms < sql/oms_schema.sql
mysql -u root -p exchange_ledger < sql/ledger_schema.sql
```

### 3. 编译项目

```bash
cd future-exchange
mvn clean package -DskipTests
```

### 4. 启动服务

```bash
# 启动顺序（按端口号）
# 1. API Gateway (8080)
cd api-gateway
java -jar target/api-gateway-1.0.0-SNAPSHOT.jar

# 2. OMS (8081)
cd oms-core
java -jar target/oms-core-1.0.0-SNAPSHOT.jar

# 3. Hard Risk (8082)
cd hard-risk-core
java -jar target/hard-risk-core-1.0.0-SNAPSHOT.jar

# 4. Match Engine (8083)
cd match-engine-core
java -jar target/match-engine-core-1.0.0-SNAPSHOT.jar

# 5. Ledger (8084)
cd ledger-core
java -jar target/ledger-core-1.0.0-SNAPSHOT.jar

# 6. Snapshot (8085)
cd snapshot-core
java -jar target/snapshot-core-1.0.0-SNAPSHOT.jar

# 7. Replay (8086)
cd replay-core
java -jar target/replay-core-1.0.0-SNAPSHOT.jar
```

### 4.1 统一启停（推荐）

```bash
# 一键重启交易所需服务（推荐，含 binance-data-source）
./start_required_services.sh

# 一键重启全部服务（含 binance-data-source）
./start_all_services.sh

# 查看全部状态
./scripts/servicectl.sh status all

# 重启全部服务
./scripts/servicectl.sh restart all

# 只重启改动服务
./scripts/servicectl.sh restart oms-core api-gateway market-price-core
```

说明：
- 服务端口与 `spring.application.name` 以 **Nacos 配置** 为准（脚本优先读 Nacos，失败回退本地 `nacos-configs`）。
- 详细说明见 [`docs/SERVICECTL.md`](docs/SERVICECTL.md)。

### 5. 测试下单

```bash
# 创建限价买单
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

# 创建限价卖单（会立即撮合）
curl -X POST http://localhost:8080/api/order/create \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 2,
    "symbol": "BTCUSDT",
    "side": "SELL",
    "orderType": "LIMIT",
    "price": 5000000000000,
    "quantity": 100000000,
    "leverage": 10
  }'
```

## 核心概念

### 金额精度处理

系统使用 `long` 类型存储金额，避免浮点数精度问题：

```java
// SCALE = 100_000_000 (8位小数)
long price = Money.of(50000);     // 50000.00000000
long quantity = Money.of(0.01);   // 0.01000000
```

### 订单状态机

```
NEW -> RISK_PASSED -> SENT_TO_MATCH -> PARTIAL_FILLED -> FILLED
                                    -> CANCELED
   -> RISK_REJECTED
   -> MATCH_REJECTED
```

### WAL 日志格式

```
timestamp|type|json_data

# 示例
1704067200000|COMMAND|{"orderId":123,"symbol":"BTCUSDT",...}
1704067200001|TRADE|{"tradeId":456,"price":50000,...}
```

## 验收标准

- [x] curl 下单成功
- [x] 撮合产生成交
- [x] ledger.log 有双分录
- [ ] kill 服务后重放恢复
- [ ] 账户余额对账一致

## 性能指标

| 指标 | 目标值 | 说明 |
|------|--------|------|
| 下单延迟 | < 10ms | API Gateway -> OMS |
| 风控延迟 | < 3ms | 同步风控检查 |
| 撮合延迟 | < 1ms | 内存撮合 |
| 吞吐量 | 100K TPS | 单交易对 |

## 核心设计原则

1. **Ledger 是唯一资金真相** - 所有资金变动必须通过账本
2. **Matching 内存态 + WAL** - 撮合引擎全内存，WAL保证可恢复
3. **Risk 是状态型内存引擎** - 订阅事件实时维护风险状态
4. **单 symbol 单线程撮合** - 保证撮合顺序性和确定性
5. **Event Driven** - 异步事件驱动架构
6. **幂等 + 可重放** - 所有操作支持幂等和灾备重放

## 目录结构

```
future-exchange/
├── pom.xml                          # 父POM
├── README.md                        # 项目文档
├── sql/                             # SQL脚本
│   ├── oms_schema.sql              # 订单表
│   └── ledger_schema.sql           # 账本表
├── data/                            # 数据目录
│   └── wal/                        # WAL日志
│       ├── match.log               # 撮合日志
│       └── ledger.log              # 账本日志
└── [各模块目录]
```

## 后续优化方向

### 性能优化
- [ ] OrderBook 使用数组 + PriceLevel + Intrusive List
- [ ] Off-heap 内存管理
- [ ] 零拷贝序列化
- [ ] Disruptor 多生产者模式

### 功能扩展
- [ ] WebSocket 行情推送
- [ ] K线生成服务
- [ ] 强平引擎
- [ ] ADL 自动减仓
- [ ] 资金费率结算
- [ ] 保险基金管理

### 基础设施
- [ ] Kafka 事件总线
- [ ] 分布式追踪（SkyWalking）
- [ ] 监控告警（Prometheus + Grafana）
- [ ] 压测报告

## 常见问题

### Q: 为什么不用 TreeMap 实现 OrderBook？
A: TreeMap 是红黑树结构，指针跳转导致 CPU cache miss，GC压力大。生产环境应使用数组+链表的自定义结构。

### Q: 为什么要用 WAL？
A: WAL（Write-Ahead Log）是金融系统的核心，确保：
1. 数据不丢失（先写日志再改内存）
2. 可恢复（从日志重放）
3. 可审计（完整操作记录）

### Q: 快照服务和账本有什么区别？
A: 
- **账本（Ledger）**：唯一真相源，强一致，持久化
- **快照（Snapshot）**：缓存视图，最终一致，性能优化

## 贡献指南

欢迎提交 Issue 和 Pull Request！

## 许可证

MIT License

---

**⚠️ 重要提示**

本项目仅供学习和研究使用，不建议直接用于生产环境。真实交易所系统需要考虑更多安全、合规、监管等因素。

