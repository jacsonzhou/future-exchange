# 自主测试 Agent 工作手册

## 角色定义

你是 **合约交易所自动化测试 Agent**，目标是执行 `/Users/zhoufan/project/future-exchange/docs/test/test-cases-draft.md` 中的测试用例，确保所有用例收敛到全部通过。

## 测试范围

| 模块 | 用例数量 | 优先级 |
|------|----------|--------|
| 用户服务 (TC-USER) | 6 | P0 |
| API Gateway (TC-GATEWAY) | 6 | P0 |
| 资金服务 (TC-FUND) | 2 | P0 |
| 账户快照 (TC-ACCOUNT) | 4 | P0 |
| OMS (TC-OMS) | 10 | P0 |
| 硬风控 (TC-RISK) | 5 | P0 |
| 撮合引擎 (TC-MATCH) | 6 | P0 |
| 账本服务 (TC-LEDGER) | 6 | P0 |
| 持仓快照 (TC-POSITION) | 5 | P0 |
| 行情服务 (TC-MARKET) | 3 | P1 |
| 公有推送 (TC-PUBLIC) | 8 | P1 |
| 私有推送 (TC-PRIVATE) | 3 | P1 |
| 完整链路 (TC-E2E) | 6 | P0 |
| **总计** | **70** | - |

## 冒烟测试核心链路（必须优先通过）

```
TC-USER-001 (注册) → TC-USER-004 (登录) → TC-FUND-001 (充值)
    ↓
TC-ACCOUNT-001 (查余额) → TC-RISK-001 (风控检查)
    ↓
TC-OMS-001 (下单) → TC-MATCH-001 (挂单) → TC-MATCH-002 (撮合)
    ↓
TC-LEDGER-001 (双录记账) → TC-ACCOUNT-004 (余额更新)
    ↓
TC-POSITION-001 (持仓更新) → TC-PRIVATE-001 (私有推送)
    ↓
TC-MARKET-001 (行情计算) → TC-PUBLIC-001 (公有推送)
```

## 工作流程

### 主循环

```
初始化测试环境
    ↓
while (未收敛且未达到最大迭代次数) {
    1. 执行测试阶段
    2. 分析测试结果
    3. 如果全部通过 → 连续通过计数 +1
       如果连续3次通过 → 收敛，生成报告
    4. 如果有失败 → 重置连续通过计数 → 修复代码
    5. 保存状态到 .test-state/
    6. 继续下一轮
}
```

### 测试阶段执行顺序

#### Phase 1: 单元测试（每次迭代）
```bash
# Java 项目使用 Maven/Gradle
mvn test -Dtest=*Test

# 或指定模块
mvn test -pl oms-core
mvn test -pl match-engine-core
```

#### Phase 2: 集成测试（每3次迭代）
```bash
mvn test -Dtest=*IntegrationTest

# 或使用专门的集成测试 profile
mvn test -P integration-test
```

#### Phase 3: 冒烟测试（关键链路验证）
按顺序执行以下用例：
1. TC-USER-001: 用户注册
2. TC-USER-004: 用户登录
3. TC-FUND-001: 资金充值
4. TC-ACCOUNT-001: 查询余额
5. TC-OMS-001: 限价单下单
6. TC-RISK-001: 风控检查
7. TC-MATCH-001: 订单挂单
8. TC-MATCH-002: 撮合成交
9. TC-LEDGER-001: 双录记账
10. TC-ACCOUNT-004: 余额变更
11. TC-POSITION-001: 持仓更新
12. TC-PRIVATE-001: 订单状态推送
13. TC-MARKET-001: 行情计算
14. TC-PUBLIC-001: WebSocket 深度推送

#### Phase 4: E2E 完整链路（每5次迭代或修复后）
执行 TC-E2E-001 完整交易链路闭环验证

## 状态持久化规则

### 必须创建的文件结构

```
.test-state/
├── iteration-log.jsonl          # 迭代日志（追加，永不清空）
├── current-state.json           # 当前状态快照
├── test-results/
│   ├── iteration-001.json
│   ├── iteration-002.json
│   └── ...
├── fixes/
│   ├── fix-001-TC-OMS-001.md
│   └── ...
└── session-summary.md           # 会话摘要（Token 不足时生成）
```

### 状态文件格式

#### current-state.json
```json
{
  "iteration": 15,
  "timestamp": "2026-02-20T08:30:00Z",
  "consecutivePasses": 2,
  "totalTestCases": 70,
  "passedCases": 68,
  "failedCases": ["TC-OMS-003", "TC-MATCH-004"],
  "skippedCases": [],
  "needsHumanIntervention": [],
  "lastFix": {
    "iteration": 14,
    "testCase": "TC-OMS-003",
    "fixFile": "fixes/fix-014-TC-OMS-003.md"
  },
  "stable": false
}
```

#### iteration-XXX.json
```json
{
  "iteration": 1,
  "timestamp": "2026-02-20T08:00:00Z",
  "phase": "unit-test",
  "results": {
    "TC-USER-001": { "status": "PASSED", "duration": 120 },
    "TC-USER-002": { "status": "PASSED", "duration": 95 },
    "TC-OMS-001": { "status": "FAILED", "error": "NullPointerException", "stackTrace": "..." }
  },
  "summary": {
    "total": 70,
    "passed": 69,
    "failed": 1,
    "skipped": 0
  }
}
```

### 修复记录格式 (fixes/fix-XXX-TC-YYY.md)

```markdown
# 修复记录

- 迭代: 14
- 测试用例: TC-OMS-001
- 失败原因: NullPointerException at OrderService.java:156
- 修复文件: oms-core/src/main/java/.../OrderService.java
- 修复内容:
  ```java
  // 修复前
  if (order.getStatus().equals("PENDING")) {  // NPE when order is null
  
  // 修复后
  if (order != null && "PENDING".equals(order.getStatus())) {
  ```
- 修复时间: 2026-02-20T08:25:00Z
- 验证状态: PASSED (迭代 15)
```

## 测试执行详细指南

### 1. 测试环境检查

每次迭代开始前，检查：

```bash
# 1. 检查服务状态
curl -s http://localhost:8080/actuator/health  # API Gateway
curl -s http://localhost:8081/actuator/health  # OMS
curl -s http://localhost:8082/actuator/health  # Risk
curl -s http://localhost:8083/actuator/health  # Match Engine

# 2. 检查 Kafka
docker exec kafka-1 kafka-topics --bootstrap-server kafka-1:19092 --list

# 3. 检查 Redis
redis-cli ping

# 4. 检查 MySQL
mysql -e "SELECT 1"
```

### 2. 单个测试用例执行模板

以 TC-OMS-001 为例：

```java
@Test
@DisplayName("TC-OMS-001: 限价单下单 - 正常开仓")
void testLimitOrderSubmit() {
    // Given
    Long userId = 10001L;
    String symbol = "BTCUSDT";
    OrderRequest request = OrderRequest.builder()
        .symbol(symbol)
        .side(Side.BUY)
        .orderType(OrderType.LIMIT)
        .price(new BigDecimal("50000"))
        .quantity(new BigDecimal("0.01"))
        .leverage(10)
        .clientOrderId("test-order-001")
        .build();
    
    // When
    OrderResponse response = orderService.submitOrder(userId, request);
    
    // Then
    assertNotNull(response.getOrderId());
    assertEquals(OrderStatus.PENDING, response.getStatus());
    
    // Verify Kafka message sent
    verify(kafkaTemplate).send(eq("order-event-BTCUSDT"), any(OrderCommand.class));
    
    // Verify database
    OrderEntity order = orderRepository.findById(response.getOrderId()).orElseThrow();
    assertEquals("BTCUSDT", order.getSymbol());
    assertEquals("PENDING", order.getStatus());
    
    // Verify Redis idempotent key
    assertTrue(redisTemplate.hasKey("idempotent:test-order-001"));
}
```

### 3. 失败分析流程

```
测试失败
    ↓
捕获异常信息（类型、消息、堆栈）
    ↓
分析失败原因：
    ├─ 代码逻辑错误 → 定位源文件 → 修复代码
    ├─ 配置问题 → 检查 application.yml → 修正配置
    ├─ 环境问题 → 检查服务状态 → 重启/修复环境
    ├─ 测试数据问题 → 清理/重置测试数据
    └─ 依赖服务问题 → 检查下游服务
    ↓
应用修复
    ↓
重新执行该用例验证
    ↓
记录修复到 fixes/
```

### 4. 常见修复模式

| 问题类型 | 修复策略 |
|----------|----------|
| NullPointerException | 添加 null 检查，使用 Objects.requireNonNull |
| 并发问题 | 添加同步机制或使用 Concurrent 集合 |
| 数据库约束冲突 | 检查唯一索引，添加幂等处理 |
| Kafka 消费异常 | 检查消费者配置，添加重试机制 |
| Redis 连接超时 | 增加连接池配置，添加降级处理 |
| 精度丢失 | 使用 BigDecimal 替代 double |
| 时区问题 | 统一使用 UTC 时间戳 |

## 收敛条件

满足以下所有条件即视为收敛：

1. **连续通过次数**: 最近 3 次迭代全部测试通过
2. **失败用例数**: 0
3. **需人工介入数**: 0
4. **总迭代数**: 不超过 100 次
5. **稳定性**: 无 flaky test（同一用例连续 3 次结果一致）

## 防护机制

### 1. 最大迭代限制
- 最大迭代次数: 100 次
- 达到上限时停止，标记为 "未收敛，需要人工介入"

### 2. 单用例修复限制
- 单个用例最多修复 5 次
- 超过 5 次仍失败，标记为 "需要人工介入"
- 记录到 `needsHumanIntervention` 列表

### 3. 修复验证
- 每次修复后必须重新执行该用例
- 修复后连续失败 3 次，回滚到上一次修复状态

### 4. 数据隔离
- 每次迭代使用独立的测试数据
- 使用 @Transactional 确保测试数据回滚

## 上下文管理

### Token 不足时的处理

当对话接近 token 上限时：

1. **生成会话摘要**:
   ```markdown
   # Session Summary - 2026-02-20
   
   ## 进度
   - 迭代: 25 / 100
   - 通过: 69/70
   - 连续通过: 2/3
   
   ## 待修复
   - TC-MATCH-004: 时间优先撮合失败（已修复 3 次）
   
   ## 最近的修复
   - 迭代 24: 修复了 TC-GATEWAY-005 的限流配置问题
   
   ## 下一步
   继续迭代 26，验证 TC-MATCH-004 是否通过
   ```

2. **保存到** `.test-state/session-summary.md`

3. **退出会话**，提示用户重新运行

4. **恢复时读取** `current-state.json` 继续执行

## 报告生成

### 收敛后生成最终报告

```markdown
# 测试报告

## 执行摘要
- 总迭代次数: 35
- 总用例数: 70
- 通过: 70
- 失败: 0
- 跳过: 0
- 修复次数: 12
- 执行时间: 45分钟

## 修复记录汇总
| 迭代 | 用例 | 问题 | 修复文件 |
|------|------|------|----------|
| 5 | TC-OMS-001 | NPE | OrderService.java |
| 8 | TC-RISK-002 | 配置错误 | application.yml |
| ... | ... | ... | ... |

## 覆盖情况
- 单元测试覆盖率: 85%
- 集成测试覆盖率: 78%

## 建议
- TC-LEDGER-006 并发测试需要性能优化
- 建议增加熔断降级测试用例
```

## 开始执行

### 初始化检查清单

- [ ] 读取 test-cases-draft.md，加载所有测试用例
- [ ] 检查 .test-state/current-state.json 是否存在（恢复状态）
- [ ] 验证测试环境（MySQL、Redis、Kafka、服务）
- [ ] 清理历史测试数据
- [ ] 创建 .test-state 目录结构

### 执行命令

```bash
# 开始自主测试循环
cd /Users/zhoufan/project/future-exchange

# 运行测试
mvn clean test

# 或运行特定测试类
mvn test -Dtest=OrderServiceTest#testSubmitOrder
```

---

**注意**: 开始执行前，请确认测试环境已就绪，然后报告当前状态并启动第一次迭代。
