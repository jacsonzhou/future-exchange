# 资金费率结算系统 (Funding Rate Core)

## 📚 概述

资金费率结算系统是永续合约交易所的核心模块之一，负责定期计算资金费率并对持仓用户进行资金费用结算。

**端口**: 8088

## ✨ 核心功能

### 1. 资金费率计算
- 每8小时计算一次（00:00, 08:00, 16:00 UTC）
- 基于指数价格和标记价格计算溢价指数
- 费率范围限制：[-0.75%, +0.75%]
- 保存费率历史记录

### 2. 资金费用结算
- 获取所有持仓用户
- 逐用户计算资金费用
- 调用Ledger服务记账
- 发布结算事件到Kafka

### 3. 预估费率实时更新
- 每5秒更新一次
- 存储到数据库和Redis缓存
- 提供实时查询接口

### 4. 历史查询
- 资金费率历史记录
- 用户资金费用明细
- 批量查询接口

## 🏗️ 系统架构

### 模块划分
```
funding-rate-core/
├── client/          # 外部服务客户端（Feign）
├── controller/      # REST API控制器
├── dto/             # 数据传输对象
├── entity/          # 数据库实体
├── event/           # Kafka事件定义
├── job/             # 定时任务
├── lock/            # 分布式锁
├── mapper/          # MyBatis Mapper
├── producer/        # Kafka生产者
└── service/         # 业务逻辑层
```

### 依赖服务
```
funding-rate-core
    ↓ 调用
index-price-core (获取指数价格)
    ↓ 调用
mark-price-core (获取标记价格)
    ↓ 调用
position-snapshot-core (获取持仓数据)
    ↓ 调用
ledger-core (记账)
    ↓ 发布事件
Kafka (funding-rate-calc, funding-settlement, user-funding-fee)
```

## 💾 数据模型

### 数据库表
1. **t_funding_rate_config** - 资金费率配置表
2. **t_funding_rate_history** - 资金费率历史表
3. **t_user_funding_fee** - 用户资金费用明细表
4. **t_funding_rate_estimate** - 预估资金费率实时表

详见: `/docs/requirements/01_funding_rate_requirements.md`

## 🚀 快速开始

### 1. 环境准备
- JDK 17+
- MySQL 8.0+
- Redis 6.0+
- Kafka 3.0+

### 2. 数据库初始化
```bash
mysql -u root -p < src/main/resources/db/migration/V1__init_funding_rate_tables.sql
```

### 3. 配置文件
编辑 `application.yml`，配置数据库、Redis、Kafka连接信息：
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/exchange_funding
    username: root
    password: your_password

  redis:
    host: localhost
    port: 6379

  kafka:
    bootstrap-servers: localhost:9092
```

### 4. 启动服务
```bash
mvn spring-boot:run
```

服务启动后，访问 http://localhost:8088/actuator/health 验证服务状态。

## 📡 API 接口

### 查询资金费率历史
```bash
GET /api/v1/funding-rate/history?symbol=BTCUSDT&limit=100
```

### 查询预估资金费率
```bash
GET /api/v1/funding-rate/estimate?symbol=BTCUSDT
```

### 查询用户资金费用记录
```bash
GET /api/v1/funding-rate/user-fees?userId=10001&symbol=BTCUSDT
```

### 查询所有symbol当前资金费率
```bash
GET /api/v1/funding-rate/current
```

完整API文档见: `/docs/requirements/01_funding_rate_requirements.md`

## 🔧 核心技术栈

- **Spring Boot 3.x** - 应用框架
- **MyBatis Plus** - ORM框架
- **Spring Cloud OpenFeign** - 服务间调用
- **Spring Kafka** - 消息队列
- **Redisson** - 分布式锁
- **Resilience4j** - 熔断降级
- **Prometheus** - 监控指标

## 🛡️ 稳定性保障

### 1. 分布式锁
使用Redisson实现分布式锁，防止重复结算：
```java
funding:settlement:lock:{symbol}:{fundingTime}
```

### 2. 熔断降级
- 外部服务调用失败时，使用缓存数据降级
- 配置了重试机制和熔断策略

### 3. 幂等性保证
- 数据库唯一索引防止重复插入
- `t_funding_rate_history`: `uk_symbol_time`
- `t_user_funding_fee`: `uk_user_symbol_time`

### 4. 异常处理
- 单个用户结算失败不影响其他用户
- 完整的错误日志记录
- TODO: 重试队列机制

## 📊 监控告警

### 核心指标
- `funding_settlement_duration` - 结算耗时
- `funding_settlement_success_rate` - 结算成功率
- `funding_settlement_user_count` - 参与结算用户数
- `funding_settlement_amount` - 结算总金额

### 告警规则
- 结算延迟超过60秒: P0告警
- 结算失败率超过1%: P0告警
- 外部服务超时: P0告警

## 🔄 Kafka Topic

| Topic | 用途 | 生产者 | 消费者 |
|-------|------|--------|--------|
| funding-rate-calc | 资金费率计算完成事件 | funding-rate-core | oms-core, position-snapshot-core |
| funding-settlement | 资金费用结算事件 | funding-rate-core | ledger-core, snapshot-account-core |
| user-funding-fee | 用户资金费用事件 | funding-rate-core | notification-service |

## 📝 待办事项 (TODO)

### P0 核心功能
- [x] 资金费用结算核心逻辑
- [x] 外部服务集成
- [x] Kafka消息发布
- [x] Mapper XML实现
- [x] 配置文件完善

### P1 稳定性
- [x] 分布式锁
- [x] 异常处理基础
- [ ] 重试队列机制
- [ ] 单元测试

### P2 运维支撑
- [ ] 对账任务
- [ ] 监控指标上报
- [ ] 告警配置
- [ ] 性能压测

## 🧪 测试

### 单元测试
```bash
mvn test
```

### 集成测试
TODO: 补充集成测试用例

## 📖 相关文档

- [需求文档](../../docs/requirements/01_funding_rate_requirements.md)
- [需求补充文档](../../docs/requirements/01_funding_rate_requirements_supplement.md)
- [合约交易完整链路分析](../../CLAUDE.md)

## 👥 负责人

开发负责人: Claude AI
技术栈: Spring Boot, MyBatis Plus, Kafka, Redis

## 📄 License

Copyright © 2024 Future Exchange. All rights reserved.
