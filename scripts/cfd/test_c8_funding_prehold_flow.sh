#!/usr/bin/env bash
set -euo pipefail

# C8 验收（账户与资金主链）：
# 1) 注册后可登录且账户存在初始资金
# 2) CFD LIMIT 下单后 frozen 增加（资金暂扣）
# 3) 撤单后 frozen 回落（释放暂扣）
# 4) CFD MARKET 成交后出现持仓，且 available 下降或 positionMargin 上升（资金占用）

API_GATEWAY="${API_GATEWAY:-http://127.0.0.1:8082}"
OMS_BASE_URL="${OMS_BASE_URL:-http://127.0.0.1:8081}"
BINANCE_BASE_URL="${BINANCE_BASE_URL:-http://127.0.0.1:8105}"
SYMBOL="${SYMBOL:-BTCUSDT}"
USERNAME="${USERNAME:-}"
PASSWORD="${PASSWORD:-}"
LEVERAGE="${LEVERAGE:-10}"
LIMIT_QTY="${LIMIT_QTY:-0.001}"
MARKET_QTY="${MARKET_QTY:-0.001}"
TIME_IN_FORCE="${TIME_IN_FORCE:-GTC}"
POLL_RETRIES="${POLL_RETRIES:-40}"
POLL_RETRIES_RELEASE="${POLL_RETRIES_RELEASE:-120}"
POLL_INTERVAL_SEC="${POLL_INTERVAL_SEC:-0.5}"
MARKET_SUBMIT_RETRIES="${MARKET_SUBMIT_RETRIES:-5}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[FAIL] missing command: $1"
    exit 1
  fi
}

require_cmd curl
require_cmd jq
require_cmd python3

if [[ -z "${USERNAME}" || -z "${PASSWORD}" ]]; then
  echo "[FAIL] USERNAME/PASSWORD are required"
  exit 1
fi

dec_gt() {
  local left="$1"
  local right="$2"
  python3 - "$left" "$right" <<'PY'
from decimal import Decimal
import sys
l = Decimal(sys.argv[1])
r = Decimal(sys.argv[2])
print("true" if l > r else "false")
PY
}

dec_le() {
  local left="$1"
  local right="$2"
  python3 - "$left" "$right" <<'PY'
from decimal import Decimal
import sys
l = Decimal(sys.argv[1])
r = Decimal(sys.argv[2])
print("true" if l <= r else "false")
PY
}

dec_sub() {
  local left="$1"
  local right="$2"
  python3 - "$left" "$right" <<'PY'
from decimal import Decimal
import sys
l = Decimal(sys.argv[1])
r = Decimal(sys.argv[2])
print((l-r).normalize())
PY
}

dec_mul() {
  local left="$1"
  local right="$2"
  python3 - "$left" "$right" <<'PY'
from decimal import Decimal
import sys
l = Decimal(sys.argv[1])
r = Decimal(sys.argv[2])
print((l*r).quantize(Decimal("0.00000001")))
PY
}

json_post() {
  local url="$1"
  local data="$2"
  shift 2 || true
  curl -sS -X POST "${url}" -H "Content-Type: application/json" "$@" -d "${data}"
}

json_get() {
  local url="$1"
  shift || true
  curl -sS "${url}" "$@"
}

login_resp="$(json_post "${API_GATEWAY}/api/v1/user/login" "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}")"
token="$(echo "${login_resp}" | jq -r '.data.token // .data.accessToken // ""')"
user_id="$(echo "${login_resp}" | jq -r '.data.userId // .data.id // ""')"
if [[ -z "${token}" || -z "${user_id}" || "${token}" == "null" || "${user_id}" == "null" ]]; then
  echo "[FAIL] login failed: ${login_resp}"
  exit 1
fi
echo "[C8] login ok, userId=${user_id}"

get_balance() {
  json_get "${API_GATEWAY}/api/v1/account/balance" \
    -H "Authorization: Bearer ${token}" \
    -H "X-User-Id: ${user_id}"
}

parse_balance_field() {
  local payload="$1"
  local field="$2"
  echo "${payload}" | jq -r --arg f "${field}" '.[$f] // .data[$f] // "0"'
}

balance0="$(get_balance)"
available0="$(parse_balance_field "${balance0}" "available")"
frozen0="$(parse_balance_field "${balance0}" "frozen")"
position_margin0="$(parse_balance_field "${balance0}" "positionMargin")"
equity0="$(parse_balance_field "${balance0}" "equity")"
echo "[C8] baseline balance: available=${available0}, frozen=${frozen0}, positionMargin=${position_margin0}, equity=${equity0}"

if [[ "$(dec_gt "${equity0}" "0")" != "true" ]]; then
  echo "[FAIL] initial funding not ready, equity=${equity0}, raw=${balance0}"
  exit 1
fi
echo "[PASS] initial funding verified"

ref_resp="$(json_get "${BINANCE_BASE_URL}/api/binance/reference-book/${SYMBOL}?depth=20")"
ref_ok="$(echo "${ref_resp}" | jq -r '.success // false')"
if [[ "${ref_ok}" != "true" ]]; then
  echo "[FAIL] reference-book unavailable: ${ref_resp}"
  exit 1
fi
best_bid="$(echo "${ref_resp}" | jq -r '.data.bestBid // ""')"
best_ask="$(echo "${ref_resp}" | jq -r '.data.bestAsk // ""')"
if [[ -z "${best_bid}" || -z "${best_ask}" || "${best_bid}" == "null" || "${best_ask}" == "null" ]]; then
  echo "[FAIL] invalid reference book: ${ref_resp}"
  exit 1
fi

# BUY LIMIT 使用远低于 bestBid 的价格，确保不立即成交
limit_price="$(dec_mul "${best_bid}" "0.95")"
client_order_id="c8-limit-$(date +%s)-$RANDOM"
submit_limit_payload="$(cat <<JSON
{
  "clientOrderId":"${client_order_id}",
  "symbol":"${SYMBOL}",
  "side":"BUY",
  "type":"LIMIT",
  "price":"${limit_price}",
  "quantity":"${LIMIT_QTY}",
  "timeInForce":"${TIME_IN_FORCE}",
  "leverage":${LEVERAGE},
  "executionMode":"CFD_DEALER"
}
JSON
)"

submit_limit_resp="$(json_post "${OMS_BASE_URL}/api/v1/oms/order/submit" "${submit_limit_payload}" \
  -H "X-User-Id: ${user_id}" \
  -H "X-Trace-Id: c8-limit-submit-${client_order_id}")"
limit_order_id="$(echo "${submit_limit_resp}" | jq -r '.orderId // .data.orderId // ""')"
if [[ -z "${limit_order_id}" || "${limit_order_id}" == "null" ]]; then
  echo "[FAIL] submit limit failed: ${submit_limit_resp}"
  exit 1
fi
echo "[C8] limit submitted, orderId=${limit_order_id}, price=${limit_price}"

get_order_status() {
  local oid="$1"
  local single
  single="$(json_get "${OMS_BASE_URL}/api/v1/oms/order/query?orderId=${oid}" \
    -H "X-User-Id: ${user_id}")"
  local st
  st="$(echo "${single}" | jq -r '.status // .data.status // ""')"
  if [[ -n "${st}" && "${st}" != "null" ]]; then
    echo "${st}"
    return 0
  fi

  local list
  list="$(json_get "${OMS_BASE_URL}/api/v1/oms/order/list?limit=100&status=NEW,PENDING_RISK,FROZEN,PARTIALLY_FILLED,FILLED,CANCELED,REJECTED&symbol=${SYMBOL}" \
    -H "X-User-Id: ${user_id}")"
  echo "${list}" | jq -r --arg oid "${oid}" '
    (
      .orders // .data.orders // .data // []
      | map(select((.orderId|tostring)==$oid))
      | .[0].status
    ) // ""'
}

limit_status=""
for _ in $(seq 1 "${POLL_RETRIES}"); do
  limit_status="$(get_order_status "${limit_order_id}")"
  if [[ -n "${limit_status}" && "${limit_status}" != "null" ]]; then
    break
  fi
  sleep "${POLL_INTERVAL_SEC}"
done
echo "[C8] limit early status=${limit_status}"
if [[ "${limit_status}" == "FILLED" ]]; then
  echo "[FAIL] limit order unexpectedly filled, orderId=${limit_order_id}"
  exit 1
fi

# 资金暂扣校验：frozen 应上升
frozen_up="false"
for _ in $(seq 1 "${POLL_RETRIES}"); do
  b="$(get_balance)"
  frozen_now="$(parse_balance_field "${b}" "frozen")"
  if [[ "$(dec_gt "${frozen_now}" "${frozen0}")" == "true" ]]; then
    frozen_up="true"
    echo "[PASS] frozen increased after submit: ${frozen0} -> ${frozen_now}"
    break
  fi
  sleep "${POLL_INTERVAL_SEC}"
done
if [[ "${frozen_up}" != "true" ]]; then
  echo "[FAIL] frozen did not increase after submit, baseline=${frozen0}"
  exit 1
fi

cancel_resp="$(json_post "${OMS_BASE_URL}/api/v1/oms/order/cancel" "{\"orderId\":\"${limit_order_id}\"}" \
  -H "X-User-Id: ${user_id}" \
  -H "X-Trace-Id: c8-limit-cancel-${client_order_id}")"
cancel_ok="$(echo "${cancel_resp}" | jq -r '.success // (.code==0) // false')"
if [[ "${cancel_ok}" != "true" ]]; then
  echo "[FAIL] cancel failed: ${cancel_resp}"
  exit 1
fi
echo "[C8] cancel requested, orderId=${limit_order_id}"

final_status=""
for _ in $(seq 1 "${POLL_RETRIES}"); do
  final_status="$(get_order_status "${limit_order_id}")"
  if [[ "${final_status}" == "CANCELED" || "${final_status}" == "REJECTED" ]]; then
    break
  fi
  sleep "${POLL_INTERVAL_SEC}"
done
if [[ "${final_status}" != "CANCELED" ]]; then
  echo "[FAIL] cancel status check failed, status=${final_status}"
  exit 1
fi
echo "[PASS] limit order canceled"

# 暂扣释放校验：frozen 回落到 baseline（容忍 <= baseline）
frozen_back="false"
for _ in $(seq 1 "${POLL_RETRIES_RELEASE}"); do
  b="$(get_balance)"
  frozen_now="$(parse_balance_field "${b}" "frozen")"
  if [[ "$(dec_le "${frozen_now}" "${frozen0}")" == "true" ]]; then
    frozen_back="true"
    echo "[PASS] frozen released after cancel: now=${frozen_now}, baseline=${frozen0}"
    break
  fi
  sleep "${POLL_INTERVAL_SEC}"
done
if [[ "${frozen_back}" != "true" ]]; then
  echo "[FAIL] frozen not released after cancel, baseline=${frozen0}"
  exit 1
fi

balance1="$(get_balance)"
available1="$(parse_balance_field "${balance1}" "available")"
position_margin1="$(parse_balance_field "${balance1}" "positionMargin")"

market_client_order_id="c8-mkt-$(date +%s)-$RANDOM"
submit_market_payload="$(cat <<JSON
{
  "clientOrderId":"${market_client_order_id}",
  "symbol":"${SYMBOL}",
  "side":"BUY",
  "type":"MARKET",
  "quantity":"${MARKET_QTY}",
  "timeInForce":"IOC",
  "leverage":${LEVERAGE},
  "executionMode":"CFD_DEALER"
}
JSON
)"
submit_market_resp=""
market_order_id=""
for _ in $(seq 1 "${MARKET_SUBMIT_RETRIES}"); do
  submit_market_resp="$(json_post "${OMS_BASE_URL}/api/v1/oms/order/submit" "${submit_market_payload}" \
    -H "X-User-Id: ${user_id}" \
    -H "X-Trace-Id: c8-market-submit-${market_client_order_id}")"
  market_order_id="$(echo "${submit_market_resp}" | jq -r '.orderId // .data.orderId // ""')"
  if [[ -n "${market_order_id}" && "${market_order_id}" != "null" ]]; then
    break
  fi
  err_code="$(echo "${submit_market_resp}" | jq -r '.errorCode // .data.errorCode // ""')"
  if [[ "${err_code}" != "OMS_3002" ]]; then
    break
  fi
  sleep "${POLL_INTERVAL_SEC}"
done
if [[ -z "${market_order_id}" || "${market_order_id}" == "null" ]]; then
  echo "[FAIL] submit market failed: ${submit_market_resp}"
  exit 1
fi
echo "[C8] market submitted, orderId=${market_order_id}"

market_final_status=""
for _ in $(seq 1 "${POLL_RETRIES}"); do
  market_final_status="$(get_order_status "${market_order_id}")"
  if [[ "${market_final_status}" == "FILLED" ]]; then
    break
  fi
  sleep "${POLL_INTERVAL_SEC}"
done
if [[ "${market_final_status}" != "FILLED" ]]; then
  echo "[FAIL] market fill not reached, status=${market_final_status}"
  exit 1
fi
echo "[PASS] market order filled"

positions_resp=""
active_pos=""
for _ in $(seq 1 "${POLL_RETRIES}"); do
  positions_resp="$(json_get "${API_GATEWAY}/api/v1/position/list" -H "Authorization: Bearer ${token}" -H "X-User-Id: ${user_id}")"
  active_pos="$(echo "${positions_resp}" | jq -c --arg s "${SYMBOL}" '
    (
      .positions // .data.positions // .data // []
      | map(select((.symbol|ascii_upcase)==($s|ascii_upcase)))
      | map(select((.size|tonumber) > 0))
      | .[0]
    ) // empty
  ')"
  if [[ -n "${active_pos}" ]]; then
    break
  fi
  sleep "${POLL_INTERVAL_SEC}"
done
if [[ -z "${active_pos}" ]]; then
  echo "[FAIL] no active position after fill, positions=${positions_resp}"
  exit 1
fi
echo "[PASS] active position exists: ${active_pos}"

balance2=""
available2="${available1}"
position_margin2="${position_margin1}"
available_down="false"
margin_up="false"
for _ in $(seq 1 "${POLL_RETRIES}"); do
  balance2="$(get_balance)"
  available2="$(parse_balance_field "${balance2}" "available")"
  position_margin2="$(parse_balance_field "${balance2}" "positionMargin")"

  available_down="$(python3 - "$available2" "$available1" <<'PY'
from decimal import Decimal
import sys
print("true" if Decimal(sys.argv[1]) < Decimal(sys.argv[2]) else "false")
PY
)"
  margin_up="$(python3 - "$position_margin2" "$position_margin1" <<'PY'
from decimal import Decimal
import sys
print("true" if Decimal(sys.argv[1]) > Decimal(sys.argv[2]) else "false")
PY
)"
  if [[ "${available_down}" == "true" || "${margin_up}" == "true" ]]; then
    break
  fi
  sleep "${POLL_INTERVAL_SEC}"
done

if [[ "${available_down}" != "true" && "${margin_up}" != "true" ]]; then
  echo "[FAIL] no fund occupation change after fill: available ${available1}->${available2}, positionMargin ${position_margin1}->${position_margin2}"
  exit 1
fi
echo "[PASS] fill fund occupation verified: available ${available1}->${available2}, positionMargin ${position_margin1}->${position_margin2}"

echo "[C8] PASS"
