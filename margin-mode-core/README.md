# 全仓/逐仓模式系统 (Margin Mode Core)

## 📋 系统概述

全仓/逐仓模式系统是合约交易所的核心风控模块，负责：
- 全仓/逐仓保证金模式管理
- 保证金计算与强平价格计算
- 杠杆调整与模式切换
- 风险监控与强平检测
- 全仓账户快照维护

**端口**: 8090
**技术栈**: Spring Boot, MyBatis Plus, Kafka, Redis

---

## 🎯 核心功能

### 1. 保证金模式

#### 全仓模式 (Cross Margin)
- 所有全仓仓位共享账户保证金
- 未实现盈亏实时影响可用保证金
- 强平时可能导致所有仓位被平
- 适合专业交易者和多仓位管理

#### 逐仓模式 (Isolated Margin)
- 每个仓位独立保证金
- 最大亏损限于该仓位的逐仓保证金
- 可单独调整杠杆和追加保证金
- 适合风险控制和单仓位投机

### 2. 核心计算

#### 保证金计算
```
全仓保证金 = 仓位名义价值 / 杠杆倍数
逐仓保证金 = 初始保证金 + 追加保证金
维持保证金 = 仓位价值 × 维持保证金率
```

#### 强平价格计算（逐仓）
```
多头强平价 = 开仓均价 × (1 - 逐仓保证金/仓位价值 + 维持保证金率)
空头强平价 = 开仓均价 × (1 + 逐仓保证金/仓位价值 - 维持保证金率)
```

#### 破产价格计算
```
多头破产价 = 开仓均价 × (1 - 逐仓保证金/仓位价值)
空头破产价 = 开仓均价 × (1 + 逐仓保证金/仓位价值)
```

#### 保证金率计算
```
逐仓保证金率 = (逐仓保证金 / 仓位价值) × 10000
全仓保证金率 = (钱包余额 + 全仓未实现盈亏) / 全仓仓位总价值 × 10000
```

### 3. 风险等级

| 风险等级 | 保证金率 | 状态 | 限制 |
|---------|---------|------|------|
| SAFE | > 100% | 安全 | 无限制 |
| WARNING | 50%-100% | 警告 | 提现受限 |
| DANGER | 10%-50% | 危险 | 交易受限 |
| LIQUIDATION | <= 10% | 强平 | 禁止交易 |

---

## 🏗️ 系统架构

### 模块划分
```
margin-mode-core/
├── calculator/           # 保证金计算器
│   └── MarginCalculator.java
├── client/               # 服务调用客户端
│   ├── AccountServiceClient.java
│   ├── PositionServiceClient.java
│   └── MarkPriceServiceClient.java
├── config/               # 配置类
│   └── RestTemplateConfig.java
├── controller/           # 控制器（内部接口）
│   └── MarginModeController.java
├── entity/               # 实体类
│   ├── PositionMarginDetail.java      # 仓位保证金详情
│   ├── CrossMarginSnapshot.java       # 全仓账户快照
│   ├── UserMarginConfig.java          # 用户配置
│   └── MarginChangeLog.java           # 保证金变动流水
├── enums/                # 枚举
│   ├── MarginMode.java                # 保证金模式
│   ├── PositionSide.java              # 持仓方向
│   ├── RiskLevel.java                 # 风险等级
│   └── ChangeType.java                # 变动类型
├── mapper/               # 数据访问层
│   ├── PositionMarginDetailMapper.java
│   ├── CrossMarginSnapshotMapper.java
│   ├── UserMarginConfigMapper.java
│   └── MarginChangeLogMapper.java
└── service/              # 服务层
    ├── MarginModeService.java
    └── impl/
        └── MarginModeServiceImpl.java
```

### 数据库表

#### t_position_margin_detail - 仓位保证金详情表
```sql
CREATE TABLE t_position_margin_detail (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    position_id         BIGINT NOT NULL UNIQUE,
    user_id             BIGINT NOT NULL,
    symbol              VARCHAR(32) NOT NULL,
    side                INT NOT NULL,
    margin_mode         VARCHAR(16) NOT NULL,
    leverage            INT NOT NULL,
    isolated_margin     BIGINT,
    position_margin     BIGINT NOT NULL,
    maint_margin        BIGINT NOT NULL,
    liquidation_price   BIGINT,
    bankruptcy_price    BIGINT,
    version             INT DEFAULT 0,
    ...
);
```

#### t_cross_margin_snapshot - 全仓账户快照表
```sql
CREATE TABLE t_cross_margin_snapshot (
    id                    BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id               BIGINT NOT NULL UNIQUE,
    wallet_balance        BIGINT NOT NULL,
    total_position_value  BIGINT NOT NULL,
    total_unrealized_pnl  BIGINT NOT NULL,
    margin_balance        BIGINT NOT NULL,
    margin_ratio          BIGINT NOT NULL,
    risk_level            INT NOT NULL,
    version               INT DEFAULT 0,
    ...
);
```

#### t_user_margin_config - 用户保证金配置表
```sql
CREATE TABLE t_user_margin_config (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id             BIGINT NOT NULL,
    symbol              VARCHAR(32) NOT NULL,
    default_margin_mode VARCHAR(16) DEFAULT 'CROSS',
    cross_leverage      INT DEFAULT 10,
    isolated_leverage   INT DEFAULT 10,
    ...
);
```

#### t_margin_change_log - 保证金变动流水表
```sql
CREATE TABLE t_margin_change_log (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id      BIGINT NOT NULL,
    position_id  BIGINT,
    change_type  VARCHAR(32) NOT NULL,
    amount       BIGINT NOT NULL,
    before_amount BIGINT NOT NULL,
    after_amount  BIGINT NOT NULL,
    ...
);
```

---

## 🔌 API 接口

### 仓位保证金管理

#### 创建仓位保证金详情
```http
POST /internal/margin/position/create
Content-Type: application/json

{
  "userId": 10001,
  "positionId": 50001,
  "symbol": "BTCUSDT",
  "side": 1,              // 1=多头, 2=空头
  "marginMode": "ISOLATED",
  "leverage": 20,
  "isolatedMargin": 1000
}
```

#### 查询仓位保证金详情
```http
GET /internal/margin/position/{positionId}
```

#### 查询用户所有仓位
```http
GET /internal/margin/user/{userId}/positions
```

### 保证金模式切换

#### 切换保证金模式
```http
POST /internal/margin/position/{positionId}/switch-mode
Content-Type: application/json

{
  "targetMode": "ISOLATED",
  "isolatedMargin": 1000
}
```

### 逐仓保证金调整

#### 追加逐仓保证金
```http
POST /internal/margin/position/{positionId}/add-margin
Content-Type: application/json

{
  "amount": 500
}
```

#### 减少逐仓保证金
```http
POST /internal/margin/position/{positionId}/reduce-margin
Content-Type: application/json

{
  "amount": 300
}
```

#### 调整杠杆倍数
```http
POST /internal/margin/position/{positionId}/leverage
Content-Type: application/json

{
  "newLeverage": 50
}
```

### 全仓账户快照

#### 查询全仓账户快照
```http
GET /internal/margin/user/{userId}/cross-snapshot
```

#### 计算并更新快照
```http
POST /internal/margin/user/{userId}/calculate-snapshot
```

### 保证金计算

#### 计算仓位保证金
```http
POST /internal/margin/position/{positionId}/calculate
Content-Type: application/json

{
  "currentPrice": 50000
}
```

#### 计算强平价格
```http
GET /internal/margin/position/{positionId}/liquidation-price
```

#### 计算破产价格
```http
GET /internal/margin/position/{positionId}/bankruptcy-price
```

### 风控接口

#### 检查仓位是否需要强平
```http
POST /internal/margin/position/{positionId}/check-liquidation
Content-Type: application/json

{
  "currentPrice": 48000
}
```

#### 检查账户是否需要强平
```http
GET /internal/margin/user/{userId}/check-liquidation
```

#### 查询需要强平的仓位列表
```http
GET /internal/margin/liquidation-candidates/positions
```

#### 查询需要强平的账户列表
```http
GET /internal/margin/liquidation-candidates/accounts
```

#### 获取可用保证金
```http
GET /internal/margin/user/{userId}/available-margin?marginMode=CROSS
```

#### 校验保证金是否充足
```http
POST /internal/margin/validate-margin
Content-Type: application/json

{
  "userId": 10001,
  "symbol": "BTCUSDT",
  "side": 1,
  "marginMode": "CROSS",
  "requiredMargin": 1000
}
```

---

## 🔄 业务流程

### 开仓流程
```
1. OMS收到开仓订单
2. Hard Risk Gate调用validateMarginSufficient检查保证金
3. 撮合成交
4. Clearing Service记账
5. Margin Mode Service创建PositionMarginDetail
6. 计算初始保证金、强平价、破产价
```

### 平仓流程
```
1. 撮合平仓成交
2. Clearing Service记账
3. Margin Mode Service删除PositionMarginDetail
4. 逐仓保证金归还到账户余额
```

### 模式切换流程
```
1. 用户请求切换模式
2. 检查是否有挂单（有则拒绝）
3. 检查切换后风险（接近强平则拒绝）
4. 逐仓→全仓：归还逐仓保证金
5. 全仓→逐仓：扣除可用余额作为逐仓保证金
6. 重新计算强平价格
7. Ledger记账
```

### 追加保证金流程
```
1. 检查账户可用余额
2. 扣除可用余额
3. 增加逐仓保证金
4. 重新计算强平价格（远离强平价）
5. Ledger记账
```

### 强平触发流程
```
1. Risk Monitor消费mark-price-topic
2. 调用checkLiquidationNeeded检查所有仓位
3. 逐仓：保证金率 <= 维持保证金率 → 触发强平
4. 全仓：账户保证金率 <= 维持保证金率 → 触发强平
5. 发布liquidation-trigger-topic
6. Liquidation Service执行强平
```

---

## ⚙️ 配置说明

### 保证金模式配置
```yaml
margin:
  mode:
    default: CROSS              # 默认保证金模式
    allow-switch: true          # 是否允许切换模式

  leverage:
    default: 10                 # 默认杠杆倍数
    max: 125                    # 最大杠杆倍数
    min: 1                      # 最小杠杆倍数

  risk:
    safe-threshold: 10000       # 安全阈值（100%）
    warning-threshold: 5000     # 警告阈值（50%）
    danger-threshold: 2000      # 危险阈值（20%）
    liquidation-threshold: 1000 # 强平阈值（10%）

  maintenance-margin:
    default-rate: 50            # 默认维持保证金率（0.5%）
    max-rate: 5000              # 最大维持保证金率（50%）
```

### 外部服务配置
```yaml
service:
  account:
    url: http://localhost:9090
  position:
    url: http://localhost:8084
  markprice:
    url: http://localhost:8089
  ledger:
    url: http://localhost:8085
```

---

## 🔧 使用示例

### 示例1：创建逐仓仓位
```java
// 1. 用户开仓，OMS调用Hard Risk检查保证金
boolean sufficient = marginModeService.validateMarginSufficient(
    userId, "BTCUSDT", 1, "ISOLATED", requiredMargin
);

// 2. 撮合成交后，创建仓位保证金详情
PositionMarginDetail detail = marginModeService.createPositionMargin(
    userId, positionId, "BTCUSDT", 1, "ISOLATED", 20, 1000L
);

// 3. 计算强平价格
Long liquidationPrice = marginModeService.calculateLiquidationPrice(positionId);
```

### 示例2：追加逐仓保证金
```java
// 用户追加500 USDT保证金
PositionMarginDetail updated = marginModeService.addIsolatedMargin(
    positionId, 500L
);

// 新的强平价格会更低（更安全）
System.out.println("New liquidation price: " + updated.getLiquidationPrice());
```

### 示例3：全仓账户风险监控
```java
// 计算全仓账户快照
CrossMarginSnapshot snapshot = marginModeService.calculateCrossSnapshot(userId);

// 检查风险等级
if (snapshot.getRiskLevel() == RiskLevel.DANGER.getCode()) {
    // 发送风险预警
    alertService.sendRiskWarning(userId);
}

// 检查是否需要强平
if (marginModeService.checkAccountLiquidationNeeded(userId)) {
    // 触发强平流程
    liquidationService.triggerAccountLiquidation(userId);
}
```

---

## 📊 性能指标

| 指标 | 目标值 | 实际值 |
|------|--------|--------|
| 模式切换延迟 | < 100ms | - |
| 保证金计算延迟 | < 50ms | - |
| 强平价计算延迟 | < 30ms | - |
| 查询接口响应时间 | < 30ms | - |
| 快照更新频率 | 1秒 | - |

---

## 🔒 安全性保障

### 乐观锁
所有保证金变更操作都使用乐观锁（version字段），避免并发冲突。

### 分布式锁
关键操作（模式切换、追加/减少保证金）使用分布式锁，确保并发安全。

### 保证金校验
- 开仓前必须校验保证金充足
- 追加保证金前必须校验账户余额
- 减少保证金必须满足维持保证金要求
- 模式切换必须校验不会立即触发强平

### 数据一致性
- Ledger账本是唯一真实来源
- 所有保证金变动必须记账
- 保证金变动流水完整可追溯

---

## 🚀 部署说明

### 环境要求
- JDK 17+
- MySQL 8.0+
- Kafka 2.8+
- Redis 6.0+

### 启动步骤
```bash
# 1. 创建数据库
mysql -u root -p < sql/schema.sql

# 2. 配置application.yml
vim src/main/resources/application.yml

# 3. 启动服务
mvn spring-boot:run
```

### 健康检查
```bash
# 检查服务状态
curl http://localhost:8090/actuator/health

# 检查数据库连接
curl http://localhost:8090/actuator/health/db
```

---

## 📝 待完成事项

- [ ] 与Ledger Service的集成（Ledger记账）
- [ ] 与Order Service的集成（检查挂单）
- [ ] Kafka事件消费（标记价格变动、账户变动）
- [ ] 定时任务（快照自动更新、强平检测）
- [ ] 分布式锁实现（Redis/Redisson）
- [ ] 单元测试覆盖
- [ ] 性能测试与优化

---

## 📚 参考文档

- [需求文档](/docs/requirements/03_margin_mode_requirements.md)
- [合约交易完整链路分析](/docs/CLAUDE.md)
- [Binance 保证金模式文档](https://www.binance.com/zh-CN/support/faq/360033162192)
- [OKX 保证金模式文档](https://www.okx.com/zh-hans/help-center/introduction-to-margin-modes)

---

## 👥 维护者

- Claude Sonnet 4.5 (AI Code Assistant)
- 项目团队

---

## 📄 许可证

MIT License
