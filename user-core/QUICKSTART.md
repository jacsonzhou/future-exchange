# User Core 快速开始指南

## 🚀 5分钟启动服务

### Step 1: 创建数据库 (30秒)

```bash
cd /Users/zhoufan/project/future-exchange
mysql -u root -p < sql/10_user_schema.sql
```

### Step 2: 确保依赖服务启动 (ledger-core)

```bash
# 在新的终端窗口启动 ledger-core
cd /Users/zhoufan/project/future-exchange/ledger-core
mvn spring-boot:run
```

### Step 3: 启动 user-core (1分钟)

```bash
cd /Users/zhoufan/project/future-exchange/user-core
mvn spring-boot:run
```

服务启动成功后，会显示：
```
╔════════════════════════════════════════════════╗
║     User Core Service Started Successfully     ║
║              Port: 8099                        ║
╚════════════════════════════════════════════════╝
```

### Step 4: 测试 API (2分钟)

```bash
# 运行测试脚本
./test-api.sh
```

或手动测试：

```bash
# 1. 注册用户
 curl -X POST http://localhost:8099/api/v1/user/register \
   -H "Content-Type: application/json" \
   -d '{
     "username": "trader001",
     "password": "123456"
   }'

# 2. 用户登录
 curl -X POST http://localhost:8099/api/v1/user/login \
   -H "Content-Type: application/json" \
   -d '{
     "username": "trader001",
     "password": "123456"
   }'

# 3. 保存返回的 token，然后查询用户信息
 curl -H "Authorization: Bearer YOUR_TOKEN" \
   http://localhost:8099/api/v1/user/me
```

---

## 📊 完整交易流程演示

### 场景：用户注册 → 查看行情 → 下单 → 查看持仓

```bash
# ==================== 1. 用户注册 ====================
REGISTER_RESULT=$(curl -s -X POST http://localhost:8099/api/v1/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo_'$(date +%s)'","password":"123456"}')

echo "注册成功: $REGISTER_RESULT"

# ==================== 2. 用户登录获取Token ====================
LOGIN_RESULT=$(curl -s -X POST http://localhost:8099/api/v1/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"'$(echo $REGISTER_RESULT | jq -r .data.username)'","password":"123456"}')

TOKEN=$(echo $LOGIN_RESULT | jq -r .data.token)
echo "登录成功，Token: $TOKEN"

# ==================== 3. 获取交易面板数据 ====================
DASHBOARD=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8099/api/v1/trading/dashboard?symbol=BTCUSDT")

echo "交易面板数据:"
echo $DASHBOARD | jq '{
  "资金余额": .data.balance,
  "当前持仓": .data.positions,
  "当前订单": .data.openOrders,
  "盘口深度": .data.orderBook | {bids: .bids[0:3], asks: .asks[0:3]},
  "24小时统计": .data.ticker24h
}'

# ==================== 4. 下单（通过api-gateway） ====================
ORDER_RESULT=$(curl -s -X POST http://localhost:8080/api/order/create \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "symbol": "BTCUSDT",
    "side": "BUY",
    "type": "LIMIT",
    "price": "40000",
    "quantity": "0.01",
    "leverage": 10
  }')

echo "下单结果: $ORDER_RESULT"

# ==================== 5. 查看订单和持仓 ====================
sleep 2
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8099/api/v1/trading/orders/open?symbol=BTCUSDT" | jq

curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8099/api/v1/trading/positions?symbol=BTCUSDT" | jq
```

---

## 🔧 配置修改

### 修改初始资金金额

编辑 `src/main/resources/application.yml`:

```yaml
initial:
  funding:
    enabled: true
    amount: 500000    # 修改为 50万 USDT
    asset: USDT
```

### 修改JWT过期时间

```yaml
jwt:
  secret: your-secret-key
  expiration: 604800000   # 修改为 7天（毫秒）
```

---

## 🔗 依赖的其他服务

user-core 需要以下服务配合才能完整运行：

| 服务 | 端口 | 用途 |
|-----|------|------|
| ledger-core | 8084 | 创建初始资金分录 |
| snapshot-account-core | 8085 | 查询余额 |
| position-snapshot-core | 8086 | 查询持仓 |
| oms-core | 8081 | 查询订单 |
| market-price-core | 8095 | 查询行情 |

如果只启动 user-core，可以测试注册/登录，但交易面板数据会缺失。

---

## 🐛 常见问题

### 1. 数据库连接失败

```
Failed to obtain JDBC Connection
```

**解决**: 检查 MySQL 是否启动，以及 `application.yml` 中的数据库配置

```bash
# 检查 MySQL
mysql -u root -p -e "SELECT 1"

# 创建数据库
mysql -u root -p < sql/10_user_schema.sql
```

### 2. ledger-core 连接失败

```
FeignException: Connection refused
```

**解决**: 启动 ledger-core 服务

```bash
cd ../ledger-core && mvn spring-boot:run
```

### 3. 端口冲突

```
Port 8099 was already in use
```

**解决**: 修改 `application.yml` 中的端口，或杀掉占用进程

```bash
# 查找占用进程
lsof -i :8099

# 杀掉进程
kill -9 <PID>
```

---

## 📚 更多文档

- [完整 API 文档](./README.md)
- [数据库表结构](../sql/10_user_schema.sql)

---

*快速开始指南 - v1.0*
