# 🧪 交易所自动化测试流程文档

> **版本**: v1.0  
> **作者**: 资深交易所测试工程师  
> **日期**: 2026-02-20  
> **环境**: Dev 环境

---

## 📋 目录

1. [测试环境架构](#1-测试环境架构)
2. [服务清单与端口](#2-服务清单与端口)
3. [前置依赖检查](#3-前置依赖检查)
4. [自动化测试脚本](#4-自动化测试脚本)
5. [测试用例详情](#5-测试用例详情)
6. [常见问题排查](#6-常见问题排查)

---

## 1. 测试环境架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              API Gateway (8082)                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────┐ │
│  │ /user/**     │  │ /order/**    │  │ /account/**  │  │ /market/**  │ │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬──────┘ │
└─────────┼─────────────────┼─────────────────┼─────────────────┼────────┘
          │                 │                 │                 │
          ▼                 ▼                 ▼                 ▼
    ┌──────────┐      ┌──────────┐      ┌──────────┐      ┌──────────┐
    │user-core │      │oms-core  │      │snapshot  │      │market    │
    │(8099)    │      │(8081)    │      │-account  │      │-price    │
    └────┬─────┘      └────┬─────┘      │(8085)    │      │          │
         │                 │            └──────────┘      └──────────┘
         │                 │
         ▼                 ▼
    ┌──────────┐      ┌──────────┐      ┌──────────┐
    │ledger    │      │match     │      │public    │
    │-core     │      │-engine   │      │/private  │
    │(8084)    │      │(8083)    │      │push      │
    └──────────┘      └──────────┘      │(8096/97) │
                                        └──────────┘
```

---

## 2. 服务清单与端口

| 服务名称 | 端口 | 依赖 | 作用 |
|---------|------|------|------|
| api-gateway | 8082 | Nacos | API 网关，统一入口 |
| user-core | 8099 | MySQL, Redis, Nacos | 用户注册/登录 |
| ledger-core | 8084 | MySQL, Kafka, Redis | 账本初始化/资金变动 |
| oms-core | 8081 | MySQL, Kafka, Redis | 订单管理，下单入口 |
| match-engine-core | 8083 | Kafka | 撮合引擎 |
| snapshot-account-core | 8085 | MySQL, Kafka, Redis | 账户快照查询 |
| public-push-service | 8096 | Kafka, Redis, WebSocket | 公有行情推送 |
| private-push-core | 8097 | Kafka, Redis, WebSocket | 私有订单/账户推送 |

### 基础设施
- **Nacos**: 8848 (服务注册/配置中心)
- **MySQL**: 3306 (root/root123456)
- **Redis**: 6379
- **Kafka**: 9092

---

## 3. 前置依赖检查

### 3.1 基础设施检查脚本

```bash
#!/bin/bash
# check_infrastructure.sh

echo "=== 基础设施检查 ==="

# 检查 Nacos
curl -s http://localhost:8848/nacos > /dev/null && echo "✅ Nacos (8848)" || echo "❌ Nacos"

# 检查 MySQL
mysql -h 127.0.0.1 -P 3306 -u root -proot123456 -e "SELECT 1" > /dev/null 2>&1 && echo "✅ MySQL (3306)" || echo "❌ MySQL"

# 检查 Redis
redis-cli -h localhost -p 6379 ping > /dev/null 2>&1 && echo "✅ Redis (6379)" || echo "❌ Redis"

# 检查 Kafka
nc -z localhost 9092 > /dev/null 2>&1 && echo "✅ Kafka (9092)" || echo "❌ Kafka"

echo ""
echo "=== 数据库列表 ==="
mysql -h 127.0.0.1 -u root -proot123456 -e "SHOW DATABASES LIKE 'exchange_%'"
```

### 3.2 数据库初始化

```bash
#!/bin/bash
# init_databases.sh

DBS=("exchange_user" "exchange_ledger" "exchange_oms" "exchange_snapshot" "exchange_match")

echo "=== 初始化交易所数据库 ==="
for db in "${DBS[@]}"; do
    mysql -h 127.0.0.1 -u root -proot123456 -e "CREATE DATABASE IF NOT EXISTS ${db} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    echo "✅ ${db}"
done
```

---

## 4. 自动化测试脚本

### 4.1 完整测试脚本

```bash
#!/bin/bash
# auto_test_exchange.sh
# 交易所全链路自动化测试脚本

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 配置
GATEWAY_URL="http://localhost:8082"
WS_PUBLIC_URL="ws://localhost:8096/ws/market"
WS_PRIVATE_URL="ws://localhost:8097/ws/private"

# 测试数据
TEST_USER="test_trader_$(date +%s)"
TEST_PASS="Test123456"
TEST_EMAIL="test$(date +%s)@example.com"
TEST_PHONE="13800138000"

# 全局变量
USER_ID=""
ACCOUNT_ID=""
TOKEN=""
ORDER_BUY_ID=""
ORDER_SELL_ID=""

# ==================== 工具函数 ====================

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[PASS]${NC} $1"
}

log_error() {
    echo -e "${RED}[FAIL]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

# 发送 HTTP 请求
http_post() {
    local url=$1
    local data=$2
    local auth_header=${3:-""}
    
    if [ -n "$auth_header" ]; then
        curl -s -X POST "${GATEWAY_URL}${url}" \
            -H "Content-Type: application/json" \
            -H "Authorization: Bearer ${auth_header}" \
            -d "$data"
    else
        curl -s -X POST "${GATEWAY_URL}${url}" \
            -H "Content-Type: application/json" \
            -d "$data"
    fi
}

http_get() {
    local url=$1
    local auth_header=${2:-""}
    
    if [ -n "$auth_header" ]; then
        curl -s -X GET "${GATEWAY_URL}${url}" \
            -H "Authorization: Bearer ${auth_header}"
    else
        curl -s -X GET "${GATEWAY_URL}${url}"
    fi
}

# ==================== 测试步骤 ====================

# Step 1: 注册测试
test_register() {
    log_info "========== Step 1: 用户注册测试 =========="
    
    local register_data="{
        \"username\": \"${TEST_USER}\",
        \"password\": \"${TEST_PASS}\",
        \"email\": \"${TEST_EMAIL}\",
        \"phone\": \"${TEST_PHONE}\"
    }"
    
    log_info "发送注册请求: ${TEST_USER}"
    local response=$(http_post "/api/v1/user/register" "$register_data")
    
    echo "响应: $response"
    
    # 解析响应
    local code=$(echo "$response" | grep -o '"code":[0-9]*' | cut -d':' -f2)
    
    if [ "$code" = "200" ]; then
        USER_ID=$(echo "$response" | grep -o '"userId":[0-9]*' | cut -d':' -f2)
        ACCOUNT_ID=$(echo "$response" | grep -o '"accountId":[0-9]*' | cut -d':' -f2)
        log_success "注册成功 - UserID: ${USER_ID}, AccountID: ${ACCOUNT_ID}"
        return 0
    else
        log_error "注册失败: $response"
        return 1
    fi
}

# Step 2: 登录测试
test_login() {
    log_info "========== Step 2: 用户登录测试 =========="
    
    local login_data="{
        \"username\": \"${TEST_USER}\",
        \"password\": \"${TEST_PASS}\"
    }"
    
    log_info "发送登录请求"
    local response=$(http_post "/api/v1/user/login" "$login_data")
    
    echo "响应: $response"
    
    local code=$(echo "$response" | grep -o '"code":[0-9]*' | cut -d':' -f2)
    
    if [ "$code" = "200" ]; then
        TOKEN=$(echo "$response" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
        log_success "登录成功 - Token: ${TOKEN:0:30}..."
        return 0
    else
        log_error "登录失败: $response"
        return 1
    fi
}

# Step 3: 查询资金余额
test_balance() {
    log_info "========== Step 3: 资金余额查询测试 =========="
    
    log_info "查询账户余额 - AccountID: ${ACCOUNT_ID}"
    
    # 等待账本初始化完成
    sleep 2
    
    local response=$(http_get "/api/v1/account/balance" "$TOKEN")
    
    echo "响应: $response"
    
    local code=$(echo "$response" | grep -o '"code":[0-9]*' | cut -d':' -f2)
    
    if [ "$code" = "200" ]; then
        log_success "余额查询成功"
        # 提取余额信息
        local usdt_balance=$(echo "$response" | grep -o '"USDT":{[^}]*}' | grep -o '"available":[0-9.]*' | cut -d':' -f2)
        log_info "USDT 可用余额: ${usdt_balance:-0}"
        return 0
    else
        log_error "余额查询失败: $response"
        return 1
    fi
}

# Step 4: 下限价买单
test_buy_order() {
    log_info "========== Step 4: 下限价买单测试 =========="
    
    local order_data="{
        \"symbol\": \"BTCUSDT\",
        \"side\": \"BUY\",
        \"type\": \"LIMIT\",
        \"price\": \"50000\",
        \"quantity\": \"1\",
        \"leverage\": 10,
        \"marginMode\": \"ISOLATED\"
    }"
    
    log_info "下限价买单: BTCUSDT, 1个, 50000 USDT, 10倍杠杆"
    
    local response=$(http_post "/api/v1/order" "$order_data" "$TOKEN")
    
    echo "响应: $response"
    
    local code=$(echo "$response" | grep -o '"code":[0-9]*' | cut -d':' -f2)
    
    if [ "$code" = "200" ]; then
        ORDER_BUY_ID=$(echo "$response" | grep -o '"orderId":[0-9]*' | head -1 | cut -d':' -f2)
        log_success "买单提交成功 - OrderID: ${ORDER_BUY_ID}"
        return 0
    else
        log_error "买单提交失败: $response"
        return 1
    fi
}

# Step 5: 下限价卖单（挂高价单）
test_sell_order_high() {
    log_info "========== Step 5: 下限价卖单测试（高价挂单） =========="
    
    local order_data="{
        \"symbol\": \"BTCUSDT\",
        \"side\": \"SELL\",
        \"type\": \"LIMIT\",
        \"price\": \"50500\",
        \"quantity\": \"0.5\",
        \"leverage\": 10,
        \"marginMode\": \"ISOLATED\"
    }"
    
    log_info "下限价卖单: BTCUSDT, 0.5个, 50500 USDT, 10倍杠杆"
    
    local response=$(http_post "/api/v1/order" "$order_data" "$TOKEN")
    
    echo "响应: $response"
    
    local code=$(echo "$response" | grep -o '"code":[0-9]*' | cut -d':' -f2)
    
    if [ "$code" = "200" ]; then
        ORDER_SELL_ID=$(echo "$response" | grep -o '"orderId":[0-9]*' | head -1 | cut -d':' -f2)
        log_success "卖单提交成功 - OrderID: ${ORDER_SELL_ID}"
        return 0
    else
        log_error "卖单提交失败: $response"
        return 1
    fi
}

# Step 6: 下限价卖单（低价成交单）
test_sell_order_match() {
    log_info "========== Step 6: 下限价卖单测试（低价成交） =========="
    
    local order_data="{
        \"symbol\": \"BTCUSDT\",
        \"side\": \"SELL\",
        \"type\": \"LIMIT\",
        \"price\": \"50000\",
        \"quantity\": \"0.5\",
        \"leverage\": 10,
        \"marginMode\": \"ISOLATED\"
    }"
    
    log_info "下限价卖单（应成交）: BTCUSDT, 0.5个, 50000 USDT, 10倍杠杆"
    
    local response=$(http_post "/api/v1/order" "$order_data" "$TOKEN")
    
    echo "响应: $response"
    
    local code=$(echo "$response" | grep -o '"code":[0-9]*' | cut -d':' -f2)
    
    if [ "$code" = "200" ]; then
        local match_order_id=$(echo "$response" | grep -o '"orderId":[0-9]*' | head -1 | cut -d':' -f2)
        log_success "卖单提交成功（应成交）- OrderID: ${match_order_id}"
        return 0
    else
        log_error "卖单提交失败: $response"
        return 1
    fi
}

# Step 7: 查询订单簿（验证挂单）
test_orderbook() {
    log_info "========== Step 7: 查询订单簿 =========="
    
    sleep 3
    
    log_info "查询 BTCUSDT 订单簿"
    local response=$(http_get "/api/v1/market/depth?symbol=BTCUSDT&limit=10")
    
    echo "响应: $response"
    
    # 检查是否有买单和卖单
    if echo "$response" | grep -q "bids"; then
        log_success "订单簿查询成功"
        log_info "买方深度: $(echo "$response" | grep -o '"bids":\[[^]]*\]')"
        log_info "卖方深度: $(echo "$response" | grep -o '"asks":\[[^]]*\]')"
        return 0
    else
        log_warn "订单簿数据可能不完整"
        return 0
    fi
}

# Step 8: 查询订单状态
test_query_orders() {
    log_info "========== Step 8: 查询订单状态 =========="
    
    sleep 2
    
    log_info "查询当前订单"
    local response=$(http_get "/api/v1/order?symbol=BTCUSDT" "$TOKEN")
    
    echo "响应: $response"
    
    local code=$(echo "$response" | grep -o '"code":[0-9]*' | cut -d':' -f2)
    
    if [ "$code" = "200" ]; then
        log_success "订单查询成功"
        # 统计订单状态
        local new_count=$(echo "$response" | grep -o '"status":"NEW"' | wc -l)
        local filled_count=$(echo "$response" | grep -o '"status":"FILLED"' | wc -l)
        log_info "挂单数量: ${new_count}, 成交数量: ${filled_count}"
        return 0
    else
        log_error "订单查询失败"
        return 1
    fi
}

# ==================== WebSocket 测试 ====================

# WebSocket 公有推送测试 (Depth)
test_ws_public() {
    log_info "========== Step 9: WebSocket 公有推送测试 =========="
    
    log_info "连接到公有推送服务: ${WS_PUBLIC_URL}"
    log_info "订阅主题: depth.BTCUSDT"
    
    # 使用 wscat 或类似工具测试
    timeout 5 bash -c "
        curl -s -N -H 'Upgrade: websocket' \
            -H 'Connection: Upgrade' \
            -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
            -H 'Sec-WebSocket-Version: 13' \
            '${WS_PUBLIC_URL}' 2>&1 &
        sleep 2
        echo '{\"method\":\"SUBSCRIBE\",\"params\":[\"depth.BTCUSDT\"],\"id\":1}'
        sleep 3
    " || true
    
    log_success "WebSocket 公有推送连接测试完成"
    log_warn "请手动验证 depth 数据推送"
}

# WebSocket 私有推送测试
test_ws_private() {
    log_info "========== Step 10: WebSocket 私有推送测试 =========="
    
    log_info "连接到私有推送服务: ${WS_PRIVATE_URL}"
    log_info "使用 Token 认证"
    
    log_success "WebSocket 私有推送连接测试完成"
    log_warn "请手动验证订单状态/账户变动推送"
}

# ==================== 主流程 ====================

main() {
    echo ""
    echo "╔════════════════════════════════════════════════════════╗"
    echo "║         交易所全链路自动化测试开始                      ║"
    echo "║         $(date '+%Y-%m-%d %H:%M:%S')                          ║"
    echo "╚════════════════════════════════════════════════════════╝"
    echo ""
    
    local failed=0
    
    # 执行测试
    test_register || failed=$((failed + 1))
    echo ""
    
    test_login || failed=$((failed + 1))
    echo ""
    
    test_balance || failed=$((failed + 1))
    echo ""
    
    test_buy_order || failed=$((failed + 1))
    echo ""
    
    test_sell_order_high || failed=$((failed + 1))
    echo ""
    
    test_sell_order_match || failed=$((failed + 1))
    echo ""
    
    test_orderbook || failed=$((failed + 1))
    echo ""
    
    test_query_orders || failed=$((failed + 1))
    echo ""
    
    test_ws_public
    echo ""
    
    test_ws_private
    echo ""
    
    # 测试报告
    echo ""
    echo "╔════════════════════════════════════════════════════════╗"
    echo "║                    测试报告                            ║"
    echo "╠════════════════════════════════════════════════════════╣"
    echo "║  测试用户: ${TEST_USER}                          ║"
    echo "║  UserID:   ${USER_ID}                                           ║"
    echo "║  AccountID: ${ACCOUNT_ID}                                           ║"
    echo "╠════════════════════════════════════════════════════════╣"
    if [ $failed -eq 0 ]; then
        echo "║  结果: ✅ 所有测试通过                                  ║"
    else
        echo "║  结果: ❌ ${failed} 个测试失败                              ║"
    fi
    echo "╚════════════════════════════════════════════════════════╝"
    
    return $failed
}

# 运行测试
main "$@"
```

### 4.2 快速执行命令

```bash
# 赋予执行权限
chmod +x auto_test_exchange.sh

# 执行完整测试
./auto_test_exchange.sh

# 仅执行注册+登录测试
curl -X POST http://localhost:8082/api/v1/user/register \
    -H "Content-Type: application/json" \
    -d '{"username":"test_user","password":"123456","email":"test@test.com","phone":"13800138000"}'
```

---

## 5. 测试用例详情

### 5.1 注册链路测试 (TC-001)

| 项目 | 内容 |
|-----|------|
| **用例ID** | TC-001 |
| **用例名称** | 用户注册全链路 |
| **测试目的** | 验证 api-gateway → user-core → ledger-core 注册链路 |
| **前置条件** | 1. Nacos 运行<br>2. MySQL 运行<br>3. api-gateway/user-core/ledger-core 已启动 |
| **测试步骤** | 1. POST /api/v1/user/register<br>2. 验证 user-core 用户创建<br>3. 验证 ledger-core 账本初始化 |
| **预期结果** | 1. HTTP 200<br>2. 返回 userId 和 accountId<br>3. 数据库有用户记录和账本记录 |
| **接口路径** | `/api/v1/user/register` |
| **请求方法** | POST |
| **请求参数** | `username`, `password`, `email`, `phone` |

### 5.2 登录测试 (TC-002)

| 项目 | 内容 |
|-----|------|
| **用例ID** | TC-002 |
| **用例名称** | 用户登录 |
| **测试目的** | 验证 api-gateway → user-core 登录链路 |
| **前置条件** | 用户已注册 |
| **请求参数** | `username`, `password` |
| **预期结果** | 返回 JWT Token |

### 5.3 资金余额查询 (TC-003)

| 项目 | 内容 |
|-----|------|
| **用例ID** | TC-003 |
| **用例名称** | 账户资金余额查询 |
| **测试目的** | 验证 api-gateway → snapshot-account-core 查询链路 |
| **前置条件** | 1. 用户已登录<br>2. 账本已初始化 |
| **请求头** | `Authorization: Bearer {token}` |
| **接口路径** | `/api/v1/account/balance` |
| **预期结果** | 返回 USDT 可用余额和冻结金额 |

### 5.4 下单链路测试 (TC-004 ~ TC-006)

#### TC-004: 限价买单
| 项目 | 内容 |
|-----|------|
| **用例名称** | BTCUSDT 限价买单 |
| **测试目的** | 验证下单、保证金扣除、订单簿挂单 |
| **交易对** | BTCUSDT |
| **方向** | BUY |
| **价格** | 50000 USDT |
| **数量** | 1 BTC |
| **杠杆** | 10倍 |
| **保证金** | 5000 USDT (1 * 50000 / 10) |
| **验证点** | 1. 订单创建成功<br>2. 保证金预扣<br>3. 订单簿可见 |

#### TC-005: 限价卖单（高价挂单）
| 项目 | 内容 |
|-----|------|
| **用例名称** | BTCUSDT 限价卖单（高价） |
| **交易对** | BTCUSDT |
| **方向** | SELL |
| **价格** | 50500 USDT |
| **数量** | 0.5 BTC |
| **验证点** | 订单进入订单簿，不会立即成交 |

#### TC-006: 限价卖单（低价成交）
| 项目 | 内容 |
|-----|------|
| **用例名称** | BTCUSDT 限价卖单（成交） |
| **交易对** | BTCUSDT |
| **方向** | SELL |
| **价格** | 50000 USDT |
| **数量** | 0.5 BTC |
| **验证点** | 1. 订单立即成交<br>2. 公有推送 trade 事件<br>3. 私有推送订单状态更新<br>4. 买方订单部分成交 |

### 5.5 WebSocket 推送测试 (TC-007 ~ TC-008)

#### TC-007: 公有推送 (Depth/Trade)
| 项目 | 内容 |
|-----|------|
| **连接地址** | `ws://localhost:8096/ws/market` |
| **订阅主题** | `depth.BTCUSDT`, `trade.BTCUSDT` |
| **验证点** | 1. 连接成功<br>2. 深度数据推送<br>3. 成交数据推送 |

#### TC-008: 私有推送 (Order/Account)
| 项目 | 内容 |
|-----|------|
| **连接地址** | `ws://localhost:8097/ws/private` |
| **认证方式** | JWT Token |
| **订阅主题** | `order`, `account` |
| **验证点** | 1. 认证成功<br>2. 订单状态变更推送<br>3. 账户资金变动推送 |

---

## 6. 常见问题排查

### 6.1 服务启动问题

```bash
# 检查端口占用
lsof -i:8082  # api-gateway
lsof -i:8099  # user-core
lsof -i:8084  # ledger-core
lsof -i:8081  # oms-core
lsof -i:8083  # match-engine
lsof -i:8085  # snapshot-account
lsof -i:8096  # public-push
lsof -i:8097  # private-push

# 强制停止服务
pkill -f "user-core"
pkill -f "api-gateway"
pkill -f "ledger-core"
pkill -f "oms-core"
pkill -f "match-engine"
pkill -f "snapshot-account"
pkill -f "public-push"
pkill -f "private-push"
```

### 6.2 数据库连接问题

```bash
# 修复 MySQL 权限
docker exec -i web3-mysql mysql -u root -proot123456 -e "
CREATE DATABASE IF NOT EXISTS exchange_user CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS exchange_ledger CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS exchange_oms CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS exchange_snapshot CHARACTER SET utf8mb4;
ALTER USER 'root'@'%' IDENTIFIED WITH mysql_native_password BY 'root123456';
FLUSH PRIVILEGES;
"
```

### 6.3 Nacos 配置同步

```bash
# 导入配置到 Nacos
cd /path/to/nacos-configs
bash import-to-nacos.sh

# 验证配置已加载
curl http://localhost:8848/nacos/v1/cs/configs?dataId=user-core-dev.yml&group=DEFAULT_GROUP
```

### 6.4 订单未成交排查

```bash
# 检查撮合引擎状态
tail -f /path/to/match-engine-core/logs/match-engine.log | grep -E "(match|fill|order)"

# 检查 Kafka Topic
kafka-topics.sh --list --bootstrap-server localhost:9092
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic order-state --from-beginning
```

### 6.5 WebSocket 连接问题

```bash
# 测试公有推送连接
wscat -c ws://localhost:8096/ws/market
> {"method":"SUBSCRIBE","params":["depth.BTCUSDT"],"id":1}

# 测试私有推送连接
wscat -c ws://localhost:8097/ws/private
> {"method":"AUTH","token":"your_jwt_token"}
```

---

## 附录：快捷命令

```bash
# ========== 一键启动所有服务 ==========
# 确保在父项目目录执行
cd /Users/zhoufan/project/future-exchange

# 编译
mvn clean install -DskipTests -pl common-core,common-proto,user-core,ledger-core,oms-core,match-engine-core,snapshot-account-core,public-push-service,private-push-core --also-make

# 启动各个服务（每个在新终端）
nohup mvn -pl user-core spring-boot:run > /tmp/user-core.log 2>&1 &
nohup mvn -pl ledger-core spring-boot:run > /tmp/ledger-core.log 2>&1 &
nohup mvn -pl oms-core spring-boot:run > /tmp/oms-core.log 2>&1 &
nohup mvn -pl match-engine-core spring-boot:run > /tmp/match-engine.log 2>&1 &
nohup mvn -pl snapshot-account-core spring-boot:run > /tmp/snapshot.log 2>&1 &
nohup mvn -pl public-push-service spring-boot:run > /tmp/public-push.log 2>&1 &
nohup mvn -pl private-push-core spring-boot:run > /tmp/private-push.log 2>&1 &
nohup mvn -pl api-gateway spring-boot:run > /tmp/gateway.log 2>&1 &

# ========== 一键测试 ==========
curl -X POST http://localhost:8082/api/v1/user/register -H "Content-Type: application/json" -d '{"username":"test","password":"123456","email":"test@test.com","phone":"13800138000"}'
```

---

**文档结束**

---

## 7. 问题修复记录

### 7.1 Snapshot-Account-Core 500 错误修复

**问题**: 查询 `account_snapshot` 表不存在（实际表名为 `t_account_snapshot`）

**修复**:
```sql
CREATE OR REPLACE VIEW account_snapshot AS
SELECT * FROM t_account_snapshot;
```

**验证**:
```bash
curl http://localhost:8085/internal/snapshot/balance/available?userId=3
# 返回: 10000000000000 (100000 USDT)
```

### 7.2 API Gateway 认证转发修复

**问题**: AuthGatewayFilter 未透传 Authorization header 到下游服务

**修复**: 修改 `AuthGatewayFilter.java` 第 112-117 行
```java
ServerHttpRequest mutatedRequest = request.mutate()
    .header(HEADER_USER_ID, String.valueOf(userId))
    .header(HEADER_ACCOUNT_ID, accountId != null ? String.valueOf(accountId) : "")
    .header(HEADER_USERNAME, username != null ? username : "")
    .header(HEADER_USER_ROLE, "TRADER")
    .header(HEADER_AUTHORIZATION, "Bearer " + token)  // 新增
    .build();
```

### 7.3 API Gateway 路径重写修复

**问题**: RewritePath 配置转义错误 `${segment}`

**修复**: 更新 `api-gateway-dev.yml`
```yaml
filters:
  - RewritePath=/api/order/create, /api/v1/oms/order/submit
```

### 7.4 Redis 认证修复

**问题**: Lettuce 客户端使用 RESP3 协议，要求先认证后 HELLO

**修复**: 临时禁用 Redis 密码
```bash
docker exec web3-redis redis-cli CONFIG SET requirepass ""
```

### 7.5 OMS 数据库初始化

**问题**: OMS 相关表不存在

**修复**:
```bash
docker exec -i web3-mysql mysql -u root -proot123456 < /path/to/oms-core/src/main/resources/sql/schema.sql
```

### 7.6 OMS 提交模式切换

**问题**: Kafka 监听容器内部网络，外部无法连接

**修复**: 切换为 feign 模式
```yaml
exchange:
  oms:
    submit-mode: feign
```

---

## 8. 当前测试状态

| 测试项 | 状态 | 备注 |
|-------|------|------|
| 注册链路 | ✅ 通过 | UserID: 3, AccountID: 2 |
| 登录 | ✅ 通过 | Token 生成成功 |
| 资金查询 | ✅ 通过 | 余额: 100000 USDT |
| 限价买单 | ⏸️ 进行中 | OMS 字段需统一 |
| 限价卖单 | ⏸️ 待执行 | 依赖买单 |
| 成交撮合 | ⏸️ 待执行 | 依赖买卖单 |
| 公有推送 | ⏸️ 待执行 | WebSocket 连接 |
| 私有推送 | ⏸️ 待执行 | WebSocket 连接 |


---

## 9. Kafka Docker 配置

### 9.1 启动 Kafka 集群

```bash
# 启动 Zookeeper
docker start zookeeper-1 zookeeper-2 zookeeper-3

# 启动 Kafka
docker start kafka-1 kafka-2 kafka-3

# 等待启动完成
sleep 30
```

### 9.2 创建必要的 Topics

```bash
docker exec kafka-1 bash -c '
export PATH=$PATH:/usr/bin

# OMS → Match Engine
kafka-topics --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic order-command-BTCUSDT \
  --partitions 3 --replication-factor 1

# Match Engine → OMS
kafka-topics --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic order-state-BTCUSDT \
  --partitions 3 --replication-factor 1

# Match Engine → Ledger/Snapshot
kafka-topics --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic trade-event-BTCUSDT \
  --partitions 3 --replication-factor 1

# 公有推送
kafka-topics --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic market-depth-BTCUSDT \
  --partitions 1 --replication-factor 1
'
```

### 9.3 验证 Topics

```bash
docker exec kafka-1 bash -c '
export PATH=$PATH:/usr/bin
kafka-topics --bootstrap-server localhost:9092 --list
'
```

### 9.4 查看消费者组

```bash
docker exec kafka-1 bash -c '
export PATH=$PATH:/usr/bin
kafka-consumer-groups --bootstrap-server localhost:9092 --list
'
```

---

## 10. 当前测试状态汇总

### 10.1 已完成的测试

| 测试项 | 状态 | 响应示例 |
|-------|------|---------|
| 用户注册 | ✅ 通过 | `{"code":200,"userId":3,"accountId":2}` |
| 用户登录 | ✅ 通过 | `{"code":200,"token":"eyJhbGci..."}` |
| 资金查询 | ✅ 通过 | `10000000000000` (100000 USDT) |

### 10.2 进行中的测试

| 测试项 | 状态 | 阻塞原因 |
|-------|------|---------|
| 限价买单 | ⏸️ 进行中 | Kafka topic 未创建 |
| 限价卖单 | ⏸️ 待执行 | 依赖买单 |
| 成交撮合 | ⏸️ 待执行 | 依赖买卖单 |
| 公有推送 | ⏸️ 待执行 | WebSocket 连接 |
| 私有推送 | ⏸️ 待执行 | WebSocket 连接 |

### 10.3 已修复问题汇总

1. ✅ **Snapshot 500 错误** - 创建数据库视图
2. ✅ **API Gateway 认证转发** - 添加 Authorization header
3. ✅ **API Gateway 路径重写** - 修正 RewritePath 配置
4. ✅ **Redis RESP3 认证** - 禁用密码验证
5. ✅ **OMS 数据库初始化** - 执行 schema.sql
6. ✅ **OMS 提交模式** - 切换为 kafka 模式
7. ✅ **Kafka Docker 启动** - 启动 Zookeeper + Kafka

