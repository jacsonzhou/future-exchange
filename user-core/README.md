# User Core Service - 用户服务

> **端口**: 8099  
> **数据库**: exchange_user  
> **核心职责**: 用户注册/登录 + 自动初始化资金

---

## 🚀 快速开始

### 1. 创建数据库

```bash
mysql -u root -p < sql/10_user_schema.sql
```

### 2. 配置环境变量（可选）

```bash
export MYSQL_HOST=localhost
export MYSQL_PORT=3306
export MYSQL_USER=root
export MYSQL_PASSWORD=123456
export REDIS_HOST=localhost
export REDIS_PORT=6379
export NACOS_SERVER=localhost:8848
```

### 3. 启动服务

```bash
# 方式1: 直接启动
cd user-core
mvn spring-boot:run

# 方式2: 打包后启动
mvn clean package -DskipTests
java -jar target/user-core-1.0.0-SNAPSHOT.jar
```

---

## 📚 API 接口文档

### 1. 用户注册

```http
POST /api/v1/user/register
Content-Type: application/json

{
    "username": "trader001",
    "password": "123456",
    "email": "trader@example.com",      // 可选
    "phone": "13800138000"              // 可选
}
```

**响应**:
```json
{
    "code": 200,
    "message": "success",
    "data": {
        "userId": 10001,
        "username": "trader001",
        "email": "trader@example.com",
        "phone": "13800138000",
        "status": 1,
        "userType": "RETAIL",
        "createdAt": "2024-01-15T10:30:00",
        "accountId": 20001                 // 自动创建的交易账户
    }
}
```

**说明**: 注册成功后自动创建交易账户并注入 **100,000 USDT** 初始资金

---

### 2. 用户登录

```http
POST /api/v1/user/login
Content-Type: application/json

{
    "username": "trader001",
    "password": "123456"
}
```

**响应**:
```json
{
    "code": 200,
    "message": "success",
    "data": {
        "userId": 10001,
        "username": "trader001",
        "email": "trader@example.com",
        "status": 1,
        "userType": "RETAIL",
        "lastLoginTime": "2024-01-15T10:35:00",
        "accountId": 20001,
        "token": "eyJhbGciOiJIUzI1NiIs..."   // JWT Token
    }
}
```

---

### 3. 获取当前用户信息

```http
GET /api/v1/user/me
Authorization: Bearer {token}
```

---

### 4. 获取交易账户信息

```http
GET /api/v1/user/account
Authorization: Bearer {token}
```

---

## 📊 交易界面数据接口

### 获取完整交易面板数据

```http
GET /api/v1/trading/dashboard?symbol=BTCUSDT
Authorization: Bearer {token}
```

**响应**（用户进入交易界面所需的所有数据）:
```json
{
    "code": 200,
    "data": {
        "balance": {
            "accountId": 20001,
            "balances": [
                {
                    "asset": "USDT",
                    "total": "100000.00",
                    "available": "100000.00",
                    "frozen": "0.00"
                }
            ]
        },
        "positions": [],                    // 当前持仓列表
        "openOrders": [],                   // 当前订单列表
        "recentTrades": [],                 // 最近成交
        "orderBook": {                      // 盘口深度
            "symbol": "BTCUSDT",
            "lastUpdateId": 123456789,
            "bids": [["42000.00", "1.5"], ["41999.00", "2.0"], ...],
            "asks": [["42001.00", "1.2"], ["42002.00", "0.8"], ...]
        },
        "marketTrades": [                   // 市场最近成交
            {"price": "42000.50", "quantity": "0.5", "side": "BUY", "time": 1705310400000},
            ...
        ],
        "klines": [                         // K线数据
            {"openTime": 1705310400000, "open": "41900.00", "high": "42100.00", "low": "41850.00", "close": "42000.00", "volume": "100.5"},
            ...
        ],
        "ticker24h": {                      // 24小时统计
            "symbol": "BTCUSDT",
            "priceChange": "+500.00",
            "priceChangePercent": "+1.20",
            "lastPrice": "42000.00",
            "highPrice": "42500.00",
            "lowPrice": "41500.00",
            "volume": "15000.5"
        }
    }
}
```

---

### 快速查询接口

```http
# 查询余额
GET /api/v1/trading/balance
Authorization: Bearer {token}

# 查询持仓
GET /api/v1/trading/positions?symbol=BTCUSDT
Authorization: Bearer {token}

# 查询当前订单
GET /api/v1/trading/orders/open?symbol=BTCUSDT
Authorization: Bearer {token}
```

---

## 🔄 完整注册流程

```
┌─────────────────────────────────────────────────────────────────┐
│  1. 用户调用 /api/v1/user/register                               │
│     {username: "trader001", password: "123456"}                  │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  2. UserService 创建用户记录                                     │
│     INSERT INTO t_user (...)                                    │
│     → userId = 10001                                            │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  3. UserService 创建交易账户                                     │
│     INSERT INTO t_trading_account (user_id=10001, ...)          │
│     → accountId = 20001                                         │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  4. UserService 调用 LedgerClient                              │
│     POST /internal/ledger/initial-funding                       │
│     {accountId: 20001, userId: 10001, asset: "USDT",            │
│      amount: 100000, reason: "INITIAL_FUNDING"}                 │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  5. Ledger-Core 创建初始资金分录                                  │
│     借：用户账户 100,000 USDT                                    │
│     贷：系统资金池 100,000 USDT                                  │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  6. Snapshot-Account-Core 消费事件更新余额                        │
│     accountId=20001 的可用余额 = 100,000 USDT                    │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  7. 返回注册响应，包含 userId + accountId + token                │
└─────────────────────────────────────────────────────────────────┘
```

---

## ⚙️ 配置说明

### application.yml 关键配置

```yaml
# 初始资金配置
initial:
  funding:
    enabled: true              # 是否启用自动注入初始资金
    amount: 100000             # 初始USDT金额（默认10万）
    asset: USDT                # 初始资金资产类型

# JWT配置
jwt:
  secret: future-exchange-user-core-secret-key-2024
  expiration: 86400000         # Token有效期24小时（毫秒）
```

---

## 🗄️ 数据库表结构

### t_user - 用户表
| 字段 | 类型 | 说明 |
|-----|------|------|
| id | BIGINT | 用户ID |
| username | VARCHAR(32) | 用户名（唯一） |
| password_hash | VARCHAR(128) | bcrypt加密后的密码 |
| email | VARCHAR(128) | 邮箱（可选） |
| phone | VARCHAR(32) | 手机号（可选） |
| status | TINYINT | 0-禁用 1-正常 |
| user_type | VARCHAR(16) | RETAIL-零售 INSTITUTIONAL-机构 |

### t_trading_account - 交易账户表
| 字段 | 类型 | 说明 |
|-----|------|------|
| id | BIGINT | 账户ID |
| user_id | BIGINT | 关联用户ID |
| account_type | VARCHAR(16) | STANDARD-标准 |
| margin_mode | VARCHAR(16) | CROSS-全仓 ISOLATED-逐仓 |
| default_leverage | INT | 默认杠杆（默认10倍） |
| is_funded | TINYINT | 是否已初始化资金 |

### t_funding_adjustment - 资金调整记录表
| 字段 | 类型 | 说明 |
|-----|------|------|
| id | BIGINT | 记录ID |
| account_id | BIGINT | 账户ID |
| amount | DECIMAL(32,8) | 调整金额 |
| reason | VARCHAR(32) | INITIAL_FUNDING / MANUAL_ADJUST |
| ledger_entry_id | VARCHAR(64) | 关联的ledger分录ID |

---

## 🔗 服务依赖

```
user-core (8099)
    ├── 调用 ledger-core (8084)      - 创建初始资金分录
    ├── 调用 snapshot-account-core (8085) - 查询余额
    ├── 调用 position-snapshot-core (8086) - 查询持仓
    ├── 调用 oms-core (8081)          - 查询订单
    ├── 调用 market-price-core (8095) - 查询行情
    └── 依赖 Redis (数据库2)           - Token缓存
```

---

## 📝 待办事项

- [ ] 实现 ledger-core 的 `/internal/ledger/initial-funding` 接口
- [ ] 补充 snapshot-account-core 的余额查询接口
- [ ] 补充 position-snapshot-core 的持仓查询接口
- [ ] 补充 oms-core 的订单查询接口
- [ ] 补充 market-price-core 的行情查询接口

---

*最后更新: 2026-02-19*
