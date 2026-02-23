#!/bin/bash

# Match Engine Core 快速测试脚本

BASE_URL="http://localhost:8083"

echo "========================================"
echo "  Match Engine Core Test"
echo "========================================"
echo ""

# 健康检查
echo "[1] Health Check"
curl -s -X GET "$BASE_URL/internal/match/health"
echo ""
echo ""

# 获取订单簿统计
echo "[2] Get OrderBook Stats"
curl -s -X GET "$BASE_URL/api/v1/match/orderbook/stats" | jq .
echo ""

# 提交买单1（限价单）
echo "[3] Submit Buy Order 1 (LIMIT, price=43000, qty=0.1)"
curl -s -X POST "$BASE_URL/api/v1/match/order/submit" \
  -H "Content-Type: application/json" \
  -d "{
    \"orderId\": 10001,
    \"userId\": 1001,
    \"symbol\": \"BTCUSDT\",
    \"side\": \"BUY\",
    \"orderType\": \"LIMIT\",
    \"price\": \"43000\",
    \"quantity\": \"0.1\"
  }" | jq .
echo ""

# 提交买单2
echo "[4] Submit Buy Order 2 (LIMIT, price=42900, qty=0.2)"
curl -s -X POST "$BASE_URL/api/v1/match/order/submit" \
  -H "Content-Type: application/json" \
  -d "{
    \"orderId\": 10002,
    \"userId\": 1002,
    \"symbol\": \"BTCUSDT\",
    \"side\": \"BUY\",
    \"orderType\": \"LIMIT\",
    \"price\": \"42900\",
    \"quantity\": \"0.2\"
  }" | jq .
echo ""

# 提交卖单1（限价单）
echo "[5] Submit Sell Order 1 (LIMIT, price=43100, qty=0.15)"
curl -s -X POST "$BASE_URL/api/v1/match/order/submit" \
  -H "Content-Type: application/json" \
  -d "{
    \"orderId\": 20001,
    \"userId\": 2001,
    \"symbol\": \"BTCUSDT\",
    \"side\": \"SELL\",
    \"orderType\": \"LIMIT\",
    \"price\": \"43100\",
    \"quantity\": \"0.15\"
  }" | jq .
echo ""

# 查看最优买卖价
echo "[6] Get Best Bid/Ask Price"
curl -s -X GET "$BASE_URL/api/v1/match/orderbook/best-price" | jq .
echo ""

# 提交卖单2（市价单，触发撮合）
echo "[7] Submit Sell Order 2 (MARKET, qty=0.1) - Will Match!"
curl -s -X POST "$BASE_URL/api/v1/match/order/submit" \
  -H "Content-Type: application/json" \
  -d "{
    \"orderId\": 20002,
    \"userId\": 2002,
    \"symbol\": \"BTCUSDT\",
    \"side\": \"SELL\",
    \"orderType\": \"MARKET\",
    \"quantity\": \"0.1\"
  }" | jq .
echo ""

# 再次查看订单簿统计
echo "[8] Get OrderBook Stats After Match"
curl -s -X GET "$BASE_URL/api/v1/match/orderbook/stats" | jq .
echo ""

# 撤单测试
echo "[9] Cancel Order (orderId=20001)"
curl -s -X POST "$BASE_URL/api/v1/match/order/cancel?orderId=20001&symbol=BTCUSDT" | jq .
echo ""

# 最终统计
echo "[10] Final OrderBook Stats"
curl -s -X GET "$BASE_URL/api/v1/match/orderbook/stats" | jq .
echo ""

echo "========================================"
echo "  Test Completed"
echo ""
echo "Check logs for trade events!"
echo "========================================"



