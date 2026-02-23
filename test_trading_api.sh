#!/bin/bash
# 合约交易系统 API 测试脚本
# 使用方法: ./test_trading_api.sh

set -e

# 配置
API_GATEWAY="http://localhost:8082"
USERNAME="zhoufan5"
PASSWORD="123456"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 存储变量
TOKEN=""
USER_ID=""
ORDER_ID=""

echo -e "${BLUE}==============================================${NC}"
echo -e "${BLUE}  合约交易系统 API 测试${NC}"
echo -e "${BLUE}==============================================${NC}"
echo ""

# 1. 登录
echo -e "${YELLOW}[1/8] 用户登录${NC}"
LOGIN_RESPONSE=$(curl -s -X POST "${API_GATEWAY}/api/v1/user/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}")

echo "响应: $LOGIN_RESPONSE"

# 提取 token 和 userId
TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
USER_ID=$(echo "$LOGIN_RESPONSE" | grep -o '"userId":[0-9]*' | grep -o '[0-9]*')

if [ -z "$TOKEN" ]; then
    # 尝试其他格式
    TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
    USER_ID=$(echo "$LOGIN_RESPONSE" | grep -o '"id":[0-9]*' | grep -o '[0-9]*')
fi

if [ -z "$TOKEN" ]; then
    echo -e "${RED}❌ 登录失败${NC}"
    exit 1
fi

echo -e "${GREEN}✅ 登录成功${NC}"
echo "   User ID: $USER_ID"
echo "   Token: ${TOKEN:0:20}..."
echo ""

# 2. 查询余额
echo -e "${YELLOW}[2/8] 查询账户余额${NC}"
BALANCE_RESPONSE=$(curl -s -X GET "${API_GATEWAY}/api/v1/account/balance/${USER_ID}" \
    -H "Authorization: Bearer ${TOKEN}")
echo "响应: $BALANCE_RESPONSE"

AVAILABLE=$(echo "$BALANCE_RESPONSE" | grep -o '"available":[0-9.]*' | cut -d':' -f2)
echo -e "${GREEN}✅ 可用余额: ${AVAILABLE:-0} USDT${NC}"
echo ""

# 3. 查询盘口
echo -e "${YELLOW}[3/8] 查询盘口深度${NC}"
DEPTH_RESPONSE=$(curl -s -X GET "${API_GATEWAY}/api/v1/match/orderbook/depth/BTCUSDT?depth=5")
echo "响应: $DEPTH_RESPONSE"

BEST_BID=$(echo "$DEPTH_RESPONSE" | grep -o '"bids":\[\[[0-9]*' | grep -o '[0-9]*' | head -1)
if [ -n "$BEST_BID" ] && [ "$BEST_BID" -gt 100000000 ]; then
    BEST_BID=$(echo "scale=2; $BEST_BID / 100000000" | bc)
fi
echo -e "${GREEN}✅ 买一价: ${BEST_BID:-未知}${NC}"
echo ""

# 4. 提交买单
echo -e "${YELLOW}[4/8] 提交限价买单${NC}"
PRICE=${BEST_BID:-50000}
if [ -n "$BEST_BID" ]; then
    PRICE=$(echo "$BEST_BID - 500" | bc)
fi

PRICE_INT=$(echo "($PRICE * 100000000) / 1" | bc)
QTY_INT=1000000  # 0.01 BTC

echo "下单参数:"
echo "  Symbol: BTCUSDT"
echo "  Side: BUY"
echo "  Type: LIMIT"
echo "  Price: $PRICE ($PRICE_INT)"
echo "  Quantity: 0.01 ($QTY_INT)"

ORDER_RESPONSE=$(curl -s -X POST "${API_GATEWAY}/api/order/create" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "X-Idempotency-Key: idem_$(date +%s%N)" \
    -d "{
        \"symbol\":\"BTCUSDT\",
        \"side\":\"BUY\",
        \"type\":\"LIMIT\",
        \"price\":\"${PRICE_INT}\",
        \"quantity\":\"${QTY_INT}\",
        \"leverage\":10,
        \"timeInForce\":\"GTC\",
        \"clientOrderId\":\"test_$(date +%s)\"
    }")

echo "响应: $ORDER_RESPONSE"
ORDER_ID=$(echo "$ORDER_RESPONSE" | grep -o '"orderId":"[^"]*"' | cut -d'"' -f4)

if [ -z "$ORDER_ID" ]; then
    ORDER_ID=$(echo "$ORDER_RESPONSE" | grep -o '"orderId":[0-9]*' | grep -o '[0-9]*')
fi

if [ -n "$ORDER_ID" ]; then
    echo -e "${GREEN}✅ 买单提交成功, Order ID: $ORDER_ID${NC}"
else
    echo -e "${RED}❌ 买单提交失败${NC}"
fi
echo ""

# 5. 查询当前订单
echo -e "${YELLOW}[5/8] 查询当前委托${NC}"
sleep 1
ORDERS_RESPONSE=$(curl -s -X GET "${API_GATEWAY}/api/v1/oms/order/list?limit=10&status=NEW,PENDING_RISK,FROZEN,PARTIALLY_FILLED" \
    -H "Authorization: Bearer ${TOKEN}")
echo "响应: $ORDERS_RESPONSE"

ORDER_COUNT=$(echo "$ORDERS_RESPONSE" | grep -o '"orderId"' | wc -l)
echo -e "${GREEN}✅ 当前委托数量: $ORDER_COUNT${NC}"
echo ""

# 6. 提交卖单（尝试撮合）
echo -e "${YELLOW}[6/8] 提交限价卖单（对手方）${NC}"
SELL_PRICE=$(echo "$PRICE + 100" | bc)
SELL_PRICE_INT=$(echo "($SELL_PRICE * 100000000) / 1" | bc)

echo "下单参数:"
echo "  Symbol: BTCUSDT"
echo "  Side: SELL"
echo "  Type: LIMIT"
echo "  Price: $SELL_PRICE ($SELL_PRICE_INT)"
echo "  Quantity: 0.01 ($QTY_INT)"

SELL_ORDER_RESPONSE=$(curl -s -X POST "${API_GATEWAY}/api/order/create" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "X-Idempotency-Key: idem_$(date +%s%N)" \
    -d "{
        \"symbol\":\"BTCUSDT\",
        \"side\":\"SELL\",
        \"type\":\"LIMIT\",
        \"price\":\"${SELL_PRICE_INT}\",
        \"quantity\":\"${QTY_INT}\",
        \"leverage\":10,
        \"timeInForce\":\"GTC\",
        \"clientOrderId\":\"test_sell_$(date +%s)\"
    }")

echo "响应: $SELL_ORDER_RESPONSE"
SELL_ORDER_ID=$(echo "$SELL_ORDER_RESPONSE" | grep -o '"orderId":"[^"]*"' | cut -d'"' -f4)

if [ -z "$SELL_ORDER_ID" ]; then
    SELL_ORDER_ID=$(echo "$SELL_ORDER_RESPONSE" | grep -o '"orderId":[0-9]*' | grep -o '[0-9]*')
fi

if [ -n "$SELL_ORDER_ID" ]; then
    echo -e "${GREEN}✅ 卖单提交成功, Order ID: $SELL_ORDER_ID${NC}"
else
    echo -e "${RED}❌ 卖单提交失败${NC}"
fi
echo ""

# 7. 等待并查询持仓
echo -e "${YELLOW}[7/8] 查询持仓${NC}"
echo "等待 3 秒让撮合完成..."
sleep 3

POSITION_RESPONSE=$(curl -s -X GET "${API_GATEWAY}/api/v1/position/list?userId=${USER_ID}" \
    -H "Authorization: Bearer ${TOKEN}")
echo "响应: $POSITION_RESPONSE"

POS_COUNT=$(echo "$POSITION_RESPONSE" | grep -o '"symbol"' | wc -l)
echo -e "${GREEN}✅ 持仓数量: $POS_COUNT${NC}"
echo ""

# 8. 查询历史订单
echo -e "${YELLOW}[8/8] 查询历史委托${NC}"
HISTORY_RESPONSE=$(curl -s -X GET "${API_GATEWAY}/api/v1/oms/order/list?limit=10&status=FILLED,CANCELED" \
    -H "Authorization: Bearer ${TOKEN}")
echo "响应: $HISTORY_RESPONSE"

HISTORY_COUNT=$(echo "$HISTORY_RESPONSE" | grep -o '"orderId"' | wc -l)
echo -e "${GREEN}✅ 历史委托数量: $HISTORY_COUNT${NC}"
echo ""

# 总结
echo -e "${BLUE}==============================================${NC}"
echo -e "${BLUE}  测试完成${NC}"
echo -e "${BLUE}==============================================${NC}"
echo ""
echo "测试订单:"
echo "  买单: $ORDER_ID"
echo "  卖单: $SELL_ORDER_ID"
echo ""
echo "订单统计:"
echo "  当前委托: $ORDER_COUNT"
echo "  历史委托: $HISTORY_COUNT"
echo "  持仓: $POS_COUNT"
echo ""

if [ "$POS_COUNT" -gt 0 ]; then
    echo -e "${GREEN}🎉 测试成功！检测到持仓，说明订单已成交${NC}"
else
    echo -e "${YELLOW}⚠️ 未检测到持仓，可能订单未成交（需要对手方账户）${NC}"
fi
