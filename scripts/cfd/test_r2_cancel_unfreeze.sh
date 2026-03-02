#!/usr/bin/env bash
set -euo pipefail

# R2 验收（撤单解冻）：
# 1) LIMIT 挂单后 frozen 上升（资金暂扣）
# 2) 撤单后订单状态为 CANCELED
# 3) 撤单后 frozen 回落到基线（允许极小误差）
# 4) Ledger 存在 MARGIN_FREEZE / MARGIN_UNFREEZE 分录

OMS_BASE_URL="${OMS_BASE_URL:-http://127.0.0.1:8081}"
SNAPSHOT_BASE_URL="${SNAPSHOT_BASE_URL:-http://127.0.0.1:8085}"
BINANCE_BASE_URL="${BINANCE_BASE_URL:-http://127.0.0.1:8105}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-web3-mysql}"
SYMBOL="${SYMBOL:-BTCUSDT}"
USER_ID="${USER_ID:-18}"
LEVERAGE="${LEVERAGE:-10}"
QUANTITY="${QUANTITY:-0.001}"
TIME_IN_FORCE="${TIME_IN_FORCE:-GTC}"
POLL_RETRIES="${POLL_RETRIES:-80}"
POLL_INTERVAL_SEC="${POLL_INTERVAL_SEC:-0.25}"
BALANCE_EPSILON="${BALANCE_EPSILON:-0.00000001}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[FAIL] missing command: $1"
    exit 1
  fi
}

require_cmd curl
require_cmd jq
require_cmd python3
require_cmd docker

dec_gt() {
  local left="$1"
  local right="$2"
  python3 - "$left" "$right" <<'PY'
from decimal import Decimal
import sys
print("true" if Decimal(sys.argv[1]) > Decimal(sys.argv[2]) else "false")
PY
}

dec_le_with_eps() {
  local left="$1"
  local right="$2"
  local eps="$3"
  python3 - "$left" "$right" "$eps" <<'PY'
from decimal import Decimal
import sys
left = Decimal(sys.argv[1])
right = Decimal(sys.argv[2])
eps = Decimal(sys.argv[3])
print("true" if left <= (right + eps) else "false")
PY
}

get_balance() {
  curl -sS "${SNAPSHOT_BASE_URL}/api/v1/account/balance" \
    -H "X-User-Id: ${USER_ID}"
}

parse_balance_field() {
  local payload="$1"
  local field="$2"
  echo "${payload}" | jq -r --arg f "${field}" '.[$f] // .data[$f] // "0"'
}

get_order_status() {
  local order_id="$1"
  curl -sS "${OMS_BASE_URL}/api/v1/oms/order/query?orderId=${order_id}" \
    -H "X-User-Id: ${USER_ID}" \
    | jq -r '.status // ""'
}

query_ledger_entry_count() {
  local order_id="$1"
  local business_type="$2"
  docker exec "${MYSQL_CONTAINER}" mysql -uroot -proot123456 -N -e \
    "SELECT COUNT(*) FROM exchange_ledger.t_ledger_entry WHERE ref_order_id=${order_id} AND business_type='${business_type}';" 2>/dev/null \
    | tr -d '\r' | head -n 1
}

reference_resp="$(curl -sS "${BINANCE_BASE_URL}/api/binance/reference-book/${SYMBOL}?depth=20")"
reference_ok="$(echo "${reference_resp}" | jq -r '.success // false')"
if [[ "${reference_ok}" != "true" ]]; then
  echo "[FAIL] reference-book unavailable: ${reference_resp}"
  exit 1
fi

best_bid="$(echo "${reference_resp}" | jq -r '.data.bestBid // ""')"
if [[ -z "${best_bid}" || "${best_bid}" == "null" ]]; then
  echo "[FAIL] invalid bestBid from reference-book: ${reference_resp}"
  exit 1
fi

limit_price="$(python3 - "$best_bid" <<'PY'
from decimal import Decimal, ROUND_DOWN
import sys
bid = Decimal(sys.argv[1])
price = (bid * Decimal("0.95")).quantize(Decimal("0.1"), rounding=ROUND_DOWN)
if price <= 0:
    price = Decimal("1")
print(format(price, "f"))
PY
)"

balance_before="$(get_balance)"
available_before="$(parse_balance_field "${balance_before}" "available")"
frozen_before="$(parse_balance_field "${balance_before}" "frozen")"
echo "[R2] baseline balance, available=${available_before}, frozen=${frozen_before}"

client_order_id="r2-cancel-$(date +%s)-$RANDOM"
submit_payload="$(cat <<JSON
{
  "clientOrderId":"${client_order_id}",
  "symbol":"${SYMBOL}",
  "side":"BUY",
  "type":"LIMIT",
  "price":"${limit_price}",
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
  -H "X-Trace-Id: r2-cancel-unfreeze-submit" \
  -d "${submit_payload}")"
submit_ok="$(echo "${submit_resp}" | jq -r '.success // false')"
if [[ "${submit_ok}" != "true" ]]; then
  echo "[FAIL] submit failed: ${submit_resp}"
  exit 1
fi

order_id="$(echo "${submit_resp}" | jq -r '.orderId // ""')"
if [[ -z "${order_id}" || "${order_id}" == "null" ]]; then
  echo "[FAIL] submit response missing orderId: ${submit_resp}"
  exit 1
fi
echo "[R2] passive limit submitted, orderId=${order_id}, price=${limit_price}"

frozen_raised="false"
for _ in $(seq 1 "${POLL_RETRIES}"); do
  balance_now="$(get_balance)"
  frozen_now="$(parse_balance_field "${balance_now}" "frozen")"
  if [[ "$(dec_gt "${frozen_now}" "${frozen_before}")" == "true" ]]; then
    frozen_raised="true"
    echo "[PASS] frozen raised after submit: ${frozen_before} -> ${frozen_now}"
    break
  fi
  sleep "${POLL_INTERVAL_SEC}"
done
if [[ "${frozen_raised}" != "true" ]]; then
  echo "[FAIL] frozen did not increase after submit, baseline=${frozen_before}"
  exit 1
fi

cancel_resp="$(curl -sS -X POST "${OMS_BASE_URL}/api/v1/oms/order/cancel" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: ${USER_ID}" \
  -H "X-Trace-Id: r2-cancel-unfreeze-cancel" \
  -d "{\"orderId\":\"${order_id}\"}")"
cancel_ok="$(echo "${cancel_resp}" | jq -r '.success // false')"
if [[ "${cancel_ok}" != "true" ]]; then
  echo "[FAIL] cancel failed: ${cancel_resp}"
  exit 1
fi
echo "[R2] cancel requested, orderId=${order_id}"

final_status=""
for _ in $(seq 1 "${POLL_RETRIES}"); do
  final_status="$(get_order_status "${order_id}")"
  if [[ "${final_status}" == "CANCELED" ]]; then
    break
  fi
  sleep "${POLL_INTERVAL_SEC}"
done
if [[ "${final_status}" != "CANCELED" ]]; then
  echo "[FAIL] order not canceled in time, orderId=${order_id}, status=${final_status:-<empty>}"
  exit 1
fi
echo "[PASS] order status is CANCELED, orderId=${order_id}"

frozen_released="false"
frozen_after=""
for _ in $(seq 1 "${POLL_RETRIES}"); do
  balance_now="$(get_balance)"
  frozen_after="$(parse_balance_field "${balance_now}" "frozen")"
  if [[ "$(dec_le_with_eps "${frozen_after}" "${frozen_before}" "${BALANCE_EPSILON}")" == "true" ]]; then
    frozen_released="true"
    break
  fi
  sleep "${POLL_INTERVAL_SEC}"
done
if [[ "${frozen_released}" != "true" ]]; then
  echo "[FAIL] frozen not released after cancel, baseline=${frozen_before}, current=${frozen_after}"
  exit 1
fi
echo "[PASS] frozen released after cancel, baseline=${frozen_before}, now=${frozen_after}"

freeze_entry_count=""
for _ in $(seq 1 "${POLL_RETRIES}"); do
  freeze_entry_count="$(query_ledger_entry_count "${order_id}" "MARGIN_FREEZE")"
  if [[ -n "${freeze_entry_count}" ]] && [[ "${freeze_entry_count}" =~ ^[0-9]+$ ]] && [[ "${freeze_entry_count}" -ge 2 ]]; then
    break
  fi
  sleep "${POLL_INTERVAL_SEC}"
done
if [[ -z "${freeze_entry_count}" || ! "${freeze_entry_count}" =~ ^[0-9]+$ || "${freeze_entry_count}" -lt 2 ]]; then
  echo "[FAIL] missing freeze ledger entries for orderId=${order_id}, count=${freeze_entry_count:-<empty>}"
  exit 1
fi
echo "[PASS] freeze ledger entries found, count=${freeze_entry_count}"

unfreeze_entry_count=""
for _ in $(seq 1 "${POLL_RETRIES}"); do
  unfreeze_entry_count="$(query_ledger_entry_count "${order_id}" "MARGIN_UNFREEZE")"
  if [[ -n "${unfreeze_entry_count}" ]] && [[ "${unfreeze_entry_count}" =~ ^[0-9]+$ ]] && [[ "${unfreeze_entry_count}" -ge 2 ]]; then
    break
  fi
  sleep "${POLL_INTERVAL_SEC}"
done
if [[ -z "${unfreeze_entry_count}" || ! "${unfreeze_entry_count}" =~ ^[0-9]+$ || "${unfreeze_entry_count}" -lt 2 ]]; then
  echo "[FAIL] missing unfreeze ledger entries for orderId=${order_id}, count=${unfreeze_entry_count:-<empty>}"
  exit 1
fi
echo "[PASS] unfreeze ledger entries found, count=${unfreeze_entry_count}"

echo "[R2] PASS"
