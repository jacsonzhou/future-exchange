#!/usr/bin/env bash
set -euo pipefail

# C5 验收：
# 1) 不可立即成交 LIMIT -> t_cfd_working_order=WORKING
# 2) 参考盘口推进后 WORKING -> FILLED（有 order-state FILLED + trade-event）
# 3) 撤单路径 WORKING -> CANCELED（有 order-state CANCELED）

OMS_BASE_URL="${OMS_BASE_URL:-http://127.0.0.1:8081}"
BINANCE_BASE_URL="${BINANCE_BASE_URL:-http://127.0.0.1:8105}"
SYMBOL="${SYMBOL:-BTCUSDT}"
USER_ID="${USER_ID:-18}"
LEVERAGE="${LEVERAGE:-10}"
QUANTITY="${QUANTITY:-0.001}"
TIME_IN_FORCE="${TIME_IN_FORCE:-GTC}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-web3-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-redis-dev}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka-1}"
BOOTSTRAP="${BOOTSTRAP:-localhost:9092}"
CONSUME_TIMEOUT_MS="${CONSUME_TIMEOUT_MS:-20000}"
ORDER_STATE_TOPIC="${ORDER_STATE_TOPIC:-order-state-${SYMBOL}}"
TRADE_TOPIC="${TRADE_TOPIC:-trade-event-${SYMBOL}}"

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
  local max_messages="${3:-120}"

  docker exec "${KAFKA_CONTAINER}" kafka-console-consumer \
    --bootstrap-server "${BOOTSTRAP}" \
    --topic "${topic}" \
    --partition 0 \
    --offset "${offset}" \
    --timeout-ms "${CONSUME_TIMEOUT_MS}" \
    --max-messages "${max_messages}" 2>/dev/null || true
}

query_working_status() {
  local order_id="$1"
  docker exec "${MYSQL_CONTAINER}" mysql -uroot -proot123456 -N -e \
    "SELECT status FROM exchange_oms.t_cfd_working_order WHERE order_id=${order_id} LIMIT 1;" 2>/dev/null | tr -d '\r' | head -n1
}

wait_for_status() {
  local order_id="$1"
  local target_status="$2"
  local retries="${3:-40}"
  local interval_sec="${4:-0.2}"

  for _ in $(seq 1 "${retries}"); do
    local status
    status="$(query_working_status "${order_id}")"
    if [[ "${status}" == "${target_status}" ]]; then
      echo "${status}"
      return 0
    fi
    sleep "${interval_sec}"
  done
  return 1
}

get_reference_book() {
  curl -sS "${BINANCE_BASE_URL}/api/binance/reference-book/${SYMBOL}?depth=20"
}

submit_limit_order() {
  local client_order_id="$1"
  local price="$2"
  curl -sS -X POST "${OMS_BASE_URL}/api/v1/oms/order/submit" \
    -H "Content-Type: application/json" \
    -H "X-User-Id: ${USER_ID}" \
    -H "X-Trace-Id: c5-limit-trigger" \
    -d "{\"clientOrderId\":\"${client_order_id}\",\"symbol\":\"${SYMBOL}\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"price\":\"${price}\",\"quantity\":\"${QUANTITY}\",\"timeInForce\":\"${TIME_IN_FORCE}\",\"leverage\":${LEVERAGE},\"executionMode\":\"CFD_DEALER\"}"
}

cancel_order() {
  local order_id="$1"
  curl -sS -X POST "${OMS_BASE_URL}/api/v1/oms/order/cancel" \
    -H "Content-Type: application/json" \
    -H "X-User-Id: ${USER_ID}" \
    -H "X-Trace-Id: c5-limit-cancel" \
    -d "{\"orderId\":\"${order_id}\"}"
}

reference_resp="$(get_reference_book)"
ref_success="$(echo "${reference_resp}" | jq -r '.success // false')"
if [[ "${ref_success}" != "true" ]]; then
  echo "[FAIL] reference-book unavailable: ${reference_resp}"
  exit 1
fi

best_bid="$(echo "${reference_resp}" | jq -r '.data.bestBid')"
best_ask="$(echo "${reference_resp}" | jq -r '.data.bestAsk')"
if [[ -z "${best_bid}" || -z "${best_ask}" || "${best_bid}" == "null" || "${best_ask}" == "null" ]]; then
  echo "[FAIL] invalid best bid/ask from reference-book: ${reference_resp}"
  exit 1
fi

working_price="$(python3 - <<PY
from decimal import Decimal, ROUND_DOWN
bid = Decimal("${best_bid}")
price = (bid * Decimal("0.95")).quantize(Decimal("0.1"), rounding=ROUND_DOWN)
if price <= Decimal("0"):
    price = Decimal("1")
print(format(price, 'f'))
PY
)"

state_offset_1="$(get_latest_offset "${ORDER_STATE_TOPIC}")"
trade_offset_1="$(get_latest_offset "${TRADE_TOPIC}")"

client_order_id_1="c5-work-$(date +%s)-$RANDOM"
submit_resp_1="$(submit_limit_order "${client_order_id_1}" "${working_price}")"
submit_ok_1="$(echo "${submit_resp_1}" | jq -r '.success // false')"
if [[ "${submit_ok_1}" != "true" ]]; then
  echo "[FAIL] submit working limit failed: ${submit_resp_1}"
  exit 1
fi

order_id_1="$(echo "${submit_resp_1}" | jq -r '.orderId')"
if [[ -z "${order_id_1}" || "${order_id_1}" == "null" ]]; then
  echo "[FAIL] missing orderId for working order: ${submit_resp_1}"
  exit 1
fi

echo "[C5] working candidate order submitted, orderId=${order_id_1}, price=${working_price}"

if ! wait_for_status "${order_id_1}" "WORKING" 40 0.2 >/dev/null; then
  actual_status="$(query_working_status "${order_id_1}")"
  echo "[FAIL] expected WORKING, got status=${actual_status:-<empty>} for orderId=${order_id_1}"
  exit 1
fi

echo "[PASS] order entered WORKING, orderId=${order_id_1}"

trigger_ask="$(python3 - <<PY
from decimal import Decimal, ROUND_DOWN
limit_price = Decimal("${working_price}")
trigger = (limit_price * Decimal("0.999")).quantize(Decimal("0.1"), rounding=ROUND_DOWN)
if trigger <= Decimal("0"):
    trigger = Decimal("1")
print(format(trigger, 'f'))
PY
)"

trigger_bid="$(python3 - <<PY
from decimal import Decimal
ask = Decimal("${trigger_ask}")
bid = ask - Decimal("0.1")
if bid <= 0:
    bid = ask
print(format(bid, 'f'))
PY
)"

trigger_event_time="$(now_ms)"
trigger_offset="$((trigger_event_time * 10))"

snapshot_json="$(python3 - <<PY
import json
symbol = "${SYMBOL}"
bid = "${trigger_bid}"
ask = "${trigger_ask}"
event_time = int("${trigger_event_time}")
offset = int("${trigger_offset}")
obj = {
    "symbol": symbol,
    "eventTime": event_time,
    "topic": f"market.ext.binance.depth.{symbol}",
    "offset": offset,
    "bestBid": bid,
    "bestAsk": ask,
    "bidsTopN": [{"price": bid, "quantity": "20"}],
    "asksTopN": [{"price": ask, "quantity": "20"}],
    "source": "binance",
    "stalenessMs": 0
}
print(json.dumps(obj, separators=(",", ":")))
PY
)"

printf '%s' "${snapshot_json}" | docker exec -i "${REDIS_CONTAINER}" redis-cli -x SET "cfd:reference:book:${SYMBOL}" >/dev/null

if ! wait_for_status "${order_id_1}" "FILLED" 60 0.2 >/dev/null; then
  actual_status="$(query_working_status "${order_id_1}")"
  echo "[FAIL] expected FILLED after trigger, got status=${actual_status:-<empty>} for orderId=${order_id_1}"
  exit 1
fi

echo "[PASS] working order triggered to FILLED, orderId=${order_id_1}"

state_filled_hit="$(consume_from_offset "${ORDER_STATE_TOPIC}" "${state_offset_1}" 200 \
  | jq -c --arg oid "${order_id_1}" 'select((.orderId|tostring)==$oid and .status=="FILLED")' \
  | head -n 1 || true)"
if [[ -z "${state_filled_hit}" ]]; then
  echo "[FAIL] missing FILLED order-state for orderId=${order_id_1}"
  exit 1
fi

echo "[PASS] FILLED order-state hit: ${state_filled_hit}"

trade_hit="$(consume_from_offset "${TRADE_TOPIC}" "${trade_offset_1}" 200 \
  | jq -c --arg oid "${order_id_1}" 'select((.eventType=="TRADE") and (((.takerOrderId|tostring)==$oid) or ((.makerOrderId|tostring)==$oid)))' \
  | head -n 1 || true)"
if [[ -z "${trade_hit}" ]]; then
  echo "[FAIL] missing trade-event for orderId=${order_id_1}"
  exit 1
fi

echo "[PASS] trade-event hit: ${trade_hit}"

# 恢复为“不可立即成交”参考簿，避免 cancel-case 直接被触发成交
non_cross_ask="$(python3 - <<PY
from decimal import Decimal, ROUND_UP
limit_price = Decimal("${working_price}")
ask = (limit_price * Decimal("1.02")).quantize(Decimal("0.1"), rounding=ROUND_UP)
print(format(ask, 'f'))
PY
)"

non_cross_bid="$(python3 - <<PY
from decimal import Decimal
ask = Decimal("${non_cross_ask}")
bid = ask - Decimal("0.1")
if bid <= 0:
    bid = ask
print(format(bid, 'f'))
PY
)"

non_cross_event_time="$(now_ms)"
non_cross_offset="$((non_cross_event_time * 10))"
non_cross_snapshot_json="$(python3 - <<PY
import json
symbol = "${SYMBOL}"
bid = "${non_cross_bid}"
ask = "${non_cross_ask}"
event_time = int("${non_cross_event_time}")
offset = int("${non_cross_offset}")
obj = {
    "symbol": symbol,
    "eventTime": event_time,
    "topic": f"market.ext.binance.depth.{symbol}",
    "offset": offset,
    "bestBid": bid,
    "bestAsk": ask,
    "bidsTopN": [{"price": bid, "quantity": "20"}],
    "asksTopN": [{"price": ask, "quantity": "20"}],
    "source": "binance",
    "stalenessMs": 0
}
print(json.dumps(obj, separators=(",", ":")))
PY
)"
printf '%s' "${non_cross_snapshot_json}" | docker exec -i "${REDIS_CONTAINER}" redis-cli -x SET "cfd:reference:book:${SYMBOL}" >/dev/null

# 撤单路径
state_offset_2="$(get_latest_offset "${ORDER_STATE_TOPIC}")"

client_order_id_2="c5-cancel-$(date +%s)-$RANDOM"
submit_resp_2="$(submit_limit_order "${client_order_id_2}" "${working_price}")"
submit_ok_2="$(echo "${submit_resp_2}" | jq -r '.success // false')"
if [[ "${submit_ok_2}" != "true" ]]; then
  echo "[FAIL] submit cancel-case limit failed: ${submit_resp_2}"
  exit 1
fi

order_id_2="$(echo "${submit_resp_2}" | jq -r '.orderId')"
if [[ -z "${order_id_2}" || "${order_id_2}" == "null" ]]; then
  echo "[FAIL] missing orderId for cancel-case order: ${submit_resp_2}"
  exit 1
fi

if ! wait_for_status "${order_id_2}" "WORKING" 40 0.2 >/dev/null; then
  actual_status="$(query_working_status "${order_id_2}")"
  echo "[FAIL] cancel-case expected WORKING, got status=${actual_status:-<empty>} for orderId=${order_id_2}"
  exit 1
fi

cancel_resp="$(cancel_order "${order_id_2}")"
cancel_ok="$(echo "${cancel_resp}" | jq -r '.success // false')"
if [[ "${cancel_ok}" != "true" ]]; then
  echo "[FAIL] cancel request failed: ${cancel_resp}"
  exit 1
fi

if ! wait_for_status "${order_id_2}" "CANCELED" 40 0.2 >/dev/null; then
  actual_status="$(query_working_status "${order_id_2}")"
  echo "[FAIL] expected CANCELED after cancel, got status=${actual_status:-<empty>} for orderId=${order_id_2}"
  exit 1
fi

echo "[PASS] working order canceled, orderId=${order_id_2}"

state_cancel_hit="$(consume_from_offset "${ORDER_STATE_TOPIC}" "${state_offset_2}" 200 \
  | jq -c --arg oid "${order_id_2}" 'select((.orderId|tostring)==$oid and .status=="CANCELED")' \
  | head -n 1 || true)"
if [[ -z "${state_cancel_hit}" ]]; then
  echo "[FAIL] missing CANCELED order-state for orderId=${order_id_2}"
  exit 1
fi

echo "[PASS] CANCELED order-state hit: ${state_cancel_hit}"

echo "[C5] PASS"
