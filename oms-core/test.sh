#!/bin/bash

# OMS核心服务快速测试脚本

BASE_URL="http://localhost:8081"
USER_ID=1001
TRACE_ID=$(uuidgen)

echo "========================================"
echo "  OMS Core Service Test"
echo "========================================"
echo ""

# 健康检查
echo "[1] Health Check"
curl -s -X GET "$BASE_URL/internal/oms/health"
echo ""
echo ""

# 提交订单
echo "[2] Submit Order (BTCUSDT BUY LIMIT)"
CLIENT_ORDER_ID="C$(date +%s)"
SUBMIT_RESULT=$(curl -s -X POST "$BASE_URL/api/v1/oms/order/submit" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: $USER_ID" \
  -H "X-Trace-Id: $TRACE_ID" \
  -H "X-Request-Id: REQ-$(uuidgen)" \
  -d "{
    \"clientOrderId\": \"$CLIENT_ORDER_ID\",
    \"symbol\": \"BTCUSDT\",
    \"side\": \"BUY\",
    \"type\": \"LIMIT\",
    \"price\": \"43000.5\",
    \"quantity\": \"0.1\",
    \"timeInForce\": \"GTC\"
  }")
echo "$SUBMIT_RESULT" | jq .
echo ""

# 提取orderId
ORDER_ID=$(echo "$SUBMIT_RESULT" | jq -r '.orderId')
echo "Order ID: $ORDER_ID"
echo ""

# 查询订单
echo "[3] Query Order"
curl -s -X GET "$BASE_URL/api/v1/oms/order/query?orderId=$ORDER_ID" \
  -H "X-User-Id: $USER_ID" | jq .
echo ""

# 撤单
echo "[4] Cancel Order"
curl -s -X POST "$BASE_URL/api/v1/oms/order/cancel" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: $USER_ID" \
  -H "X-Trace-Id: $TRACE_ID" \
  -H "X-Request-Id: REQ-$(uuidgen)" \
  -d "{
    \"orderId\": \"$ORDER_ID\",
    \"clientOrderId\": \"$CLIENT_ORDER_ID\"
  }" | jq .
echo ""

# 再次查询订单
echo "[5] Query Order After Cancel"
curl -s -X GET "$BASE_URL/api/v1/oms/order/query?orderId=$ORDER_ID" \
  -H "X-User-Id: $USER_ID" | jq .
echo ""

# 幂等测试
echo "[6] Idempotency Test (Submit Same Order Again)"
curl -s -X POST "$BASE_URL/api/v1/oms/order/submit" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: $USER_ID" \
  -H "X-Trace-Id: $TRACE_ID" \
  -H "X-Request-Id: REQ-$(uuidgen)" \
  -d "{
    \"clientOrderId\": \"$CLIENT_ORDER_ID\",
    \"symbol\": \"BTCUSDT\",
    \"side\": \"BUY\",
    \"type\": \"LIMIT\",
    \"price\": \"43000.5\",
    \"quantity\": \"0.1\",
    \"timeInForce\": \"GTC\"
  }" | jq .
echo ""

echo "========================================"
echo "  Test Completed"
echo "========================================"






