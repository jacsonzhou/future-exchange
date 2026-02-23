# OMS Core - 项目文件清单

## 📦 项目结构

```
oms-core/
├── src/main/java/com/exchange/oms/
│   ├── OmsApplication.java                      # 启动类
│   │
│   ├── entity/                                  # 实体层 (5个)
│   │   ├── OmsOrder.java                        # 订单实体（增强版）★
│   │   ├── OmsOrderEvent.java                   # 订单事件实体 ★
│   │   ├── OmsOrderStateLog.java                # 订单状态日志实体 ★
│   │   ├── OmsIdempotentKey.java                # 幂等key实体 ★
│   │   └── Order.java                           # [旧版保留]
│   │
│   ├── mapper/                                  # Mapper层 (5个)
│   │   ├── OmsOrderMapper.java                  # 订单Mapper ★
│   │   ├── OmsOrderEventMapper.java             # 事件Mapper ★
│   │   ├── OmsOrderStateLogMapper.java          # 状态日志Mapper ★
│   │   ├── OmsIdempotentKeyMapper.java          # 幂等keyMapper ★
│   │   └── OrderMapper.java                     # [旧版保留]
│   │
│   ├── dto/                                     # DTO层 (6个)
│   │   ├── SubmitOrderRequest.java              # 提交订单请求 ★
│   │   ├── SubmitOrderResponse.java             # 提交订单响应 ★
│   │   ├── CancelOrderRequest.java              # 撤单请求 ★
│   │   ├── CancelOrderResponse.java             # 撤单响应 ★
│   │   ├── QueryOrderRequest.java               # 查询订单请求 ★
│   │   └── QueryOrderResponse.java              # 查询订单响应 ★
│   │
│   ├── service/                                 # 服务层 (4个)
│   │   ├── OmsService.java                      # OMS服务接口 ★
│   │   ├── impl/
│   │   │   ├── OmsServiceImpl.java              # OMS服务实现（400+行）★★★
│   │   │   └── OrderServiceImpl.java            # [旧版保留]
│   │   └── OrderService.java                    # [旧版保留]
│   │
│   ├── controller/                              # 控制器层 (3个)
│   │   ├── OmsController.java                   # 对外API接口 ★
│   │   ├── OmsInternalController.java           # 内部接口 ★
│   │   └── OrderInternalController.java         # [旧版保留]
│   │
│   ├── enums/                                   # 枚举 (1个)
│   │   └── OmsErrorCode.java                    # 错误码枚举 ★
│   │
│   ├── exception/                               # 异常 (1个)
│   │   └── OmsException.java                    # OMS业务异常 ★
│   │
│   ├── config/                                  # 配置 (2个)
│   │   ├── MybatisPlusConfig.java               # MyBatis Plus配置 ★
│   │   └── JacksonConfig.java                   # Jackson配置 ★
│   │
│   ├── advice/                                  # 全局处理 (1个)
│   │   └── GlobalExceptionHandler.java          # 全局异常处理 ★
│   │
│   └── client/                                  # Feign客户端 (2个)
│       ├── HardRiskClient.java                  # [旧版保留]
│       └── MatchEngineClient.java               # [旧版保留]
│
├── src/main/resources/
│   └── application.yml                          # 应用配置 ★
│
├── pom.xml                                      # Maven配置 ★
├── test.sh                                      # 快速测试脚本 ★
├── README.md                                    # 项目说明
├── IMPLEMENTATION.md                            # 实现文档 ★
└── DELIVERY.md                                  # 交付总结 ★
```

**★ = 本次新生成的文件**  
**[旧版保留] = 之前生成的文件，保留用于兼容**

---

## 📊 文件统计

### Java源文件
- **新生成**：23个
- **旧版保留**：8个
- **总计**：31个

### 代码量（新生成部分）
- **Entity**：约300行
- **Mapper**：约150行
- **DTO**：约200行
- **Service**：约500行（核心ServiceImpl约400行）
- **Controller**：约150行
- **Config/Exception/Enum**：约150行
- **总计**：约1450+行

### 配置文件
- **SQL Schema**：1个（100+行）
- **application.yml**：1个（更新）
- **pom.xml**：1个（更新）

### 文档
- **README.md**：1个（原有）
- **IMPLEMENTATION.md**：1个（新增，300+行）
- **DELIVERY.md**：1个（新增，200+行）

### 脚本
- **test.sh**：1个（新增，70+行）

---

## 🎯 核心文件说明

### 1. OmsServiceImpl.java（核心中的核心）

**代码行数**：400+行  
**核心功能**：
- ✅ 完整的订单状态机
- ✅ 幂等检查和记录
- ✅ 订单创建和状态流转
- ✅ 风控检查集成点
- ✅ 账户冻结集成点
- ✅ 事件记录（事件溯源）
- ✅ 状态日志（审计链）
- ✅ 乐观锁更新
- ✅ 成交回报处理
- ✅ 完整的异常处理

**核心方法**：
```java
submitOrder()        // 提交订单（核心编排流程）
cancelOrder()        // 撤单
queryOrder()         // 查询订单
handleTradeReport()  // 处理成交回报
```

### 2. OmsOrder.java（增强实体）

**代码行数**：100+行  
**核心特性**：
- ✅ 完整字段映射
- ✅ 乐观锁支持（@Version）
- ✅ 业务方法
  - getRemainingQuantity()：剩余数量
  - isFullyFilled()：是否完全成交
  - isPartiallyFilled()：是否部分成交
  - isCancelable()：是否可撤销
  - isFinalStatus()：是否终态

### 3. Mapper接口

**OmsOrderMapper.java**：
```java
// 自定义SQL方法
selectByUserIdAndClientOrderId()  // 幂等查询
updateStatus()                    // 状态更新（乐观锁）
updateFilledQuantity()            // 成交数量更新（乐观锁）
selectActiveOrders()              // 活跃订单查询
selectUserOrders()                // 用户订单查询
countUserOrders()                 // 订单数统计
```

### 4. SQL Schema

**oms_schema_enhanced.sql**：
```sql
-- 4张核心表
oms_order              -- 订单表（带乐观锁）
oms_order_event        -- 事件流（事件溯源）
oms_order_state_log    -- 状态日志（审计链）
oms_idempotent_key     -- 幂等key（唯一约束）

-- 关键索引
uk_user_client         -- 幂等唯一索引
idx_user_symbol        -- 用户交易对索引
idx_status             -- 状态索引
idx_created_at         -- 时间索引
```

---

## 🔑 核心设计亮点

### 1. 订单状态机

```
NEW (0)
  ↓ [风控检查]
PENDING_RISK (1)
  ↓ [资金冻结]
FROZEN (2)
  ↓ [撮合成交]
PARTIALLY_FILLED (3)
  ↓
FILLED (4)

取消路径：
NEW/FROZEN → CANCELED (5)

拒绝路径：
ANY → REJECTED (6)
```

### 2. 幂等实现

**Submit幂等**：
```java
// 幂等Key
userId + clientOrderId

// 唯一约束
UNIQUE KEY uk_user_key (user_id, idem_key)

// 流程
1. 查询幂等表
2. 如果存在：
   - 校验请求hash
   - 返回原订单ID
3. 如果不存在：
   - 创建订单
   - 记录幂等key
```

**Cancel幂等**：
```java
// 基于订单状态判断
if (status == FILLED) return "已成交"
if (status == CANCELED) return "已撤销"
if (isCancelable()) executeCancel()
```

### 3. 乐观锁

```sql
UPDATE oms_order 
SET status = #{newStatus}, 
    version = version + 1,
    updated_at = #{updatedAt}
WHERE id = #{orderId} 
  AND status = #{oldStatus}   -- 状态校验
  AND version = #{version}     -- 版本校验
```

### 4. 事件溯源

```java
// 所有订单操作记录事件
recordEvent(orderId, userId, symbol, eventType, eventSource, payload)

// 用途
- 重建订单状态
- 重放给撮合引擎
- 对账恢复
- 跨Region灾备
```

### 5. 审计链

```java
// 所有状态变更记录日志
recordStateLog(orderId, userId, fromStatus, toStatus, 
               reasonCode, reasonMsg, traceId)

// 完整追踪
- 状态变更历史
- 变更原因
- TraceId关联
```

---

## 🚀 API接口

### 1. 提交订单
```
POST /api/v1/oms/order/submit
Headers: X-User-Id, X-Trace-Id, X-Request-Id
Body: { clientOrderId, symbol, side, type, price, quantity, timeInForce }
```

### 2. 撤单
```
POST /api/v1/oms/order/cancel
Headers: X-User-Id, X-Trace-Id, X-Request-Id
Body: { orderId, clientOrderId }
```

### 3. 查询订单
```
GET /api/v1/oms/order/query?orderId=xxx
Headers: X-User-Id
```

### 4. 成交回报（内部接口）
```
POST /internal/oms/trade-report
Params: orderId, filledQuantity
```

---

## 📋 错误码规范

| Code | 含义 |
|------|------|
| OMS_1001 | 重复的clientOrderId |
| OMS_1002 | 幂等key冲突，参数不一致 |
| OMS_2001 | 订单不存在 |
| OMS_2002 | 订单状态不允许撤单 |
| OMS_3001 | 风控拒绝 |
| OMS_3002 | 资金冻结失败 |
| OMS_4001-4003 | 参数校验失败 |
| OMS_9001-9003 | 系统异常 |

---

## ✅ 生产级特性清单

- ✅ 完整的状态机
- ✅ 幂等保证
- ✅ 乐观锁
- ✅ 事件溯源
- ✅ 审计链
- ✅ 分布式追踪
- ✅ 全局异常处理
- ✅ 参数校验
- ✅ 错误码规范
- ✅ 日志规范
- ✅ Nacos服务发现
- ✅ MyBatis Plus集成
- ✅ 灾备Replay机制
- ✅ 完整文档
- ✅ 测试脚本

---

## 🎯 交付成果

### 代码层面
✅ 23个新Java文件（1450+行）  
✅ 1个SQL Schema（100+行）  
✅ 完整的分层架构（Entity/Mapper/DTO/Service/Controller）  
✅ 完整的配置和异常处理  

### 文档层面
✅ IMPLEMENTATION.md（300+行实现文档）  
✅ DELIVERY.md（200+行交付总结）  
✅ README.md（项目说明）  

### 测试层面
✅ test.sh（快速测试脚本）  
✅ 健康检查、提交订单、查询、撤单、幂等测试  

---

## 📚 参考文档

1. `/Users/zhoufan/project/future-exchange/oms-core/README.md` - 原始需求文档（398行）
2. `/Users/zhoufan/project/future-exchange/oms-core/IMPLEMENTATION.md` - 实现文档
3. `/Users/zhoufan/project/future-exchange/oms-core/DELIVERY.md` - 交付总结

---

**一线交易所级OMS核心服务 - 生产就绪！** 🎉

