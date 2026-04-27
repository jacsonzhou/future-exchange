#!/bin/bash

# API Gateway 测试脚本

echo "================================"
echo "API Gateway 功能测试"
echo "================================"

BASE_URL="http://localhost:8080"

# 生成UUID（用于幂等Key）
generate_uuid() {
    if command -v uuidgen &> /dev/null; then
        uuidgen | tr '[:upper:]' '[:lower:]' | tr -d '-'
    else
        cat /proc/sys/kernel/random/uuid | tr -d '-'
    fi
}

# 测试1: 健康检查
echo ""
echo "测试1: 健康检查"
echo "-------------------------------"
curl -s $BASE_URL/api/order/health
echo ""

# 测试2: 创建订单（带认证）
echo ""
echo "测试2: 创建订单"
echo "-------------------------------"
IDEMPOTENCY_KEY=$(generate_uuid)
echo "Idempotency Key: $IDEMPOTENCY_KEY"

curl -X POST $BASE_URL/api/order/create \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test-token-12345" \
  -H "X-Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d '{
    "userId": 1,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": 5000000000000,
    "quantity": 100000000,
    "leverage": 10
  }' | jq .

echo ""

# 测试3: 幂等性测试（重复请求）
echo ""
echo "测试3: 幂等性测试（使用相同的Key）"
echo "-------------------------------"
sleep 1

curl -X POST $BASE_URL/api/order/create \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test-token-12345" \
  -H "X-Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d '{
    "userId": 1,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": 5000000000000,
    "quantity": 100000000,
    "leverage": 10
  }' | jq .

echo ""

# 测试4: 缺少幂等Key
echo ""
echo "测试4: 缺少幂等Key（应该报错）"
echo "-------------------------------"

curl -X POST $BASE_URL/api/order/create \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test-token-12345" \
  -d '{
    "userId": 1,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": 5000000000000,
    "quantity": 100000000,
    "leverage": 10
  }' | jq .

echo ""

# 测试5: 限流测试
echo ""
echo "测试5: 限流测试（快速发送20个请求）"
echo "-------------------------------"
SUCCESS_COUNT=0
RATE_LIMITED_COUNT=0

for i in {1..20}; do
    KEY=$(generate_uuid)
    RESPONSE=$(curl -s -w "%{http_code}" -o /tmp/response.txt -X POST $BASE_URL/api/order/create \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer test-token-12345" \
      -H "X-Idempotency-Key: $KEY" \
      -d '{
        "userId": 1,
        "symbol": "BTCUSDT",
        "side": "BUY",
        "orderType": "LIMIT",
        "price": 5000000000000,
        "quantity": 100000000,
        "leverage": 10
      }')
    
    if [ "$RESPONSE" = "200" ]; then
        SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
    elif [ "$RESPONSE" = "429" ]; then
        RATE_LIMITED_COUNT=$((RATE_LIMITED_COUNT + 1))
    fi
done

echo "成功: $SUCCESS_COUNT 个"
echo "限流: $RATE_LIMITED_COUNT 个"
echo ""

# 测试6: 撤单
echo ""
echo "测试6: 撤单"
echo "-------------------------------"
CANCEL_KEY=$(generate_uuid)

curl -X POST $BASE_URL/api/order/cancel \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test-token-12345" \
  -H "X-Idempotency-Key: $CANCEL_KEY" \
  -d '{
    "userId": 1,
    "orderId": 123456789,
    "symbol": "BTCUSDT"
  }' | jq .

echo ""

# 测试7: 无认证（应该401）
echo ""
echo "测试7: 无认证（应该返回401）"
echo "-------------------------------"

curl -X POST $BASE_URL/api/order/create \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "price": 5000000000000,
    "quantity": 100000000,
    "leverage": 10
  }' | jq .

echo ""
echo "================================"
echo "测试完成"
echo "================================"







