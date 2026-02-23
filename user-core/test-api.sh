#!/bin/bash

# ========================================================
# User Core API 测试脚本
# ========================================================

BASE_URL="http://localhost:8099"

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo "╔══════════════════════════════════════════════════════════╗"
echo "║         User Core Service API Test Script               ║"
echo "║              Base URL: $BASE_URL              ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# 检查服务是否启动
echo -e "${YELLOW}[Step 1] Checking service status...${NC}"
if ! curl -s "$BASE_URL/actuator/health" > /dev/null 2>&1; then
    echo -e "${RED}✗ Service is not running at $BASE_URL${NC}"
    echo "Please start user-core service first:"
    echo "  cd user-core && mvn spring-boot:run"
    exit 1
fi
echo -e "${GREEN}✓ Service is running${NC}"
echo ""

# 注册用户
echo -e "${YELLOW}[Step 2] Testing user registration...${NC}"
REGISTER_RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/user/register" \
    -H "Content-Type: application/json" \
    -d '{
        "username": "testuser_'$(date +%s)'",
        "password": "123456",
        "email": "test@example.com"
    }')

echo "Response: $REGISTER_RESPONSE"

# 提取用户数据
USER_ID=$(echo $REGISTER_RESPONSE | grep -o '"userId":[0-9]*' | cut -d':' -f2)
ACCOUNT_ID=$(echo $REGISTER_RESPONSE | grep -o '"accountId":[0-9]*' | cut -d':' -f2)
CODE=$(echo $REGISTER_RESPONSE | grep -o '"code":[0-9]*' | cut -d':' -f2)

if [ "$CODE" = "200" ]; then
    echo -e "${GREEN}✓ Registration successful${NC}"
    echo "  User ID: $USER_ID"
    echo "  Account ID: $ACCOUNT_ID"
else
    echo -e "${YELLOW}⚠ Registration may have failed (code: $CODE)${NC}"
    # 尝试使用固定用户登录
    USERNAME="testuser_12345"
fi
echo ""

# 用户登录
echo -e "${YELLOW}[Step 3] Testing user login...${NC}"
LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/user/login" \
    -H "Content-Type: application/json" \
    -d '{
        "username": "'$(echo $REGISTER_RESPONSE | grep -o '"username":"[^"]*"' | cut -d'"' -f4)'",
        "password": "123456"
    }')

echo "Response: $LOGIN_RESPONSE"

# 提取Token
TOKEN=$(echo $LOGIN_RESPONSE | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
CODE=$(echo $LOGIN_RESPONSE | grep -o '"code":[0-9]*' | cut -d':' -f2)

if [ "$CODE" = "200" ] && [ ! -z "$TOKEN" ]; then
    echo -e "${GREEN}✓ Login successful${NC}"
    echo "  Token: ${TOKEN:0:50}..."
else
    echo -e "${RED}✗ Login failed${NC}"
    exit 1
fi
echo ""

# 获取当前用户信息
echo -e "${YELLOW}[Step 4] Testing get current user...${NC}"
USER_RESPONSE=$(curl -s -X GET "$BASE_URL/api/v1/user/me" \
    -H "Authorization: Bearer $TOKEN")

echo "Response: $USER_RESPONSE"
CODE=$(echo $USER_RESPONSE | grep -o '"code":[0-9]*' | cut -d':' -f2)

if [ "$CODE" = "200" ]; then
    echo -e "${GREEN}✓ Get user info successful${NC}"
else
    echo -e "${RED}✗ Get user info failed${NC}"
fi
echo ""

# 获取交易账户信息
echo -e "${YELLOW}[Step 5] Testing get trading account...${NC}"
ACCOUNT_RESPONSE=$(curl -s -X GET "$BASE_URL/api/v1/user/account" \
    -H "Authorization: Bearer $TOKEN")

echo "Response: $ACCOUNT_RESPONSE"
CODE=$(echo $ACCOUNT_RESPONSE | grep -o '"code":[0-9]*' | cut -d':' -f2)

if [ "$CODE" = "200" ]; then
    echo -e "${GREEN}✓ Get account info successful${NC}"
else
    echo -e "${RED}✗ Get account info failed${NC}"
fi
echo ""

# 获取交易面板数据（需要其他服务支持）
echo -e "${YELLOW}[Step 6] Testing get trading dashboard...${NC}"
echo "Note: This requires market-price-core, oms-core, etc. to be running"
DASHBOARD_RESPONSE=$(curl -s -X GET "$BASE_URL/api/v1/trading/dashboard?symbol=BTCUSDT" \
    -H "Authorization: Bearer $TOKEN")

echo "Response: $DASHBOARD_RESPONSE"
echo ""

# 汇总
echo "╔══════════════════════════════════════════════════════════╗"
echo "║                    Test Summary                          ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""
echo -e "${GREEN}✓ Registration API: Tested${NC}"
echo -e "${GREEN}✓ Login API: Tested${NC}"
echo -e "${GREEN}✓ Get User API: Tested${NC}"
echo -e "${GREEN}✓ Get Account API: Tested${NC}"
echo ""
echo "Token for manual testing:"
echo "  $TOKEN"
echo ""
echo "Example curl commands:"
echo ""
echo "# Get user info:"
echo "curl -H \"Authorization: Bearer $TOKEN\" $BASE_URL/api/v1/user/me"
echo ""
echo "# Get account info:"
echo "curl -H \"Authorization: Bearer $TOKEN\" $BASE_URL/api/v1/user/account"
echo ""
echo "# Get trading dashboard:"
echo "curl -H \"Authorization: Bearer $TOKEN\" \"$BASE_URL/api/v1/trading/dashboard?symbol=BTCUSDT\""
echo ""
