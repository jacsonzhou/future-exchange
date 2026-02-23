#!/bin/bash

# Hard Risk Gate 快速测试脚本

BASE_URL="http://localhost:8082"
USER_ID=1001
TRACE_ID=$(uuidgen)

echo "========================================"
echo "  Hard Risk Gate Test"
echo "========================================"
echo ""

# 健康检查
echo "[1] Health Check"
curl -s -X GET "$BASE_URL/internal/risk/health"
echo ""
echo ""

# 测试1: 正常订单（应该PASS）
echo "[2] Test PASS - Normal Order"
curl -s -X POST "$BASE_URL/api/v1/risk/check" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: $USER_ID" \
  -H "X-Trace-Id: $TRACE_ID" \
  -H "X-Request-Id: REQ-$(uuidgen)" \
  -d "{
    \"orderId\": \"1001\",
    \"symbol\": \"BTCUSDT\",
    \"side\": \"BUY\",
    \"price\": \"43000\",
    \"quantity\": \"0.01\",
    \"leverage\": 10,
    \"reduceOnly\": false
  }" | jq .
echo ""

# 测试2: 保证金不足（应该REJECT）
echo "[3] Test REJECT - Insufficient Margin"
curl -s -X POST "$BASE_URL/api/v1/risk/check" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: $USER_ID" \
  -H "X-Trace-Id: $TRACE_ID" \
  -H "X-Request-Id: REQ-$(uuidgen)" \
  -d "{
    \"orderId\": \"1002\",
    \"symbol\": \"BTCUSDT\",
    \"side\": \"BUY\",
    \"price\": \"43000\",
    \"quantity\": \"1000\",
    \"leverage\": 1,
    \"reduceOnly\": false
  }" | jq .
echo ""

# 测试3: 杠杆超限（应该REJECT）
echo "[4] Test REJECT - Leverage Exceeded"
curl -s -X POST "$BASE_URL/api/v1/risk/check" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: $USER_ID" \
  -H "X-Trace-Id: $TRACE_ID" \
  -H "X-Request-Id: REQ-$(uuidgen)" \
  -d "{
    \"orderId\": \"1003\",
    \"symbol\": \"BTCUSDT\",
    \"side\": \"BUY\",
    \"price\": \"43000\",
    \"quantity\": \"0.1\",
    \"leverage\": 200,
    \"reduceOnly\": false
  }" | jq .
echo ""

# 测试4: ReduceOnly违规（应该REJECT）
echo "[5] Test REJECT - ReduceOnly Violation"
curl -s -X POST "$BASE_URL/api/v1/risk/check" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: $USER_ID" \
  -H "X-Trace-Id: $TRACE_ID" \
  -H "X-Request-Id: REQ-$(uuidgen)" \
  -d "{
    \"orderId\": \"1004\",
    \"symbol\": \"ETHUSDT\",
    \"side\": \"BUY\",
    \"price\": \"2200\",
    \"quantity\": \"1\",
    \"leverage\": 10,
    \"reduceOnly\": true
  }" | jq .
echo ""

echo "========================================"
echo "  Test Completed"
echo ""
echo "Note: 测试结果取决于数据库中的账户/持仓快照数据"
echo "========================================"

