#!/usr/bin/env bash
set -euo pipefail

# C4 验收：
# 1) MARKET + CFD_DEALER 下单后，order-state-{symbol} 出现 FILLED
# 2) trade-event-{symbol} 出现对应 trade 事件
# 3) FILLED 时延满足阈值（默认 500ms）

OMS_BASE_URL="${OMS_BASE_URL:-http://127.0.0.1:8081}"
SYMBOL="${SYMBOL:-BTCUSDT}"
USER_ID="${USER_ID:-18}"
LEVERAGE="${LEVERAGE:-10}"
QUANTITY="${QUANTITY:-0.001}"
TIME_IN_FORCE="${TIME_IN_FORCE:-IOC}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka-1}"
BOOTSTRAP="${BOOTSTRAP:-localhost:9092}"
CONSUME_TIMEOUT_MS="${CONSUME_TIMEOUT_MS:-15000}"
MAX_FILLED_LATENCY_MS="${MAX_FILLED_LATENCY_MS:-500}"
TRADE_TOPIC="${TRADE_TOPIC:-trade-event-${SYMBOL}}"
ORDER_STATE_TOPIC="${ORDER_STATE_TOPIC:-order-state-${SYMBOL}}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[FAIL] missing command: $1"
    exit 1
  fi
}

require_cmd curl
require_cmd jq
require_cmd docker
require_cmd awk
require_cmd python3

now_ms() {
  python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
}

get_latest_offset() {
  local topic="$1"
  local raw
  local latest

  raw="$(docker exec "${KAFKA_CONTAINER}" kafka-run-class kafka.tools.GetOffsetShell \
    --broker-list "${BOOTSTRAP}" \
    --topic "${topic}" \
    --time -1 2>/dev/null || true)"

  latest="$(echo "${raw}" | awk -F: 'BEGIN{max=0} NF>=3 {if (($3+0) > max) max=($3+0)} END{print max}')"
  if [[ -z "${latest}" ]]; then
    echo "0"
    return
  fi
  echo "${latest}"
}

consume_from_offset() {
  local topic="$1"
  local offset="$2"
  local max_messages="${3:-80}"

  docker exec "${KAFKA_CONTAINER}" kafka-console-consumer \
    --bootstrap-server "${BOOTSTRAP}" \
    --topic "${topic}" \
    --partition 0 \
    --offset "${offset}" \
    --timeout-ms "${CONSUME_TIMEOUT_MS}" \
    --max-messages "${max_messages}" 2>/dev/null || true
}

CLIENT_ORDER_ID="c4-$(date +%s)-$RANDOM"
pre_state_offset="$(get_latest_offset "${ORDER_STATE_TOPIC}")"
pre_trade_offset="$(get_latest_offset "${TRADE_TOPIC}")"

echo "[C4] pre-submit offsets: ${ORDER_STATE_TOPIC}=${pre_state_offset}, ${TRADE_TOPIC}=${pre_trade_offset}"

submit_req_ts="$(now_ms)"
submit_payload="$(cat <<JSON
{
  "clientOrderId":"${CLIENT_ORDER_ID}",
  "symbol":"${SYMBOL}",
  "side":"BUY",
  "type":"MARKET",
  "quantity":"${QUANTITY}",
  "timeInForce":"${TIME_IN_FORCE}",
  "leverage":${LEVERAGE},
  "executionMode":"CFD_DEALER"
}
JSON
)"

echo "[C4] submit market order, clientOrderId=${CLIENT_ORDER_ID}"
submit_resp="$(curl -sS -X POST "${OMS_BASE_URL}/api/v1/oms/order/submit" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: ${USER_ID}" \
  -H "X-Trace-Id: c4-market-fill" \
  -d "${submit_payload}")"

submit_ok="$(echo "${submit_resp}" | jq -r '.success // false')"
if [[ "${submit_ok}" != "true" ]]; then
  echo "[FAIL] submit failed: ${submit_resp}"
  exit 1
fi

order_id="$(echo "${submit_resp}" | jq -r '.orderId')"
if [[ -z "${order_id}" || "${order_id}" == "null" ]]; then
  echo "[FAIL] submit response missing orderId: ${submit_resp}"
  exit 1
fi

submit_ack_ts="$(now_ms)"
echo "[C4] submit success, orderId=${order_id}"

state_hit="$(consume_from_offset "${ORDER_STATE_TOPIC}" "${pre_state_offset}" 120 \
  | jq -c --arg oid "${order_id}" 'select((.orderId|tostring)==$oid and .status=="FILLED")' \
  | head -n 1 || true)"

if [[ -z "${state_hit}" ]]; then
  echo "[FAIL] missing FILLED order-state for orderId=${order_id}, topic=${ORDER_STATE_TOPIC}"
  exit 1
fi

event_time="$(echo "${state_hit}" | jq -r '.eventTime // .tradeTime // 0')"
if [[ ! "${event_time}" =~ ^[0-9]+$ ]]; then
  event_time="0"
fi
if (( event_time <= 0 )); then
  event_time="$(now_ms)"
fi

filled_latency_ms=$((event_time - submit_ack_ts))
if (( filled_latency_ms < 0 )); then
  filled_latency_ms=0
fi

echo "[PASS] FILLED state hit: ${state_hit}"
echo "[C4] filled latency (submit_ack->FILLED) = ${filled_latency_ms}ms, submit_req_ts=${submit_req_ts}, submit_ack_ts=${submit_ack_ts}, event_time=${event_time}"

if (( filled_latency_ms > MAX_FILLED_LATENCY_MS )); then
  echo "[FAIL] FILLED latency too high: ${filled_latency_ms}ms > ${MAX_FILLED_LATENCY_MS}ms"
  exit 1
fi

trade_hit="$(consume_from_offset "${TRADE_TOPIC}" "${pre_trade_offset}" 120 \
  | jq -c --arg oid "${order_id}" 'select((.eventType=="TRADE") and (((.takerOrderId|tostring)==$oid) or ((.makerOrderId|tostring)==$oid)))' \
  | head -n 1 || true)"

if [[ -z "${trade_hit}" ]]; then
  echo "[FAIL] missing trade-event for orderId=${order_id}, topic=${TRADE_TOPIC}"
  exit 1
fi

echo "[PASS] trade-event hit: ${trade_hit}"

echo "[C4] PASS"
