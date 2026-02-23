# 合约交易所测试用例文档

> 本文档基于 `/docs/test/test-scenarios.md` 生成，覆盖完整冒烟测试链路
> 
> **状态**: Review 完成
> **适用版本**: v1.0.0

---

## 文档说明

### 冒烟测试核心链路

```
用户注册 → 用户登录 → 初始化账本（资金充值） → 查询余额（可用/冻结）
    ↓
下单 → OMS → 风控检查 → Kafka → 撮合引擎
    ↓
撮合成交 → TradeEvent → Ledger（双录记账）
    ↓
    ├→ Snapshot（账户余额/持仓更新）→ 私有推送（订单/账户变更）
    └→ Market Price（行情计算）→ Public Push（盘口/K线/成交推送）
```

### 测试用例编号规则

| 模块 | 编号前缀 |
|------|----------|
| 用户服务 | TC-USER-xxx |
| API Gateway | TC-GATEWAY-xxx |
| 资金服务 | TC-FUND-xxx |
| 账户快照 | TC-ACCOUNT-xxx |
| OMS | TC-OMS-xxx |
| 硬风控 | TC-RISK-xxx |
| 撮合引擎 | TC-MATCH-xxx |
| 账本服务 | TC-LEDGER-xxx |
| 持仓快照 | TC-POSITION-xxx |
| 行情服务 | TC-MARKET-xxx |
| 公有推送 | TC-PUBLIC-xxx |
| 私有推送 | TC-PRIVATE-xxx |
| 完整链路 | TC-E2E-xxx |

---

## 1. 用户服务测试用例

### 1.1 用户注册

| 用例编号 | TC-USER-001 |
|---------|------------|
| **用例名称** | 用户注册 - 正常流程 |
| **前置条件** | 1. User Core 服务正常运行（端口 8080/内部端口）<br>2. MySQL `exchange_user` 数据库连接正常<br>3. Redis 连接正常 |
| **测试步骤** | 1. 调用 POST `/api/v1/user/register`<br>2. 请求体：`{"username": "testuser001", "password": "Test@123456", "email": "test001@example.com", "phone": "13800138001"}`<br>3. 系统校验参数格式（用户名长度、密码强度、邮箱格式）<br>4. 检查用户名唯一性<br>5. 密码加密（BCrypt）<br>6. 生成用户ID（雪花算法）<br>7. 写入 `t_user` 表<br>8. 初始化用户风控等级（默认杠杆限制 20x） |
| **预期结果** | 1. HTTP 状态码 200<br>2. 返回用户ID（如：`1891234567890123456`）<br>3. MySQL `t_user` 表新增记录<br>4. 密码已加密存储（非明文）<br>5. 默认状态：NORMAL |

| 用例编号 | TC-USER-002 |
|---------|------------|
| **用例名称** | 用户注册 - 用户名重复 |
| **前置条件** | 1. 已存在用户名为 `testuser001` 的用户 |
| **测试步骤** | 1. 发送注册请求，用户名使用 `testuser001`<br>2. 系统检查用户名唯一性 |
| **预期结果** | 1. HTTP 状态码 400<br>2. 错误码：`USER_ALREADY_EXISTS`<br>3. 错误信息："用户名已存在" |

| 用例编号 | TC-USER-003 |
|---------|------------|
| **用例名称** | 用户注册 - 参数校验失败 |
| **前置条件** | 无 |
| **测试步骤** | 1. 密码长度 < 6 位<br>2. 或邮箱格式不正确（如：`invalid-email`）<br>3. 或用户名为空 |
| **预期结果** | 1. HTTP 状态码 400<br>2. 返回详细校验错误信息（字段级错误） |

---

### 1.2 用户登录

| 用例编号 | TC-USER-004 |
|---------|------------|
| **用例名称** | 用户登录 - 正常流程 |
| **前置条件** | 1. 用户已注册（username: `testuser001`, password: `Test@123456`）<br>2. 用户状态正常（非冻结/黑名单） |
| **测试步骤** | 1. 调用 POST `/api/v1/user/login`<br>2. 请求体：`{"username": "testuser001", "password": "Test@123456"}`<br>3. 验证用户名存在<br>4. BCrypt 校验密码<br>5. 生成 JWT Token（包含 userId, username, 过期时间）<br>6. 记录登录日志 |
| **预期结果** | 1. HTTP 状态码 200<br>2. 返回 JWT Token（格式：`eyJhbGciOiJIUzI1NiIs...`）<br>3. Token 包含 claims：userId, exp（24小时后过期）<br>4. 返回用户基本信息（userId, username, maxLeverage） |

| 用例编号 | TC-USER-005 |
|---------|------------|
| **用例名称** | 用户登录 - 密码错误 |
| **前置条件** | 1. 用户已注册 |
| **测试步骤** | 1. 调用登录接口，密码错误（如：`WrongPass123`）<br>2. BCrypt 校验失败 |
| **预期结果** | 1. HTTP 状态码 401<br>2. 错误码：`INVALID_CREDENTIALS`<br>3. 记录失败登录日志 |

| 用例编号 | TC-USER-006 |
|---------|------------|
| **用例名称** | 用户登录 - 用户被冻结 |
| **前置条件** | 1. 用户状态为 `FROZEN` |
| **测试步骤** | 1. 调用登录接口 |
| **预期结果** | 1. HTTP 状态码 403<br>2. 错误码：`USER_FROZEN`<br>3. 错误信息："账户已被冻结，请联系客服" |

---

## 2. API Gateway 测试用例

### 2.1 JWT 认证

| 用例编号 | TC-GATEWAY-001 |
|---------|---------------|
| **用例名称** | JWT 认证 - Token 有效 |
| **前置条件** | 1. 用户已登录，获得有效 JWT Token<br>2. API Gateway 服务正常运行（端口 8080） |
| **测试步骤** | 1. 发送请求到 `/api/v1/oms/order/submit`<br>2. Header 中包含：`Authorization: Bearer {valid_token}`<br>3. Gateway 解析 JWT Token<br>4. 验证签名和过期时间<br>5. 提取 userId<br>6. 设置 `X-User-Id: {userId}` Header<br>7. 转发请求到 OMS Core |
| **预期结果** | 1. Token 验证通过<br>2. 请求成功转发到后端服务<br>3. 后端服务收到 `X-User-Id` Header |

| 用例编号 | TC-GATEWAY-002 |
|---------|---------------|
| **用例名称** | JWT 认证 - Token 过期 |
| **前置条件** | 1. 使用已过期的 JWT Token |
| **测试步骤** | 1. 发送请求，Header 中包含过期 Token |
| **预期结果** | 1. HTTP 状态码 401<br>2. 错误码：`TOKEN_EXPIRED`<br>3. 请求未转发到后端服务 |

| 用例编号 | TC-GATEWAY-003 |
|---------|---------------|
| **用例名称** | JWT 认证 - Token 缺失 |
| **前置条件** | 无 |
| **测试步骤** | 1. 发送请求到受保护端点<br>2. Header 中不包含 `Authorization` |
| **预期结果** | 1. HTTP 状态码 401<br>2. 错误码：`UNAUTHORIZED`<br>3. 错误信息："未授权，请先登录" |

---

### 2.2 限流检查

| 用例编号 | TC-GATEWAY-004 |
|---------|---------------|
| **用例名称** | 限流检查 - IP 级限流 |
| **前置条件** | 1. 限流配置：1000 请求/分钟/IP |
| **测试步骤** | 1. 同一 IP 发送 1001 个请求 |
| **预期结果** | 1. 前 1000 个请求通过<br>2. 第 1001 个请求被拒绝<br>3. HTTP 状态码 429<br>4. 响应头包含 `Retry-After` |

| 用例编号 | TC-GATEWAY-005 |
|---------|---------------|
| **用例名称** | 限流检查 - 用户级限流 |
| **前置条件** | 1. 限流配置：100 请求/分钟/用户 |
| **测试步骤** | 1. 同一用户发送 101 个下单请求 |
| **预期结果** | 1. 第 101 个请求被拒绝<br>2. HTTP 状态码 429<br>3. 错误信息："请求过于频繁" |

| 用例编号 | TC-GATEWAY-006 |
|---------|---------------|
| **用例名称** | 限流拒绝后恢复测试 |
| **前置条件** | 1. 用户已触发限流（100 请求/分钟）<br>2. 限流窗口：1分钟 |
| **测试步骤** | 1. 用户触发限流（第101个请求被拒绝）<br>2. 等待1分钟后重试<br>3. 验证限流窗口已重置<br>4. 发送新的请求 |
| **预期结果** | 1. 等待期间请求持续被拒绝<br>2. 1分钟后请求恢复正常<br>3. 限流计数器已清零<br>4. Redis 限流键 TTL 正确 |

---

## 3. 资金服务测试用例

### 3.1 资金初始化（充值）

| 用例编号 | TC-FUND-001 |
|---------|------------|
| **用例名称** | 资金充值 - USDT 充值（冒烟测试关键步骤） |
| **前置条件** | 1. Ledger Core 服务正常运行<br>2. 用户已注册（userId: 10001）<br>3. 用户当前 USDT 余额为 0 |
| **测试步骤** | 1. 调用资金充值接口（内部接口或模拟入金）<br>2. 请求参数：`{userId: 10001, asset: "USDT", amount: 100000000000000}`（10000 USDT，8位小数）<br>3. Ledger Core 生成双录分录：<br>   - 借：用户资产 USDT 10000<br>   - 贷：系统负债 USDT 10000<br>4. 插入 `t_ledger_entry` 表<br>5. 发送 `account-change-topic` 事件 |
| **预期结果** | 1. 充值成功<br>2. 分录借贷平衡<br>3. `t_ledger_entry` 新增两条记录<br>4. Kafka 事件已发送 |

| 用例编号 | TC-FUND-002 |
|---------|------------|
| **用例名称** | 资金充值 - 多币种充值 |
| **前置条件** | 1. 用户已注册 |
| **测试步骤** | 1. 充值 USDT 10000<br>2. 充值 BTC 0.5 |
| **预期结果** | 1. USDT 余额：10000<br>2. BTC 余额：0.5<br>3. 各币种分录正确 |

---

## 4. 账户快照服务测试用例

### 4.1 查询账户余额

| 用例编号 | TC-ACCOUNT-001 |
|---------|---------------|
| **用例名称** | 查询余额 - 初始化后查询（冒烟测试关键步骤） |
| **前置条件** | 1. Snapshot Account Core 服务正常运行（端口 8085）<br>2. 用户已完成资金充值（USDT 10000）<br>3. Redis 连接正常 |
| **测试步骤** | 1. 用户调用 GET `/api/v1/account/balance`<br>2. 携带 JWT Token<br>3. Gateway 认证并转发<br>4. Snapshot Account Core 查询 Redis（`account:{userId}:USDT`）<br>5. 返回余额信息 |
| **预期结果** | 1. HTTP 状态码 200<br>2. 返回数据包含：<br>   - `asset`: "USDT"<br>   - `totalBalance`: 100000000000000（10000 USDT）<br>   - `availableBalance`: 100000000000000<br>   - `frozenBalance`: 0 |

| 用例编号 | TC-ACCOUNT-002 |
|---------|---------------|
| **用例名称** | 查询余额 - 缓存未命中从 DB 加载 |
| **前置条件** | 1. Redis 中无该用户余额缓存 |
| **测试步骤** | 1. 发送查询余额请求<br>2. Redis 查询未命中<br>3. 从 MySQL `t_account_balance` 查询<br>4. 写入 Redis 缓存 |
| **预期结果** | 1. 返回正确余额<br>2. Redis 缓存已更新<br>3. 后续查询命中缓存 |

| 用例编号 | TC-ACCOUNT-003 |
|---------|---------------|
| **用例名称** | 查询余额 - 下单后冻结资金 |
| **前置条件** | 1. 用户已下单（保证金 500 USDT 已冻结） |
| **测试步骤** | 1. 查询账户余额 |
| **预期结果** | 1. `totalBalance`: 10000 USDT<br>2. `availableBalance`: 9500 USDT<br>3. `frozenBalance`: 500 USDT |

---

### 4.2 余额变更消费

| 用例编号 | TC-ACCOUNT-004 |
|---------|---------------|
| **用例名称** | 消费账本事件更新余额 |
| **前置条件** | 1. Kafka 消费者正常运行<br>2. 用户当前 USDT：可用 10000，冻结 0 |
| **测试步骤** | 1. Ledger Core 发送 `account-change-topic` 事件<br>2. 事件内容：冻结 500 USDT<br>3. Snapshot Account Core 消费事件<br>4. 更新 MySQL `t_account_balance`<br>5. 更新 Redis `account:{userId}:USDT` |
| **预期结果** | 1. MySQL 记录已更新<br>2. Redis 缓存已更新<br>3. 可用余额：9500<br>4. 冻结余额：500 |

---

## 5. 订单管理服务（OMS）测试用例

### 5.1 下单

| 用例编号 | TC-OMS-001 |
|---------|-----------|
| **用例名称** | 限价单下单 - 正常开仓（冒烟测试关键步骤） |
| **前置条件** | 1. OMS Core 服务正常运行（端口 8081）<br>2. 用户已登录（userId: 10001）<br>3. 账户余额：USDT 10000（可用）<br>4. Hard Risk Core 服务正常<br>5. Kafka 正常运行 |
| **测试步骤** | 1. 调用 POST `/api/v1/oms/order/submit`<br>2. 请求体：<br>```json<br>{<br>  "symbol": "BTCUSDT",<br>  "side": "BUY",<br>  "orderType": "LIMIT",<br>  "price": "5000000000000",<br>  "quantity": "100000000",<br>  "leverage": 10,<br>  "clientOrderId": "test-order-001"<br>}<br>```<br>3. OMS 参数校验<br>4. 幂等性检查（Redis `idempotent:test-order-001`）<br>5. 计算保证金：`5000000000000 * 100000000 / 10 = 50000000000000`（500 USDT）<br>6. 调用 Hard Risk Core（Feign）<br>7. 风控通过后，发送 Kafka 到 `order-event-BTCUSDT`<br>8. 返回订单信息 |
| **预期结果** | 1. HTTP 状态码 200<br>2. 返回订单ID（雪花算法）<br>3. 订单状态：`PENDING`<br>4. Redis 幂等标记已设置（TTL: 24h）<br>5. MySQL `t_order` 表新增记录<br>6. Kafka 消息已发送（Topic: `order-event-BTCUSDT`） |

| 用例编号 | TC-OMS-002 |
|---------|-----------|
| **用例名称** | 下单 - 市价单 |
| **前置条件** | 1. 用户余额充足 |
| **测试步骤** | 1. 发送市价单请求（`orderType: MARKET`）<br>2. 不指定 price 或 price 为 null<br>3. OMS 校验参数<br>4. 调用风控<br>5. 发送 Kafka |
| **预期结果** | 1. 订单创建成功<br>2. 订单类型：MARKET<br>3. 按市场最优价格成交 |

| 用例编号 | TC-OMS-003 |
|---------|-----------|
| **用例名称** | 下单 - 重复提交（幂等性） |
| **前置条件** | 1. 已使用 `clientOrderId: test-order-001` 成功下单 |
| **测试步骤** | 1. 使用相同的 `clientOrderId` 再次下单<br>2. OMS 检查 Redis 幂等标记 |
| **预期结果** | 1. HTTP 状态码 200<br>2. 返回已存在的订单ID<br>3. 不创建新订单<br>4. 不重复发送 Kafka 消息 |

| 用例编号 | TC-OMS-004 |
|---------|-----------|
| **用例名称** | 下单 - 风控拒绝（余额不足） |
| **前置条件** | 1. 用户 USDT 余额：100 |
| **测试步骤** | 1. 发送下单请求（需要保证金 500 USDT）<br>2. OMS 调用 Hard Risk Core<br>3. 风控检查余额不足 |
| **预期结果** | 1. HTTP 状态码 400<br>2. 错误码：`INSUFFICIENT_BALANCE`<br>3. 订单状态：`REJECTED`<br>4. 不发送 Kafka 消息 |

| 用例编号 | TC-OMS-005 |
|---------|-----------|
| **用例名称** | 下单 - 风控拒绝（杠杆超限） |
| **前置条件** | 1. 用户最大杠杆限制：5x<br>2. 用户余额充足 |
| **测试步骤** | 1. 发送下单请求，leverage: 10 |
| **预期结果** | 1. HTTP 状态码 400<br>2. 错误码：`LEVERAGE_EXCEEDED`<br>3. 订单状态：`REJECTED` |

| 用例编号 | TC-OMS-006 |
|---------|-----------|
| **用例名称** | 下单 - 参数校验失败 |
| **前置条件** | 无 |
| **测试步骤** | 1. price 为负数<br>2. 或 quantity 为 0<br>3. 或 symbol 为空 |
| **预期结果** | 1. HTTP 状态码 400<br>2. 返回详细参数校验错误 |

---

### 5.2 订单状态管理

| 用例编号 | TC-OMS-007 |
|---------|-----------|
| **用例名称** | 订单状态更新 - 撮合回传 |
| **前置条件** | 1. 订单状态为 `PENDING`<br>2. Kafka 消费者正常运行 |
| **测试步骤** | 1. 撮合引擎发送订单状态到 `order-state-BTCUSDT`<br>2. 消息内容：`{orderId: xxx, status: "PARTIALLY_FILLED", filledQty: 50000000}`<br>3. OMS 消费消息<br>4. 更新订单状态<br>5. 插入 `t_order_event` 表 |
| **预期结果** | 1. 订单状态更新为 `PARTIALLY_FILLED`<br>2. `filledQuantity`: 0.005 BTC<br>3. `t_order_event` 新增状态变更记录 |

| 用例编号 | TC-OMS-008 |
|---------|-----------|
| **用例名称** | 撤单 - 正常流程 |
| **前置条件** | 1. 订单状态为 `PENDING` 或 `PARTIALLY_FILLED`<br>2. 订单未完全成交 |
| **测试步骤** | 1. 调用 POST `/api/v1/oms/order/cancel`<br>2. 参数：`{orderId: xxx}`<br>3. OMS 校验订单所有权<br>4. 检查订单状态<br>5. 发送撤单命令到 Kafka `order-event-BTCUSDT`<br>6. 更新订单状态为 `CANCELING` |
| **预期结果** | 1. HTTP 状态码 200<br>2. 订单状态：`CANCELING`<br>3. Kafka 消息已发送 |

| 用例编号 | TC-OMS-009 |
|---------|-----------|
| **用例名称** | 查询订单列表 |
| **前置条件** | 1. 用户有多个订单 |
| **测试步骤** | 1. 调用 GET `/api/v1/oms/order/list`<br>2. 参数：`symbol=BTCUSDT&status=PENDING&page=1&size=20` |
| **预期结果** | 1. HTTP 状态码 200<br>2. 返回订单列表<br>3. 支持分页 |

| 用例编号 | TC-OMS-010 |
|---------|-----------|
| **用例名称** | 幂等键过期后重试 |
| **前置条件** | 1. 用户曾使用 `clientOrderId: test-order-001` 下单<br>2. 幂等键 TTL 已过期（24小时后）<br>3. Redis 中幂等键已被清除 |
| **测试步骤** | 1. 使用相同的 `clientOrderId: test-order-001` 再次下单<br>2. OMS 检查 Redis 幂等标记<br>3. 未找到幂等键<br>4. 创建新订单 |
| **预期结果** | 1. HTTP 状态码 200<br>2. 创建新订单成功<br>3. 返回新的 orderId（与之前不同）<br>4. 不会被误判为重复提交<br>5. 新幂等键写入 Redis |

---

## 6. 硬风控服务测试用例

### 6.1 下单前风控检查

| 用例编号 | TC-RISK-001 |
|---------|------------|
| **用例名称** | 风控检查 - 余额充足（冒烟测试关键步骤） |
| **前置条件** | 1. Hard Risk Core 服务正常运行（端口 8082）<br>2. Redis 中存在账户快照<br>3. 用户 USDT 余额：可用 10000，冻结 0 |
| **测试步骤** | 1. OMS 调用 Hard Risk Core（Feign）<br>2. 请求参数：<br>```json<br>{<br>  "userId": 10001,<br>  "symbol": "BTCUSDT",<br>  "side": "BUY",<br>  "requiredMargin": 50000000000000,<br>  "leverage": 10,<br>  "maxLeverage": 20<br>}<br>```<br>3. Hard Risk Core 查询 Redis（`account:10001:USDT`）<br>4. 检查可用余额 >= 保证金（10000 >= 500）<br>5. 检查杠杆限制（10 <= 20）<br>6. 检查用户黑名单（Redis `blacklist:user:10001`）<br>7. 返回 RiskCheckResult |
| **预期结果** | 1. 风控检查通过<br>2. 返回 `passed: true`<br>3. 响应时间 < 3ms |

| 用例编号 | TC-RISK-002 |
|---------|------------|
| **用例名称** | 风控检查 - 可用余额不足 |
| **前置条件** | 1. 用户 USDT：可用 300，冻结 0 |
| **测试步骤** | 1. 下单需要保证金 500 USDT<br>2. 查询账户快照<br>3. 检查余额 |
| **预期结果** | 1. 风控检查失败<br>2. 返回 `passed: false`<br>3. 错误码：`INSUFFICIENT_BALANCE`<br>4. 错误信息："可用余额不足" |

| 用例编号 | TC-RISK-003 |
|---------|------------|
| **用例名称** | 风控检查 - 杠杆超限 |
| **前置条件** | 1. 用户最大杠杆限制：5x（Redis `user:10001:maxLeverage`） |
| **测试步骤** | 1. 下单请求杠杆：10x |
| **预期结果** | 1. 风控检查失败<br>2. 错误码：`LEVERAGE_EXCEEDED` |

| 用例编号 | TC-RISK-004 |
|---------|------------|
| **用例名称** | 风控检查 - 用户黑名单 |
| **前置条件** | 1. Redis `blacklist:user:10001 = true` |
| **测试步骤** | 1. 发送下单请求<br>2. 检查黑名单 |
| **预期结果** | 1. 风控检查失败<br>2. 错误码：`USER_BLACKLISTED`<br>3. HTTP 状态码 403 |

| 用例编号 | TC-RISK-005 |
|---------|------------|
| **用例名称** | 风控检查 - 价格保护 |
| **前置条件** | 1. 标记价格：50000 USDT<br>2. 价格保护阈值：5% |
| **测试步骤** | 1. 用户发送限价单，价格 60000（偏离 20%） |
| **预期结果** | 1. 风控检查失败<br>2. 错误码：`PRICE_DEVIATION_TOO_LARGE` |

---

## 7. 撮合引擎测试用例

### 7.1 订单撮合

| 用例编号 | TC-MATCH-001 |
|---------|-------------|
| **用例名称** | 订单处理 - 挂单等待（冒烟测试关键步骤） |
| **前置条件** | 1. Match Engine Core 服务正常运行（端口 8083）<br>2. Disruptor RingBuffer 已初始化<br>3. OrderBook 为空<br>4. Kafka 消费者正常运行 |
| **测试步骤** | 1. 消费 Kafka `order-event-BTCUSDT`<br>2. 消息内容：OrderCommand（BUY, LIMIT, price=50000, qty=0.01）<br>3. Disruptor 接收事件<br>4. 单线程 EventHandler 处理<br>5. 检查 OrderBook 对手盘<br>6. 无匹配，订单加入 OrderBook（买方队列）<br>7. 写 WAL 日志（`data/wal/match.log`）<br>8. 发送订单状态回传到 `order-state-BTCUSDT` |
| **预期结果** | 1. 订单加入 OrderBook<br>2. WAL 日志已写入（格式：`timestamp|COMMAND|{orderData}`）<br>3. Kafka 消息已发送（状态：PENDING）<br>4. 处理延迟 < 1ms |

| 用例编号 | TC-MATCH-002 |
|---------|-------------|
| **用例名称** | 撮合成交 - 完全成交 |
| **前置条件** | 1. OrderBook 中存在卖单（价格 50000，数量 0.01） |
| **测试步骤** | 1. 买单进入撮合引擎（价格 50000，数量 0.01）<br>2. 价格匹配（50000 == 50000）<br>3. 数量匹配（0.01 == 0.01）<br>4. 生成 TradeEvent：<br>   - tradeId（雪花算法）<br>   - makerOrderId（卖单）<br>   - takerOrderId（买单）<br>   - price: 5000000000000<br>   - quantity: 100000000<br>5. 从 OrderBook 移除双方订单<br>6. 写 WAL 日志（TradeEvent）<br>7. 发送 TradeEvent 到 `trade-event` Topic |
| **预期结果** | 1. 双方订单完全成交<br>2. TradeEvent 生成正确<br>3. WAL 日志包含成交记录<br>4. Kafka 消息已发送 |

| 用例编号 | TC-MATCH-003 |
|---------|-------------|
| **用例名称** | 撮合成交 - 部分成交 |
| **前置条件** | 1. OrderBook 中存在卖单（价格 50000，数量 0.005） |
| **测试步骤** | 1. 买单进入（价格 50000，数量 0.01）<br>2. 部分成交（0.005）<br>3. 剩余数量（0.005）加入 OrderBook |
| **预期结果** | 1. 生成 TradeEvent（数量 0.005）<br>2. 卖单从 OrderBook 移除<br>3. 买单剩余 0.005 继续挂单<br>4. 买单状态：PARTIALLY_FILLED |

| 用例编号 | TC-MATCH-004 |
|---------|-------------|
| **用例名称** | 撮合规则 - 价格优先 |
| **前置条件** | 1. OrderBook 卖单：<br>   - 卖单1：价格 50000，数量 0.01<br>   - 卖单2：价格 50010，数量 0.01 |
| **测试步骤** | 1. 买单进入（价格 50010，数量 0.01） |
| **预期结果** | 1. 与价格最低的卖单（50000）成交<br>2. 实际成交价格：50000 |

| 用例编号 | TC-MATCH-005 |
|---------|-------------|
| **用例名称** | 撮合规则 - 时间优先 |
| **前置条件** | 1. OrderBook 卖单：<br>   - 卖单1：价格 50000，时间 T1<br>   - 卖单2：价格 50000，时间 T2（T2 > T1） |
| **测试步骤** | 1. 买单进入（价格 50000，数量 0.01） |
| **预期结果** | 1. 与时间最早的卖单（T1）成交 |

| 用例编号 | TC-MATCH-006 |
|---------|-------------|
| **用例名称** | 撤单处理 |
| **前置条件** | 1. OrderBook 中存在挂单（orderId: xxx） |
| **测试步骤** | 1. 接收 CancelOrderCommand<br>2. 从 OrderBook 移除订单<br>3. 写 WAL 日志<br>4. 发送状态回传（CANCELED） |
| **预期结果** | 1. 订单从 OrderBook 移除<br>2. WAL 日志记录撤单<br>3. Kafka 状态回传已发送 |

---

## 8. 账本服务测试用例

### 8.1 双录分录记账

| 用例编号 | TC-LEDGER-001 |
|---------|--------------|
| **用例名称** | 双录分录 - 买入开仓（冒烟测试关键步骤） |
| **前置条件** | 1. Ledger Core 服务正常运行（端口 8084）<br>2. Kafka 消费者正常运行<br>3. 数据库连接正常 |
| **测试步骤** | 1. 消费 TradeEvent（`trade-event` Topic）<br>2. 事件内容：<br>```json<br>{<br>  "tradeId": "1891234567890",<br>  "buyerId": 10001,<br>  "sellerId": 10002,<br>  "symbol": "BTCUSDT",<br>  "price": 5000000000000,<br>  "quantity": 100000000,<br>  "leverage": 10<br>}<br>```<br>3. 计算买方保证金：`5000000000000 * 100000000 / 10 = 50000000000000`<br>4. 生成买方分录：<br>   - 借：持仓资产 BTC 0.01（`position:10001:BTCUSDT:LONG`）<br>   - 贷：保证金 USDT 500（`account:10001:USDT`）<br>5. 生成卖方分录（平仓场景类似）<br>6. 校验借贷平衡：`debit == credit`<br>7. 插入 `t_ledger_entry` 表<br>8. 写 WAL 日志（`data/wal/ledger.log`）<br>9. 发送账本事件到 `trade-entry-BTCUSDT` |
| **预期结果** | 1. 账本记录成功插入（每笔 Trade 至少 4 条分录：买卖双方各 2 条）<br>2. 借贷平衡校验通过<br>3. WAL 日志已写入<br>4. Kafka 账本事件已发送<br>5. 分录 bizSeq 包含 tradeId，确保幂等 |

| 用例编号 | TC-LEDGER-002 |
|---------|--------------|
| **用例名称** | 双录分录 - 卖出平仓（盈利场景） |
| **前置条件** | 1. 用户已有持仓（BTC 0.01，开仓价 50000） |
| **测试步骤** | 1. 消费 TradeEvent（平仓，价格 51000）<br>2. 计算已实现盈亏：`(51000 - 50000) * 0.01 = 10 USDT`<br>3. 生成分录：<br>   - 借：保证金 USDT 510（500 + 10）<br>   - 贷：持仓资产 BTC 0.01<br>   - 贷：已实现盈亏 USDT 10 |
| **预期结果** | 1. 分录借贷平衡<br>2. 已实现盈亏正确计算<br>3. 持仓减少为 0 |

| 用例编号 | TC-LEDGER-003 |
|---------|--------------|
| **用例名称** | 双录分录 - 卖出平仓（亏损场景） |
| **前置条件** | 1. 用户持仓（BTC 0.01，开仓价 50000） |
| **测试步骤** | 1. 平仓价格：49000<br>2. 计算已实现盈亏：`(49000 - 50000) * 0.01 = -10 USDT` |
| **预期结果** | 1. 分录借贷平衡<br>2. 已实现亏损正确计算 |

| 用例编号 | TC-LEDGER-004 |
|---------|--------------|
| **用例名称** | 分录幂等性 |
| **前置条件** | 1. 已存在 bizSeq = "trade-1891234567890" 的分录 |
| **测试步骤** | 1. 消费相同的 TradeEvent<br>2. 根据 bizSeq 查询已存在记录 |
| **预期结果** | 1. 不重复插入<br>2. 返回已存在的分录ID |

| 用例编号 | TC-LEDGER-005 |
|---------|--------------|
| **用例名称** | 借贷不平衡异常处理 |
| **前置条件** | 模拟异常情况 |
| **测试步骤** | 1. 构造 debit != credit 的分录<br>2. 执行校验 |
| **预期结果** | 1. 抛出 `LedgerUnbalancedException`<br>2. 事务回滚<br>3. 不插入任何记录<br>4. 记录错误日志并报警 |

| 用例编号 | TC-LEDGER-006 |
|---------|--------------|
| **用例名称** | 高并发场景下账本记账幂等性 |
| **前置条件** | 1. Kafka Topic `trade-event` 分区数 >= 3<br>2. Ledger Core 多实例部署（3个实例）<br>3. 数据库支持唯一约束（bizSeq 唯一索引） |
| **测试步骤** | 1. 模拟高并发场景，同时发送100个不同的 TradeEvent<br>2. 多个 Ledger 实例并发消费<br>3. 每个实例根据 bizSeq 检查是否已记账<br>4. 数据库唯一约束防止重复插入<br>5. 统计最终分录数量 |
| **预期结果** | 1. 每个 Trade 只记账一次<br>2. 无重复分录（bizSeq 唯一）<br>3. 所有分录借贷平衡<br>4. 分录总数 = TradeEvent 数量 * 4（买卖双方各2条）<br>5. 无死锁或并发冲突异常 |

---

## 9. 持仓快照服务测试用例

### 9.1 持仓更新

| 用例编号 | TC-POSITION-001 |
|---------|----------------|
| **用例名称** | 持仓更新 - 开仓（冒烟测试关键步骤） |
| **前置条件** | 1. Position Snapshot Core 服务正常运行（端口 8086）<br>2. Kafka 消费者正常运行<br>3. 用户当前无 BTCUSDT 持仓 |
| **测试步骤** | 1. 消费账本事件（`trade-entry-BTCUSDT`）<br>2. 事件内容：买入开仓，数量 0.01，价格 50000<br>3. 查询当前持仓（MySQL `t_position`）<br>4. 无持仓，创建新记录：<br>   - `symbol`: BTCUSDT<br>   - `side`: LONG<br>   - `quantity`: 0.01<br>   - `entryPrice`: 50000<br>5. 更新 MySQL<br>6. 更新 Redis（`position:10001:BTCUSDT`） |
| **预期结果** | 1. MySQL `t_position` 新增持仓记录<br>2. Redis 缓存已更新<br>3. 持仓方向：LONG<br>4. 持仓数量：0.01<br>5. 开仓均价：50000 |

| 用例编号 | TC-POSITION-002 |
|---------|----------------|
| **用例名称** | 持仓更新 - 加仓 |
| **前置条件** | 1. 用户已有持仓（LONG，0.01，开仓价 50000） |
| **测试步骤** | 1. 再次买入 0.01，价格 51000<br>2. 计算新的开仓均价：`(50000*0.01 + 51000*0.01) / 0.02 = 50500` |
| **预期结果** | 1. 持仓数量：0.02<br>2. 开仓均价更新为：50500 |

| 用例编号 | TC-POSITION-003 |
|---------|----------------|
| **用例名称** | 持仓更新 - 平仓 |
| **前置条件** | 1. 用户持仓（LONG，0.02，开仓价 50500） |
| **测试步骤** | 1. 卖出平仓 0.01，价格 51000<br>2. 持仓减少为 0.01 |
| **预期结果** | 1. 持仓数量：0.01<br>2. 开仓均价保持 50500 |

| 用例编号 | TC-POSITION-004 |
|---------|----------------|
| **用例名称** | 持仓更新 - 完全平仓 |
| **前置条件** | 1. 用户持仓（LONG，0.01） |
| **测试步骤** | 1. 卖出平仓 0.01 |
| **预期结果** | 1. 持仓记录删除（或标记为已平仓）<br>2. Redis 缓存清除 |

---

### 9.2 浮盈浮亏计算

| 用例编号 | TC-POSITION-005 |
|---------|----------------|
| **用例名称** | 浮盈浮亏计算 |
| **前置条件** | 1. 用户持仓（LONG，0.01，开仓价 50000）<br>2. 标记价格：51000 |
| **测试步骤** | 1. 定时任务计算浮盈浮亏<br>2. 公式：`(标记价格 - 开仓均价) * 持仓数量` |
| **预期结果** | 1. 浮盈：`(51000 - 50000) * 0.01 = 10 USDT`<br>2. 更新 `unrealizedPnl` 字段 |

---

## 10. 行情服务测试用例

### 10.1 行情计算

| 用例编号 | TC-MARKET-001 |
|---------|--------------|
| **用例名称** | 行情计算 - 消费成交事件（冒烟测试关键步骤） |
| **前置条件** | 1. Market Price Core 服务正常运行（端口 8095）<br>2. Kafka 消费者正常运行 |
| **测试步骤** | 1. 消费 TradeEvent（`trade-event` Topic）<br>2. 更新最新成交价（Last Price）<br>3. 更新 24h Ticker：<br>   - 成交量累加<br>   - 成交额累加<br>   - 最高价/最低价更新<br>4. 从 Match Engine 获取 OrderBook 快照<br>5. 计算买卖盘深度<br>6. 更新 K 线数据（1m, 5m, 15m, 1h, 4h, 1d 等）<br>7. 发送行情事件到 `market-events` Topic |
| **预期结果** | 1. 最新价更新<br>2. Ticker 统计正确<br>3. OrderBook 深度数据正确<br>4. K 线 OHLCV 数据正确<br>5. Kafka 行情事件已发送 |

| 用例编号 | TC-MARKET-002 |
|---------|--------------|
| **用例名称** | K 线生成 |
| **前置条件** | 1. 当前时间：14:23:45<br>2. 1分钟 K 线周期 |
| **测试步骤** | 1. 成交价格：50000<br>2. 更新当前 1m K 线（14:23:00） |
| **预期结果** | 1. 如果是该 K 线第一笔成交，设为开盘价<br>2. 最高价/最低价更新<br>3. 收盘价更新为最新成交价<br>4. 成交量累加 |

| 用例编号 | TC-MARKET-003 |
|---------|--------------|
| **用例名称** | OrderBook 深度计算 |
| **前置条件** | Match Engine OrderBook 有数据 |
| **测试步骤** | 1. 从 Match Engine 获取买单队列（前 20 档）<br>2. 获取卖单队列（前 20 档）<br>3. 计算每个价格档位的累计数量 |
| **预期结果** | 1. 返回 bids（买单）和 asks（卖单）数组<br>2. 每个档位包含价格和数量<br>3. 按价格排序（买单降序，卖单升序） |

---

## 11. 公有推送服务测试用例

### 11.1 WebSocket 连接

| 用例编号 | TC-PUBLIC-001 |
|---------|--------------|
| **用例名称** | WebSocket 连接建立 |
| **前置条件** | 1. Public Push Core 服务正常运行（端口 8096）<br>2. WebSocket 端口开放 |
| **测试步骤** | 1. 客户端发起 WebSocket 连接：`ws://localhost:8096/ws/market`<br>2. 服务端接受连接<br>3. 创建 Session 对象<br>4. 分配 connectionId |
| **预期结果** | 1. WebSocket 连接成功建立<br>2. 返回连接确认消息<br>3. Session 已创建并管理 |

| 用例编号 | TC-PUBLIC-002 |
|---------|--------------|
| **用例名称** | WebSocket 断线重连 |
| **前置条件** | 1. WebSocket 连接已建立<br>2. 客户端已订阅频道 |
| **测试步骤** | 1. 模拟网络断线<br>2. 客户端检测心跳超时<br>3. 客户端发起重连<br>4. 重新订阅之前的频道 |
| **预期结果** | 1. 断线检测正常<br>2. 重连成功<br>3. 订阅状态恢复<br>4. 继续接收推送 |

---

### 11.2 订阅管理

| 用例编号 | TC-PUBLIC-003 |
|---------|--------------|
| **用例名称** | 订阅深度行情（冒烟测试关键步骤） |
| **前置条件** | 1. WebSocket 连接已建立 |
| **测试步骤** | 1. 客户端发送订阅消息：<br>```json<br>{<br>  "method": "SUBSCRIBE",<br>  "params": ["depth.BTCUSDT"],<br>  "id": 1<br>}<br>```<br>2. 服务端验证订阅参数<br>3. 检查订阅数量（最大 1024/连接）<br>4. 添加订阅到 Session<br>5. 立即发送深度快照（从 Redis 获取）<br>6. 开始推送增量更新 |
| **预期结果** | 1. 返回订阅确认：`{"result": null, "id": 1}`<br>2. 立即收到深度快照（全量）<br>3. 后续收到增量更新<br>4. 快照包含 bids 和 asks |

| 用例编号 | TC-PUBLIC-004 |
|---------|--------------|
| **用例名称** | 订阅 K 线 |
| **前置条件** | WebSocket 连接已建立 |
| **测试步骤** | 1. 订阅 `kline.BTCUSDT.1m`<br>2. 服务端获取历史 K 线<br>3. 推送实时更新 |
| **预期结果** | 1. 收到历史 K 线数据<br>2. 收到实时 K 线更新 |

| 用例编号 | TC-PUBLIC-005 |
|---------|--------------|
| **用例名称** | 订阅成交 |
| **前置条件** | WebSocket 连接已建立 |
| **测试步骤** | 1. 订阅 `trade.BTCUSDT` |
| **预期结果** | 1. 每笔成交实时推送<br>2. 包含价格、数量、时间、买卖方向 |

| 用例编号 | TC-PUBLIC-006 |
|---------|--------------|
| **用例名称** | 订阅数量限制 |
| **前置条件** | 1. 已订阅 1024 个频道 |
| **测试步骤** | 1. 尝试订阅第 1025 个频道 |
| **预期结果** | 1. 订阅被拒绝<br>2. 返回错误：`SUBSCRIPTION_LIMIT_EXCEEDED` |

---

### 11.3 行情推送

| 用例编号 | TC-PUBLIC-007 |
|---------|--------------|
| **用例名称** | 行情推送 - 深度更新（冒烟测试关键步骤） |
| **前置条件** | 1. 客户端已订阅 `depth.BTCUSDT`<br>2. Market Price Core 发送行情事件 |
| **测试步骤** | 1. Public Push Core 消费 `market-events`<br>2. 解析深度更新事件<br>3. 查找所有订阅了 `depth.BTCUSDT` 的 Session<br>4. 批量聚合消息（10-50ms 窗口）<br>5. 应用限流（Token Bucket）<br>6. 通过 WebSocket 推送<br>7. 客户端接收并校验序号连续性 |
| **预期结果** | 1. 行情事件成功消费<br>2. 消息批量发送（减少网络开销）<br>3. 客户端收到深度更新<br>4. 推送延迟 < 100ms<br>5. 序号连续无 Gap |

| 用例编号 | TC-PUBLIC-008 |
|---------|--------------|
| **用例名称** | 行情推送 - 限流保护 |
| **前置条件** | 1. 限流配置：1000 消息/秒/连接 |
| **测试步骤** | 1. 行情更新频率过高<br>2. 触发限流 |
| **预期结果** | 1. 限流器正常工作<br>2. 丢弃非关键消息或合并发送<br>3. 系统稳定 |

---

## 12. 私有推送服务测试用例

### 12.1 私有事件推送

| 用例编号 | TC-PRIVATE-001 |
|---------|---------------|
| **用例名称** | 私有推送 - 订单状态更新（冒烟测试关键步骤） |
| **前置条件** | 1. Private Push Core 服务正常运行<br>2. 用户已建立 WebSocket 连接<br>3. 用户已认证（JWT Token） |
| **测试步骤** | 1. OMS 发送订单状态变更事件<br>2. Private Push Core 消费事件<br>3. 根据 userId 查找用户的 WebSocket Session<br>4. 推送订单更新消息：<br>```json<br>{<br>  "event": "ORDER_UPDATE",<br>  "data": {<br>    "orderId": "1891234567890",<br>    "clientOrderId": "test-order-001",<br>    "symbol": "BTCUSDT",<br>    "status": "FILLED",<br>    "filledQty": "100000000",<br>    "avgPrice": "5000000000000"
  }<br>}<br>``` |
| **预期结果** | 1. 订单状态消息成功推送<br>2. 客户端收到订单更新<br>3. 推送延迟 < 50ms |

| 用例编号 | TC-PRIVATE-002 |
|---------|---------------|
| **用例名称** | 私有推送 - 账户余额更新 |
| **前置条件** | 1. 用户已认证连接 |
| **测试步骤** | 1. 账户余额变更<br>2. Snapshot 服务发送账户事件<br>3. Private Push Core 消费并推送 |
| **预期结果** | 1. 客户端收到余额更新<br>2. 包含可用余额、冻结余额 |

| 用例编号 | TC-PRIVATE-003 |
|---------|---------------|
| **用例名称** | 私有推送 - 持仓更新 |
| **前置条件** | 1. 用户已认证连接 |
| **测试步骤** | 1. 持仓变更（开仓/平仓）<br>2. 推送持仓更新 |
| **预期结果** | 1. 客户端收到持仓更新<br>2. 包含持仓数量、开仓均价、浮盈浮亏 |

---

## 13. 完整链路集成测试用例

### 13.1 冒烟测试主链路

| 用例编号 | TC-E2E-001 |
|---------|-----------|
| **用例名称** | **冒烟测试 - 完整交易链路闭环** |
| **前置条件** | 1. **所有服务正常运行**：<br>   - API Gateway (8080)<br>   - User Core<br>   - OMS Core (8081)<br>   - Hard Risk Core (8082)<br>   - Match Engine Core (8083)<br>   - Ledger Core (8084)<br>   - Snapshot Account Core (8085)<br>   - Position Snapshot Core (8086)<br>   - Market Price Core (8095)<br>   - Public Push Core (8096)<br>   - Private Push Core<br>2. **基础设施正常运行**：<br>   - MySQL<br>   - Redis<br>   - Kafka (所有 Topic 已创建)<br>3. **测试用户准备**：<br>   - 用户A（买方）：已注册，初始资金 10000 USDT<br>   - 用户B（卖方）：已注册，初始资金 10000 USDT，持仓 BTC 0.01 |
| **测试步骤** | **阶段1：用户准备（注册 → 登录 → 初始化资金）**<br>1. 用户A注册 → 获得 userId=10001<br>2. 用户A登录 → 获得 JWT Token<br>3. 用户A资金充值 → USDT 10000（调用 Ledger 充值接口）<br>4. 查询用户A余额 → 验证可用 10000，冻结 0<br>5. 用户B注册 → 获得 userId=10002<br>6. 用户B登录 → 获得 JWT Token<br>7. 用户B资金充值 → USDT 10000<br>8. 用户B开仓（买入 0.01 BTC @ 50000，杠杆10x）→ 作为测试对手盘准备<br>9. 查询用户B余额和持仓 → 验证 USDT 可用余额、BTC 持仓 0.01<br><br>**阶段2：下单（用户A 买入开仓）**<br>10. 用户A通过 API Gateway 发送下单请求：<br>   - Symbol: BTCUSDT<br>   - Side: BUY<br>   - Type: LIMIT<br>   - Price: 50000 USDT<br>   - Quantity: 0.01 BTC<br>   - Leverage: 10x<br>11. Gateway JWT 认证 → 设置 X-User-Id: 10001<br>12. OMS 参数校验 → 计算保证金 500 USDT<br>13. OMS 调用 Hard Risk Core → 验证余额充足<br>14. OMS 发送 Kafka → `order-event-BTCUSDT`<br>15. OMS 返回订单ID，状态 PENDING<br><br>**阶段3：撮合（用户B 卖出平仓）**<br>16. 用户B发送卖单（卖出 0.01 BTC，价格 50000）<br>17. 流程同上，通过风控检查<br>18. Match Engine 消费两个 OrderCommand<br>19. 撮合成交（价格匹配、数量匹配）<br>20. 生成 TradeEvent<br>21. 发送 TradeEvent 到 `trade-event`<br>22. 发送订单状态回传到 `order-state-BTCUSDT`<br><br>**阶段4：账本记账**<br>23. Ledger Core 消费 TradeEvent<br>24. 生成买方分录（借持仓 BTC，贷保证金 USDT）<br>25. 生成卖方分录（借保证金 USDT，贷持仓 BTC）<br>26. 校验借贷平衡<br>27. 写入 `t_ledger_entry`<br>28. 发送 `trade-entry-BTCUSDT`<br><br>**阶段5：快照更新**<br>29. Snapshot Account Core 消费账本事件<br>30. 更新用户A余额：可用 9500，冻结 0，持仓价值 500<br>31. 更新用户B余额：可用 10500，BTC 持仓 0<br>32. Position Snapshot Core 更新持仓：<br>    - 用户A：BTCUSDT LONG，数量 0.01，开仓价 50000<br>    - 用户B：BTCUSDT 持仓清零<br>33. 更新 Redis 缓存<br><br>**阶段6：公有推送**<br>34. Market Price Core 消费 TradeEvent<br>35. 更新最新价、Ticker、K线、OrderBook<br>36. 发送 `market-events`<br>37. Public Push Core 消费行情事件<br>38. 推送深度更新给订阅客户端<br>39. 推送成交数据<br>40. 推送 K 线更新<br><br>**阶段7：私有推送**<br>41. Private Push Core 消费订单事件<br>42. 推送用户A：订单状态 FILLED<br>43. 推送用户A：账户余额更新（可用 9500）<br>44. 推送用户A：持仓更新（BTC 0.01）<br>45. 推送用户B：订单状态 FILLED<br>46. 推送用户B：账户余额更新（可用 10500）<br>47. 推送用户B：持仓更新（BTC 0） |
| **预期结果** | **数据一致性验证**：<br>1. ✅ 用户A订单状态：FILLED<br>2. ✅ 用户B订单状态：FILLED<br>3. ✅ 账本记录：借贷平衡（查询 `t_ledger_entry`）<br>4. ✅ 用户A余额：可用 9500 USDT，冻结 0（Redis + MySQL 一致）<br>5. ✅ 用户A持仓：BTC 0.01，开仓价 50000<br>6. ✅ 用户B余额：可用 10500 USDT<br>7. ✅ 用户B持仓：BTC 0<br>8. ✅ **MySQL 与 Redis 数据一致性**：<br>   - 余额：`t_account_balance.available` == Redis `account:10001:USDT`<br>   - 持仓：`t_position.quantity` == Redis `position:10001:BTCUSDT`<br>9. ✅ **账本借贷平衡**：<br>   - `SELECT SUM(debit) - SUM(credit) FROM t_ledger_entry WHERE biz_seq LIKE 'trade-%' = 0`<br><br>**消息流转验证**：<br>10. ✅ Kafka 所有 Topic 消息正常流转<br>11. ✅ WAL 日志完整（match.log、ledger.log）<br>12. ✅ 公有推送：WebSocket 客户端收到深度/成交/K线更新<br>13. ✅ 私有推送：用户A/B 收到订单、余额、持仓更新<br><br>**性能验证（P99 指标）**：<br>14. ✅ 下单延迟 P99 < 10ms（Gateway → OMS → 响应）<br>15. ✅ 风控延迟 P99 < 3ms（Hard Risk Core 检查耗时）<br>16. ✅ 撮合延迟 P99 < 1ms（单笔订单在 Match Engine 中的处理时间）<br>17. ✅ 账本记账延迟 P99 < 5ms（Ledger 写入 MySQL 耗时）<br>18. ✅ 端到端延迟 P99 < 100ms（下单 → 收到私有推送）<br>19. ✅ WebSocket 推送延迟 P99 < 50ms（行情事件 → 客户端接收）<br>20. ✅ Kafka 消息积压 < 1000条/Topic（无堆积） |

---

### 13.2 其他场景链路

| 用例编号 | TC-E2E-002 |
|---------|-----------|
| **用例名称** | 部分成交链路 |
| **前置条件** | 1. 所有服务正常运行<br>2. 用户A 余额充足 |
| **测试步骤** | 1. 用户A 下单：买入 0.01 BTC @ 50000<br>2. 用户B 下单：卖出 0.005 BTC @ 50000<br>3. 撮合部分成交<br>4. 验证部分成交后的账本、快照、推送 |
| **预期结果** | 1. 用户A 订单状态：PARTIALLY_FILLED<br>2. 成交数量：0.005 BTC<br>3. 剩余订单：0.005 BTC 继续挂单<br>4. 账本只记录实际成交部分<br>5. 快照数据与成交一致 |

| 用例编号 | TC-E2E-003 |
|---------|-----------|
| **用例名称** | 撤单链路 |
| **前置条件** | 1. 订单已创建（状态：PENDING） |
| **测试步骤** | 1. 用户发送撤单请求<br>2. OMS 发送撤单命令到 Kafka<br>3. Match Engine 处理撤单<br>4. 订单从 OrderBook 移除<br>5. 保证金解冻<br>6. 更新账户快照<br>7. 推送订单状态更新 |
| **预期结果** | 1. 订单状态：CANCELED<br>2. 冻结保证金已解冻<br>3. 账户快照已更新<br>4. 客户端收到撤单确认 |

| 用例编号 | TC-E2E-004 |
|---------|-----------|
| **用例名称** | 风控拒绝链路 |
| **前置条件** | 1. 用户余额：100 USDT |
| **测试步骤** | 1. 用户下单：需要保证金 500 USDT<br>2. OMS 调用 Hard Risk Core<br>3. 风控检查余额不足<br>4. 返回拒绝<br>5. OMS 拒绝订单<br>6. 推送拒绝通知（如有） |
| **预期结果** | 1. 订单状态：REJECTED<br>2. 错误信息："可用余额不足"<br>3. 不发送 Kafka 消息到撮合引擎<br>4. 账户余额不变 |

| 用例编号 | TC-E2E-005 |
|---------|-----------|
| **用例名称** | WebSocket 断线重连链路 |
| **前置条件** | 1. 客户端已建立 WebSocket 连接<br>2. 已订阅 depth.BTCUSDT |
| **测试步骤** | 1. 模拟网络断线<br>2. 客户端检测心跳超时<br>3. 客户端自动重连<br>4. 重新订阅之前的频道<br>5. 服务端发送快照+增量 |
| **预期结果** | 1. 断线检测正常（心跳超时）<br>2. 重连成功<br>3. 订阅状态恢复<br>4. 收到最新快照<br>5. 后续增量更新正常 |

| 用例编号 | TC-E2E-006 |
|---------|-----------|
| **用例名称** | 市价单成交链路 |
| **前置条件** | 1. OrderBook 有对手盘<br>2. 用户余额充足 |
| **测试步骤** | 1. 用户发送市价单（买入 0.01 BTC）<br>2. OMS 处理（不指定价格）<br>3. 风控检查<br>4. 发送到撮合引擎<br>5. 按市场最优价格成交 |
| **预期结果** | 1. 订单立即成交<br>2. 成交价格 = OrderBook 最优卖价<br>3. 账本、快照、推送正常更新 |

| 用例编号 | TC-E2E-007 |
|---------|-----------|
| **用例名称** | Kafka 消息幂等性验证 |
| **前置条件** | 1. Ledger Core 服务正常运行<br>2. Kafka 消费者正常运行<br>3. 数据库 bizSeq 唯一约束已配置 |
| **测试步骤** | 1. Match Engine 生成 TradeEvent（tradeId: 1891234567890）<br>2. 发送到 `trade-event` Topic<br>3. Ledger Core 消费并记账（bizSeq: trade-1891234567890）<br>4. 模拟 Kafka 重复投递，再次发送相同的 TradeEvent<br>5. Ledger Core 再次消费<br>6. 根据 bizSeq 检查数据库是否已存在<br>7. 发现重复，跳过记账 |
| **预期结果** | 1. 第一次消费：成功插入分录（4条：买卖双方各2条）<br>2. 第二次消费：检测到重复，不插入任何记录<br>3. 日志记录：`[Ledger] Duplicate trade ignored: tradeId=1891234567890`<br>4. 最终分录数量 = 4（无重复）<br>5. 账本借贷平衡 |

| 用例编号 | TC-E2E-008 |
|---------|-----------|
| **用例名称** | Match Engine WAL 日志恢复 |
| **前置条件** | 1. Match Engine 正常运行<br>2. OrderBook 中有挂单：<br>   - 买单：价格 49900，数量 0.01<br>   - 卖单：价格 50100，数量 0.01<br>3. WAL 日志文件完整（`data/wal/match.log`） |
| **测试步骤** | 1. 记录当前 OrderBook 状态（买单1笔，卖单1笔）<br>2. 模拟 Match Engine 进程崩溃（kill -9）<br>3. 重启 Match Engine<br>4. 从 WAL 日志读取历史事件<br>5. 按时间顺序重放 OrderCommand 和 TradeEvent<br>6. 重建 OrderBook 内存状态<br>7. 验证 OrderBook 数据 |
| **预期结果** | 1. Match Engine 重启成功<br>2. WAL 日志读取完整（无损坏）<br>3. OrderBook 恢复到崩溃前状态：<br>   - 买单：价格 49900，数量 0.01<br>   - 卖单：价格 50100，数量 0.01<br>4. 日志记录：`[MatchEngine] WAL recovery completed, restored 2 orders`<br>5. 后续订单继续正常撮合<br>6. 无数据丢失 |

---

## 14. 测试用例统计

| 模块 | 用例数量 | 关键用例 |
|------|----------|----------|
| 用户服务 (TC-USER) | 6 | TC-USER-001 注册, TC-USER-004 登录 |
| API Gateway (TC-GATEWAY) | 6 | TC-GATEWAY-001 JWT认证, TC-GATEWAY-006 限流恢复 |
| 资金服务 (TC-FUND) | 2 | TC-FUND-001 资金充值 |
| 账户快照 (TC-ACCOUNT) | 4 | TC-ACCOUNT-001 查询余额, TC-ACCOUNT-003 冻结资金 |
| OMS (TC-OMS) | 10 | TC-OMS-001 限价单下单, TC-OMS-010 幂等键过期重试 |
| 硬风控 (TC-RISK) | 5 | TC-RISK-001 余额充足检查 |
| 撮合引擎 (TC-MATCH) | 6 | TC-MATCH-001 挂单, TC-MATCH-002 撮合成交 |
| 账本服务 (TC-LEDGER) | 6 | TC-LEDGER-001 双录分录记账, TC-LEDGER-006 高并发幂等性 |
| 持仓快照 (TC-POSITION) | 5 | TC-POSITION-001 开仓更新, TC-POSITION-005 浮盈计算 |
| 行情服务 (TC-MARKET) | 3 | TC-MARKET-001 行情计算 |
| 公有推送 (TC-PUBLIC) | 8 | TC-PUBLIC-003 订阅深度, TC-PUBLIC-007 行情推送 |
| 私有推送 (TC-PRIVATE) | 3 | TC-PRIVATE-001 订单状态推送 |
| 完整链路 (TC-E2E) | 8 | TC-E2E-001 冒烟测试主链路, TC-E2E-007 Kafka幂等性, TC-E2E-008 WAL恢复 |
| **总计** | **72** | - |

---

## 15. 冒烟测试执行清单

执行冒烟测试时，按以下顺序执行关键用例：

### Phase 1: 基础服务检查（10分钟）
- [ ] TC-USER-001: 用户注册
- [ ] TC-USER-004: 用户登录
- [ ] TC-GATEWAY-001: JWT 认证

### Phase 2: 资金初始化（5分钟）
- [ ] TC-FUND-001: 资金充值（USDT）
- [ ] TC-ACCOUNT-001: 查询余额

### Phase 3: 交易核心链路（15分钟）
- [ ] TC-RISK-001: 风控检查通过
- [ ] TC-OMS-001: 限价单下单
- [ ] TC-MATCH-001: 订单挂单
- [ ] TC-MATCH-002: 撮合成交
- [ ] TC-LEDGER-001: 双录分录记账

### Phase 4: 数据一致性验证（15分钟）
- [ ] TC-ACCOUNT-003: 查询冻结资金
- [ ] TC-POSITION-001: 查询持仓
- [ ] TC-POSITION-005: 浮盈浮亏计算
- [ ] **[新增] MySQL 与 Redis 数据一致性校验**
  - 余额一致性：`SELECT * FROM t_account_balance WHERE user_id=10001` 与 Redis `GET account:10001:USDT` 对比
  - 持仓一致性：`SELECT * FROM t_position WHERE user_id=10001` 与 Redis `GET position:10001:BTCUSDT` 对比
- [ ] **[新增] 账本借贷平衡校验**
  - 执行 SQL：`SELECT SUM(debit) - SUM(credit) AS balance FROM t_ledger_entry`
  - 预期结果：`balance = 0`（借贷完全平衡）

### Phase 5: 推送服务验证（10分钟）
- [ ] TC-PUBLIC-001: WebSocket 连接
- [ ] TC-PUBLIC-003: 订阅深度
- [ ] TC-PUBLIC-007: 深度更新推送
- [ ] TC-PRIVATE-001: 订单状态推送

### Phase 6: 完整链路（20分钟）
- [ ] TC-E2E-001: 完整交易链路闭环（端到端）

---

## 16. 附录

### A. 测试数据规范

| 字段 | 示例值 | 说明 |
|------|--------|------|
| userId | 1891234567890123456 | 雪花算法生成的 Long 型ID |
| symbol | BTCUSDT | 交易对，大写，基础货币+计价货币 |
| price | 5000000000000 | 价格，8位小数定点数（50000.00000000） |
| quantity | 100000000 | 数量，8位小数定点数（1.00000000 = 1 BTC） |
| amount | 500000000000000 | 金额，8位小数定点数 |

### B. 服务端口速查

| 服务 | 端口 | 测试关注点 |
|------|------|-----------|
| API Gateway | 8080 | JWT、限流、路由 |
| OMS Core | 8081 | 下单、撤单、订单查询 |
| Hard Risk Core | 8082 | 风控检查延迟 < 3ms |
| Match Engine Core | 8083 | 撮合延迟 < 1ms |
| Ledger Core | 8084 | 双录分录、借贷平衡 |
| Snapshot Account Core | 8085 | 余额查询、缓存一致性 |
| Position Snapshot Core | 8086 | 持仓查询、浮盈计算 |
| Market Price Core | 8095 | 行情计算、K线生成 |
| Public Push Core | 8096 | WebSocket、行情推送 |

### C. Kafka Topic 速查

| Topic | 用途 | 生产者 | 消费者 |
|-------|------|--------|--------|
| order-event-{symbol} | 订单命令 | OMS | Match Engine |
| order-state-{symbol} | 订单状态回传 | Match Engine | OMS, Private Push |
| trade-event | 成交事件 | Match Engine | Ledger, Market Price, Private Push |
| trade-entry-{symbol} | 账本分录 | Ledger | Snapshot Account, Snapshot Position, Private Push |
| market-events | 行情事件 | Market Price | Public Push |
| account-change-topic | 账户变更 | Ledger | Snapshot Account, Private Push |

---

**文档状态**: ✅ Review 完成并优化，可用于冒烟测试执行

**最后更新**: 2026-02-20（新增5个测试用例，增强性能指标和数据一致性验证）

**变更记录（v1.1）**:
- ✅ 新增 TC-GATEWAY-006：限流拒绝后恢复测试
- ✅ 新增 TC-OMS-010：幂等键过期后重试
- ✅ 新增 TC-LEDGER-006：高并发场景下账本记账幂等性
- ✅ 新增 TC-E2E-007：Kafka 消息幂等性验证
- ✅ 新增 TC-E2E-008：Match Engine WAL 日志恢复
- ✅ 优化 TC-E2E-001 步骤7-9：用户B通过正常开仓流程获得BTC持仓
- ✅ 增强性能验证指标：新增 P99 延迟指标（下单、风控、撮合、账本、端到端、推送）
- ✅ 增强数据一致性验证：MySQL 与 Redis 一致性校验、账本借贷平衡校验
- ✅ 更新测试用例统计：67 → 72
