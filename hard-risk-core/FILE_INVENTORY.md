# Hard Risk Gate - 项目文件清单

## 📦 项目结构

```
hard-risk-core/
├── src/main/java/com/exchange/risk/
│   ├── HardRiskApplication.java                 # 启动类 ★
│   │
│   ├── entity/                                  # 实体层 (5个) ★
│   │   ├── RiskAccountSnapshot.java             # 账户快照实体
│   │   ├── RiskPositionSnapshot.java            # 持仓快照实体
│   │   ├── RiskUserList.java                    # 黑白名单实体
│   │   ├── RiskCheckLog.java                    # 风控审计日志实体
│   │   └── RiskSymbolConfig.java                # 交易对配置实体
│   │
│   ├── mapper/                                  # Mapper层 (5个) ★
│   │   ├── RiskAccountSnapshotMapper.java       # 账户快照Mapper
│   │   ├── RiskPositionSnapshotMapper.java      # 持仓快照Mapper
│   │   ├── RiskUserListMapper.java              # 黑白名单Mapper
│   │   ├── RiskCheckLogMapper.java              # 审计日志Mapper
│   │   └── RiskSymbolConfigMapper.java          # 交易对配置Mapper
│   │
│   ├── dto/                                     # DTO层 (2个) ★
│   │   ├── CheckOrderRiskRequest.java           # 风控检查请求
│   │   └── CheckOrderRiskResponse.java          # 风控检查响应
│   │
│   ├── enums/                                   # 枚举 (2个) ★
│   │   ├── RiskResult.java                      # 风控结果枚举
│   │   └── RiskRejectReason.java                # 拒绝原因枚举
│   │
│   ├── service/                                 # 服务层 (2个) ★
│   │   ├── HardRiskService.java                 # 风控服务接口
│   │   └── impl/
│   │       └── HardRiskServiceImpl.java         # 风控服务实现（280+行）★★★
│   │
│   ├── controller/                              # 控制器层 (2个) ★
│   │   ├── HardRiskController.java              # 对外API接口
│   │   └── RiskInternalController.java          # 内部接口
│   │
│   └── config/                                  # 配置 (2个) ★
│       ├── MybatisPlusConfig.java               # MyBatis Plus配置
│       └── JacksonConfig.java                   # Jackson配置
│
├── src/main/resources/
│   └── application.yml                          # 应用配置 ★
│
├── pom.xml                                      # Maven配置 ★
├── test.sh                                      # 快速测试脚本 ★
├── hard-risk.md                                 # 原始需求文档
├── IMPLEMENTATION.md                            # 实现文档 ★ (500+行)
└── DELIVERY.md                                  # 交付总结 ★ (300+行)
```

**★ = 本次新生成/更新的文件**

---

## 📊 文件统计

### Java源文件
- **新生成**：18个
- **更新**：2个（HardRiskApplication, application.yml）
- **总计**：20个

### 代码量（新生成部分）
- **Entity**：约250行（5个实体）
- **Mapper**：约80行（5个Mapper）
- **DTO**：约100行（2个DTO）
- **Enum**：约100行（2个枚举）
- **Service**：约300行（核心ServiceImpl约280行）
- **Controller**：约80行（2个Controller）
- **Config**：约50行（2个配置）
- **总计**：约960+行

### SQL文件
- **`hard_risk_schema.sql`**：1个（150+行）
  - 5张核心表
  - 初始化示例数据

### 配置文件
- **`application.yml`**：1个（更新）
- **`pom.xml`**：1个（更新）

### 文档
- **`hard-risk.md`**：1个（原始需求文档，343行）
- **`IMPLEMENTATION.md`**：1个（新增，500+行）
- **`DELIVERY.md`**：1个（新增，300+行）

### 脚本
- **`test.sh`**：1个（新增，80+行）

---

## 🎯 核心文件说明

### 1. HardRiskServiceImpl.java（核心中的核心）

**代码行数**：280+行  
**核心功能**：
- ✅ 同步风控检查
- ✅ 8大风控规则链
- ✅ 保证金计算
- ✅ ReduceOnly规则
- ✅ 价格有效性检查
- ✅ 仓位限制检查
- ✅ 账户状态检查
- ✅ 黑白名单检查
- ✅ 审计日志记录
- ✅ Fail-Close策略

**核心方法**：
```java
checkOrderRisk()        // 主入口（协调所有规则）
executeRiskRules()      // 执行风控规则链（核心）
logRiskCheck()          // 记录审计日志
```

**风控规则执行顺序**：
```java
1. 黑名单检查（最优先）
2. 账户状态检查
3. 杠杆限制检查
4. 价格有效性检查
5. 订单数量检查
6. ReduceOnly检查
7. 仓位限制检查
8. 保证金检查（最核心）
```

### 2. Entity层（5个实体）

**RiskAccountSnapshot.java**：
- 账户快照实体
- 业务方法：isNormal(), isFrozen(), isLiquidating()

**RiskPositionSnapshot.java**：
- 持仓快照实体
- 业务方法：isLong(), isShort(), isNormal()

**RiskUserList.java**：
- 黑白名单实体
- 业务方法：isBlackList(), isWhiteList()

**RiskCheckLog.java**：
- 风控审计日志实体
- 完整的审计字段

**RiskSymbolConfig.java**：
- 交易对配置实体
- 业务方法：isEnabled()

### 3. Mapper层（5个Mapper）

**RiskAccountSnapshotMapper.java**：
```java
selectByUserId()  // 查询账户快照
```

**RiskPositionSnapshotMapper.java**：
```java
selectByUserIdAndSymbol()  // 查询持仓快照
```

**RiskUserListMapper.java**：
```java
selectByUserId()  // 查询黑白名单
```

**RiskSymbolConfigMapper.java**：
```java
selectBySymbol()  // 查询交易对配置
```

### 4. SQL Schema

**hard_risk_schema.sql**：
```sql
-- 5张核心表
risk_account_snapshot      -- 账户快照
risk_position_snapshot     -- 持仓快照
risk_user_list             -- 黑白名单
risk_check_log             -- 风控审计日志
risk_symbol_config         -- 交易对配置

-- 关键索引
idx_user_id                -- 用户索引
uk_user_symbol             -- 用户+交易对唯一索引
uk_user                    -- 用户唯一索引
uk_symbol                  -- 交易对唯一索引

-- 初始化数据
BTCUSDT配置（杠杆125x）
ETHUSDT配置（杠杆100x）
```

---

## 🔑 核心设计亮点

### 1. 8大风控规则链

```
Rule 1: 黑名单检查
  ↓
Rule 2: 账户状态检查
  ↓
Rule 3: 杠杆限制检查
  ↓
Rule 4: 价格有效性检查
  ↓
Rule 5: 订单数量检查
  ↓
Rule 6: ReduceOnly检查
  ↓
Rule 7: 仓位限制检查
  ↓
Rule 8: 保证金检查（最核心）
```

### 2. 保证金计算（最核心）

```java
// 计算所需保证金
notional = price × quantity
requiredMargin = notional ÷ leverage

// 校验
if (availableMargin < requiredMargin) {
    REJECT(INSUFFICIENT_MARGIN)
}
```

### 3. ReduceOnly 规则（容易出事故）

```java
if (reduceOnly) {
    // 必须有持仓
    if (position == null) {
        REJECT(REDUCE_ONLY_VIOLATION)
    }
    
    // 不能增加仓位
    // BUY只能平空仓，SELL只能平多仓
    if ((isBuy && isLongPosition) || (!isBuy && !isLongPosition)) {
        REJECT(REDUCE_ONLY_VIOLATION)
    }
}
```

### 4. 价格有效性（防插针）

```java
// 基于标记价格
deviation = |orderPrice - markPrice| / markPrice

if (deviation > maxPriceDeviationPct) {
    REJECT(PRICE_OUT_OF_RANGE)
}
```

### 5. Fail-Close 策略

```java
try {
    return executeRiskRules(...);
} catch (Exception e) {
    log.error("Risk check error", e);
    // 出错即拒单，绝不放过
    return REJECT(INSUFFICIENT_MARGIN);
}
```

### 6. 审计日志

```java
// 所有风控检查都记录到数据库
risk_check_log:
  - order_id          # 订单ID
  - user_id           # 用户ID
  - symbol            # 交易对
  - result            # PASS/REJECT
  - reject_reason     # 拒绝原因
  - required_margin   # 所需保证金
  - available_margin  # 可用保证金
  - trace_id          # 追踪ID
  - created_at        # 检查时间
```

---

## 🚀 API接口

### 检查订单风险

```
POST /api/v1/risk/check
Headers: X-User-Id, X-Trace-Id, X-Request-Id
Body: { orderId, symbol, side, price, quantity, leverage, reduceOnly }
```

**Response (PASS)**：
```json
{
  "result": "PASS",
  "rejectReason": "NONE",
  "requiredMargin": "430.00000000",
  "availableMargin": "10000.00000000",
  "riskCheckTime": 1706198400000
}
```

**Response (REJECT)**：
```json
{
  "result": "REJECT",
  "rejectReason": "INSUFFICIENT_MARGIN",
  "rejectMessage": "保证金不足",
  "requiredMargin": "4300.00000000",
  "availableMargin": "1000.00000000",
  "riskCheckTime": 1706198400000
}
```

---

## 📋 错误码规范

| Code | Reason | 说明 |
|------|--------|------|
| 0 | NONE | 通过 |
| 1 | INSUFFICIENT_MARGIN | 保证金不足 ★ |
| 2 | LEVERAGE_EXCEEDED | 杠杆超限 |
| 3 | POSITION_LIMIT_EXCEEDED | 仓位超限 |
| 4 | REDUCE_ONLY_VIOLATION | 只减仓违规 ★ |
| 5 | ACCOUNT_FROZEN | 账户冻结 |
| 6 | ACCOUNT_LIQUIDATING | 账户强平中 |
| 7 | PRICE_OUT_OF_RANGE | 价格超出范围 ★ |
| 8 | RISK_BLACKLISTED | 风控黑名单 |
| 9 | SYMBOL_CONFIG_NOT_FOUND | 交易对配置不存在 |
| 10 | ACCOUNT_NOT_FOUND | 账户不存在 |
| 11 | ORDER_QTY_EXCEEDED | 订单数量超限 |

---

## ✅ 生产级特性清单

- ✅ 同步风控检查
- ✅ 强一致性（读取最新快照）
- ✅ 完整的8大风控规则链
- ✅ Fail-Close策略（失败即拒单）
- ✅ 审计日志（所有检查可追溯）
- ✅ 分布式追踪
- ✅ 错误码规范
- ✅ MyBatis Plus集成
- ✅ Nacos服务发现
- ✅ 完整文档
- ✅ 测试脚本
- ✅ 无状态设计（水平扩展）

---

## 🎯 交付成果

### 代码层面
✅ 18个新Java文件（960+行）  
✅ 1个SQL Schema（150+行）  
✅ 完整的分层架构（Entity/Mapper/DTO/Service/Controller）  
✅ 完整的配置和异常处理  
✅ 8大风控规则链  

### 文档层面
✅ IMPLEMENTATION.md（500+行实现文档）  
✅ DELIVERY.md（300+行交付总结）  
✅ hard-risk.md（原始需求文档）  

### 测试层面
✅ test.sh（快速测试脚本）  
✅ 健康检查、正常订单、保证金不足、杠杆超限、ReduceOnly违规测试  

---

## 📚 参考文档

1. `/Users/zhoufan/project/future-exchange/hard-risk-core/hard-risk.md` - 原始需求文档（343行）
2. `/Users/zhoufan/project/future-exchange/hard-risk-core/IMPLEMENTATION.md` - 实现文档
3. `/Users/zhoufan/project/future-exchange/hard-risk-core/DELIVERY.md` - 交付总结

---

**一线交易所级Hard Risk Gate - 生产就绪！** 🎉

**最后防线，绝不放过任何一笔风险订单！**

