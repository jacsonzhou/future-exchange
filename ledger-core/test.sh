#!/bin/bash

# Ledger Core 测试脚本

set -e

BASE_URL="http://localhost:8084/internal/ledger"

echo "========== Ledger Core 功能测试 =========="

# 1. 查询账户快照（用户1001）
echo ""
echo "[测试1] 查询账户快照"
echo "请求: GET ${BASE_URL}/account/1001"
curl -s -X GET "${BASE_URL}/account/1001" | jq .

# 2. 冻结保证金
echo ""
echo "[测试2] 冻结保证金"
echo "请求: POST ${BASE_URL}/freeze"
curl -s -X POST "${BASE_URL}/freeze" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1001,
    "currency": "USDT",
    "amount": "1000.00",
    "orderId": 10001
  }' | jq .

# 3. 解冻保证金
echo ""
echo "[测试3] 解冻保证金"
echo "请求: POST ${BASE_URL}/unfreeze"
curl -s -X POST "${BASE_URL}/unfreeze" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1001,
    "currency": "USDT",
    "amount": "500.00",
    "orderId": 10001
  }' | jq .

# 4. 触发Replay
echo ""
echo "[测试4] 触发全量Replay"
echo "请求: POST ${BASE_URL}/replay/all"
REPLAY_ID=$(curl -s -X POST "${BASE_URL}/replay/all" \
  -H "Content-Type: application/json" \
  -d '{
    "startBizSeq": 0,
    "endBizSeq": -1
  }')
echo "Replay任务ID: ${REPLAY_ID}"

# 5. 查询Replay进度
echo ""
echo "[测试5] 查询Replay进度"
sleep 2
curl -s -X GET "${BASE_URL}/replay/progress/${REPLAY_ID}"

echo ""
echo "========== 测试完成 =========="



