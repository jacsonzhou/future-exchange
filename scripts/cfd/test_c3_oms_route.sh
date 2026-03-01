#!/usr/bin/env bash
set -euo pipefail

# C3 验收：
# 1) executionMode=CFD_DEALER 下单后，命中 cfd-order-command-{symbol}
# 2) 同一 orderId 不应出现在 order-event-{symbol}
# 3) 撤单后，命中 cfd-order-command-{symbol} 的 CFD_CANCEL
# 4) 同一 orderId 的 ORDER_CANCEL 不应出现在 order-event-{symbol}

OMS_BASE_URL="${OMS_BASE_URL:-http://127.0.0.1:8081}"
SYMBOL="${SYMBOL:-BTCUSDT}"
USER_ID="${USER_ID:-18}"
LEVERAGE="${LEVERAGE:-10}"
PRICE="${PRICE:-67000.0}"
QUANTITY="${QUANTITY:-0.001}"
TIME_IN_FORCE="${TIME_IN_FORCE:-GTC}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka-1}"
BOOTSTRAP="${BOOTSTRAP:-localhost:9092}"
CONSUME_TIMEOUT_MS="${CONSUME_TIMEOUT_MS:-10000}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[FAIL] missing command: $1"
    exit 1
  fi
}

require_cmd curl
require_cmd jq
require_cmd docker

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
  local max_messages="${3:-50}"

  docker exec "${KAFKA_CONTAINER}" kafka-console-consumer \
    --bootstrap-server "${BOOTSTRAP}" \
    --topic "${topic}" \
    --partition 0 \
    --offset "${offset}" \
    --timeout-ms "${CONSUME_TIMEOUT_MS}" \
    --max-messages "${max_messages}" 2>/dev/null || true
}

CLIENT_ORDER_ID="c3-$(date +%s)-$RANDOM"
CFD_TOPIC="cfd-order-command-${SYMBOL}"
MATCH_TOPIC="order-event-${SYMBOL}"

pre_submit_cfd_offset="$(get_latest_offset "${CFD_TOPIC}")"
pre_submit_match_offset="$(get_latest_offset "${MATCH_TOPIC}")"
echo "[C3] pre-submit offsets: ${CFD_TOPIC}=${pre_submit_cfd_offset}, ${MATCH_TOPIC}=${pre_submit_match_offset}"

echo "[C3] submit order, clientOrderId=${CLIENT_ORDER_ID}"
submit_payload="$(cat <<JSON
{
  "clientOrderId":"${CLIENT_ORDER_ID}",
  "symbol":"${SYMBOL}",
  "side":"BUY",
  "type":"LIMIT",
  "price":"${PRICE}",
  "quantity":"${QUANTITY}",
  "timeInForce":"${TIME_IN_FORCE}",
  "leverage":${LEVERAGE},
  "executionMode":"CFD_DEALER"
}
JSON
)"

submit_resp="$(curl -sS -X POST "${OMS_BASE_URL}/api/v1/oms/order/submit" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: ${USER_ID}" \
  -H "X-Trace-Id: c3-route-test" \
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

echo "[C3] submit success, orderId=${order_id}"
sleep 1

echo "[C3] verify submit routed to ${CFD_TOPIC}"
cfd_submit_hit="$(consume_from_offset "${CFD_TOPIC}" "${pre_submit_cfd_offset}" 50 \
  | jq -c --arg oid "${order_id}" 'select((.orderId|tostring)==$oid and .eventType=="CFD_ORDER_SUBMIT")' \
  | head -n 1 || true)"

if [[ -z "${cfd_submit_hit}" ]]; then
  echo "[FAIL] missing CFD_ORDER_SUBMIT in ${CFD_TOPIC}, orderId=${order_id}"
  exit 1
fi
echo "[PASS] cfd submit hit: ${cfd_submit_hit}"

echo "[C3] verify submit not routed to ${MATCH_TOPIC}"
match_submit_hit="$(consume_from_offset "${MATCH_TOPIC}" "${pre_submit_match_offset}" 80 \
  | jq -c --arg oid "${order_id}" 'select((.orderId|tostring)==$oid)' \
  | head -n 1 || true)"

if [[ -n "${match_submit_hit}" ]]; then
  echo "[FAIL] unexpected order-event hit for CFD order: ${match_submit_hit}"
  exit 1
fi
echo "[PASS] no order-event submit hit"

pre_cancel_cfd_offset="$(get_latest_offset "${CFD_TOPIC}")"
pre_cancel_match_offset="$(get_latest_offset "${MATCH_TOPIC}")"
echo "[C3] pre-cancel offsets: ${CFD_TOPIC}=${pre_cancel_cfd_offset}, ${MATCH_TOPIC}=${pre_cancel_match_offset}"

echo "[C3] cancel order, orderId=${order_id}"
cancel_payload="{\"orderId\":\"${order_id}\"}"
cancel_resp="$(curl -sS -X POST "${OMS_BASE_URL}/api/v1/oms/order/cancel" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: ${USER_ID}" \
  -H "X-Trace-Id: c3-route-test-cancel" \
  -d "${cancel_payload}")"

cancel_ok="$(echo "${cancel_resp}" | jq -r '.success // false')"
if [[ "${cancel_ok}" != "true" ]]; then
  echo "[FAIL] cancel failed: ${cancel_resp}"
  exit 1
fi
echo "[C3] cancel success"
sleep 1

echo "[C3] verify cancel routed to ${CFD_TOPIC}"
cfd_cancel_hit="$(consume_from_offset "${CFD_TOPIC}" "${pre_cancel_cfd_offset}" 50 \
  | jq -c --arg oid "${order_id}" 'select((.orderId|tostring)==$oid and .eventType=="CFD_CANCEL")' \
  | head -n 1 || true)"

if [[ -z "${cfd_cancel_hit}" ]]; then
  echo "[FAIL] missing CFD_CANCEL in ${CFD_TOPIC}, orderId=${order_id}"
  exit 1
fi
echo "[PASS] cfd cancel hit: ${cfd_cancel_hit}"

echo "[C3] verify cancel not routed to ${MATCH_TOPIC}"
match_cancel_hit="$(consume_from_offset "${MATCH_TOPIC}" "${pre_cancel_match_offset}" 80 \
  | jq -c --arg oid "${order_id}" 'select((.orderId|tostring)==$oid and .eventType=="ORDER_CANCEL")' \
  | head -n 1 || true)"

if [[ -n "${match_cancel_hit}" ]]; then
  echo "[FAIL] unexpected ORDER_CANCEL in ${MATCH_TOPIC}: ${match_cancel_hit}"
  exit 1
fi
echo "[PASS] no order-event cancel hit"

echo "[C3] PASS"
