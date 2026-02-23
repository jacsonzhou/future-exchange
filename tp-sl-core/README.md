# TP/SL Core - 止盈止损订单服务

## 📋 功能概述

止盈止损订单服务是合约交易系统的核心功能模块，支持：

- ✅ **止盈订单 (Take Profit)**: 价格达到盈利目标时自动平仓
- ✅ **止损订单 (Stop Loss)**: 价格达到止损目标时自动平仓
- ✅ **移动止损 (Trailing Stop)**: 跟踪最优价格的动态止损
- ✅ **多种触发方式**: 标记价格、最新价格、指数价格
- ✅ **多种执行方式**: 市价平仓、限价平仓
- ✅ **风控保护**: 前置风控检查、滑点保护、重试机制

## 🏗️ 架构设计

```
tp-sl-core (端口: 8089)
├── Controller层      # REST API接口
├── Service层         # 业务逻辑
├── Mapper层          # 数据访问
├── Consumer层        # Kafka消费者
├── Publisher层       # Kafka生产者
├── Client层          # 外部服务调用
└── DTO层            # 数据传输对象
```

### 核心流程

```
标记价格更新 (Kafka)
    ↓
MarkPriceConsumer
    ↓
TpSlService.checkAndTrigger()
    ↓
检查触发条件
    ↓
触发订单 → 风控检查 → 创建平仓订单 (OMS)
    ↓
发布事件 (Kafka)
```

## 📦 模块结构

### 1. Controller层
- `TpSlController.java` - REST API控制器
  - POST /api/v1/tp-sl/create - 创建TP/SL订单
  - POST /api/v1/tp-sl/create-trailing - 创建移动止损
  - POST /api/v1/tp-sl/modify - 修改订单
  - POST /api/v1/tp-sl/cancel - 撤销订单
  - POST /api/v1/tp-sl/cancel-batch - 批量撤销
  - GET /api/v1/tp-sl/list - 查询订单列表
  - GET /api/v1/tp-sl/by-position - 查询持仓关联订单

### 2. Service层
- `TpSlService.java` - 服务接口
- `TpSlServiceImplNew.java` - 完整业务实现
  - 订单创建与风控检查
  - 触发条件判断
  - 平仓订单创建
  - 移动止损价格更新
- `RiskCheckService.java` - 风控检查服务
  - 创建前校验
  - 触发前校验

### 3. Entity层
- `TpSlOrder.java` - 订单实体
- `TpSlExecLog.java` - 执行日志实体

### 4. Mapper层
- `TpSlOrderMapper.java` - 订单数据访问
- `TpSlExecLogMapper.java` - 日志数据访问

### 5. Consumer层
- `MarkPriceConsumer.java` - 监听标记价格更新
- `PositionCloseConsumer.java` - 监听持仓平仓事件

### 6. Publisher层
- `TpSlEventPublisher.java` - 发布TP/SL事件
  - tp-sl-triggered - 触发事件
  - tp-sl-cancelled - 撤销事件
  - tp-sl-executed - 执行完成事件

### 7. Client层
- `OmsClient.java` - 调用订单管理服务创建平仓订单

### 8. DTO层
- `CreateTpSlRequest.java` - 创建订单请求
- `CreateWithTpSlRequest.java` - 开仓时设置TP/SL请求
- `ModifyTpSlRequest.java` - 修改订单请求
- `CancelTpSlRequest.java` - 撤销订单请求
- `BatchCancelRequest.java` - 批量撤销请求
- `TpSlOrderVO.java` - 订单视图对象
- `PositionTpSlVO.java` - 持仓关联订单视图

## 📊 数据模型

### t_tp_sl_order (订单表)
```sql
- order_id: 订单ID
- user_id: 用户ID
- symbol: 交易对
- position_id: 关联持仓
- order_type: TP/SL/TRAILING
- trigger_type: MARK/LAST/INDEX
- trigger_price: 触发价格
- trigger_side: LONG/SHORT
- exec_type: MARKET/LIMIT
- exec_price: 执行限价
- quantity: 平仓数量
- status: ACTIVE/TRIGGERED/EXECUTED/CANCELLED
```

### t_tp_sl_exec_log (执行日志表)
```sql
- tp_sl_order_id: TP/SL订单ID
- trigger_price: 触发价格
- mark_price: 标记价格
- exec_order_id: 平仓订单ID
- exec_result: SUCCESS/FAIL
- error_msg: 错误信息
```

## 🔌 Kafka Topic

### 消费的Topic
- `mark-price-update` - 标记价格更新
- `position-closed` - 持仓平仓事件

### 发布的Topic
- `tp-sl-triggered` - TP/SL触发事件
- `tp-sl-cancelled` - TP/SL撤销事件
- `tp-sl-executed` - TP/SL执行完成事件

## 🎯 核心功能实现

### 1. 止盈止损触发逻辑

#### 多单止盈 (TP)
```
触发条件: 标记价格 >= 触发价格
执行: 市价平多仓 / 限价平多仓
```

#### 多单止损 (SL)
```
触发条件: 标记价格 <= 触发价格
执行: 市价平多仓 / 限价平多仓
```

#### 空单止盈 (TP)
```
触发条件: 标记价格 <= 触发价格
执行: 市价平空仓 / 限价平空仓
```

#### 空单止损 (SL)
```
触发条件: 标记价格 >= 触发价格
执行: 市价平空仓 / 限价平空仓
```

### 2. 移动止损 (Trailing Stop)

```java
// 多单移动止损
if (markPrice > highestPrice) {
    highestPrice = markPrice;
    triggerPrice = markPrice * (1 - trailingPercent/10000);
}
if (markPrice <= triggerPrice) {
    触发平仓();
}

// 空单移动止损
if (markPrice < lowestPrice) {
    lowestPrice = markPrice;
    triggerPrice = markPrice * (1 + trailingPercent/10000);
}
if (markPrice >= triggerPrice) {
    触发平仓();
}
```

### 3. 风控检查

#### 创建前检查
- 触发价格合理性
- 数量有效性
- 执行类型校验
- 移动止损参数校验

#### 触发前检查
- 持仓是否存在
- 持仓数量是否充足
- 账户状态是否正常

### 4. 重试机制

```
第1次失败: 100ms后重试，使用原执行类型
第2次失败: 200ms后重试，降级为MARKET订单
第3次失败: 500ms后重试，MARKET + 紧急标记
全部失败: 发送告警，记录日志
```

## 🚀 启动方式

### 1. 环境准备
```bash
# MySQL
CREATE DATABASE future_exchange;

# 执行建表脚本
mysql -u root -p future_exchange < src/main/resources/schema.sql

# Kafka
docker run -d --name kafka -p 9092:9092 apache/kafka

# Redis
docker run -d --name redis -p 6379:6379 redis
```

### 2. 启动服务
```bash
mvn spring-boot:run
```

### 3. 验证服务
```bash
curl http://localhost:8089/actuator/health
```

## 🧪 测试示例

### 创建止盈订单
```bash
curl -X POST http://localhost:8089/api/v1/tp-sl/create \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 10001,
    "symbol": "BTCUSDT",
    "positionId": 50001,
    "type": "TP",
    "triggerPrice": 55000000000,
    "execType": "MARKET",
    "quantity": 100000000,
    "triggerBy": "MARK"
  }'
```

### 创建移动止损
```bash
curl -X POST http://localhost:8089/api/v1/tp-sl/create-trailing \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 10001,
    "symbol": "BTCUSDT",
    "positionId": 50001,
    "type": "TRAILING",
    "triggerPrice": 50000000000,
    "execType": "MARKET",
    "quantity": 100000000,
    "trailingPercent": 500
  }'
```

### 查询订单列表
```bash
curl "http://localhost:8089/api/v1/tp-sl/list?userId=10001&symbol=BTCUSDT&status=ACTIVE"
```

## 📝 配置说明

### application.yml
```yaml
tp-sl:
  slippage:
    max-rate: 0.005          # 最大滑点 0.5%
    limit-order-offset: 0.003 # 限价单偏离 0.3%
    limit-order-ttl: 10000    # 限价单有效期 10秒
  trigger:
    confirmation-count: 2     # 触发确认次数
    confirmation-interval: 100 # 确认间隔(ms)
  retry:
    max-attempts: 3           # 最大重试次数
    intervals: 100,200,500    # 重试间隔(ms)
```

## 🔧 优化建议

### 1. 性能优化
- 使用Redis Sorted Set存储活跃订单，加速触发检查
- 添加数据库复合索引优化查询
- 批量查询减少数据库交互

### 2. 可靠性增强
- 实现重试机制的指数退避
- 添加熔断器防止级联失败
- 完善监控告警

### 3. 功能扩展
- 支持部分平仓后自动调整订单
- 添加订单有效期功能
- 实现多级止盈止损

## 📈 监控指标

- 触发延迟: P99 < 50ms
- 执行延迟: P99 < 100ms
- 成功率: > 99.5%
- 活跃订单数量
- 执行失败率

## 🐛 常见问题

### Q1: TP/SL订单未触发
- 检查标记价格是否正常更新
- 确认触发条件是否设置正确
- 查看Consumer是否正常消费

### Q2: 平仓订单创建失败
- 检查OMS服务是否正常
- 确认持仓是否存在
- 查看风控检查是否通过

### Q3: 移动止损不生效
- 确认回调比例设置是否合理
- 检查最高/低价是否正确更新
- 查看订单状态是否为ACTIVE

## 📚 相关文档

- [需求文档](../../docs/requirements/02_tp_sl_order_requirements.md)
- [需求补充](../../docs/requirements/02_tp_sl_order_requirements_supplement.md)
- [架构设计](../../docs/architecture/tp-sl-architecture.md)
