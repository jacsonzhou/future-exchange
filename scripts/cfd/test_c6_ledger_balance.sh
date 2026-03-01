#!/usr/bin/env bash
set -euo pipefail

# C6 验收：
# 1) CFD 成交事件包含 executionMode/liquiditySource/dealerAccountId
# 2) trade-entry-{symbol} 事件借贷平衡（sum(debit) == sum(credit)）
# 3) ledger 表落库借贷平衡，且 dealer/user 双边分录都存在
# 4) 成交数量敞口：dealer 与 user 方向相反且绝对值相等

OMS_BASE_URL="${OMS_BASE_URL:-http://127.0.0.1:8081}"
SYMBOL="${SYMBOL:-BTCUSDT}"
USER_ID="${USER_ID:-18}"
LEVERAGE="${LEVERAGE:-10}"
QUANTITY="${QUANTITY:-0.001}"
TIME_IN_FORCE="${TIME_IN_FORCE:-IOC}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka-1}"
BOOTSTRAP="${BOOTSTRAP:-localhost:9092}"
CONSUME_TIMEOUT_MS="${CONSUME_TIMEOUT_MS:-20000}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-web3-mysql}"
TRADE_TOPIC="${TRADE_TOPIC:-trade-event-${SYMBOL}}"
TRADE_ENTRY_TOPIC="${TRADE_ENTRY_TOPIC:-trade-entry-${SYMBOL}}"
EXPECTED_DEALER_ACCOUNT_ID="${EXPECTED_DEALER_ACCOUNT_ID:-}"

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
  local max_messages="${3:-200}"

  docker exec "${KAFKA_CONTAINER}" kafka-console-consumer \
    --bootstrap-server "${BOOTSTRAP}" \
    --topic "${topic}" \
    --partition 0 \
    --offset "${offset}" \
    --timeout-ms "${CONSUME_TIMEOUT_MS}" \
    --max-messages "${max_messages}" 2>/dev/null || true
}

pre_trade_offset="$(get_latest_offset "${TRADE_TOPIC}")"
pre_entry_offset="$(get_latest_offset "${TRADE_ENTRY_TOPIC}")"
client_order_id="c6-ledger-$(date +%s)-$RANDOM"

submit_payload="$(cat <<JSON
{
  "clientOrderId":"${client_order_id}",
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

echo "[C6] submit market order, clientOrderId=${client_order_id}, preOffsets: ${TRADE_TOPIC}=${pre_trade_offset}, ${TRADE_ENTRY_TOPIC}=${pre_entry_offset}"
submit_resp="$(curl -sS -X POST "${OMS_BASE_URL}/api/v1/oms/order/submit" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: ${USER_ID}" \
  -H "X-Trace-Id: c6-ledger-balance" \
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

trade_hit="$(consume_from_offset "${TRADE_TOPIC}" "${pre_trade_offset}" 240 \
  | jq -c --arg oid "${order_id}" 'select((.eventType=="TRADE") and (((.takerOrderId|tostring)==$oid) or ((.makerOrderId|tostring)==$oid)))' \
  | head -n 1 || true)"
if [[ -z "${trade_hit}" ]]; then
  echo "[FAIL] missing trade-event for orderId=${order_id}, topic=${TRADE_TOPIC}"
  exit 1
fi

execution_mode="$(echo "${trade_hit}" | jq -r '.executionMode // ""')"
liquidity_source="$(echo "${trade_hit}" | jq -r '.liquiditySource // ""')"
dealer_account_id="$(echo "${trade_hit}" | jq -r '.dealerAccountId // empty')"
trade_id="$(echo "${trade_hit}" | jq -r '.tradeId // ""')"
maker_user_id="$(echo "${trade_hit}" | jq -r '.makerUserId // empty')"
taker_user_id="$(echo "${trade_hit}" | jq -r '.takerUserId // empty')"

if [[ "${execution_mode}" != "CFD_DEALER" ]]; then
  echo "[FAIL] trade-event executionMode invalid: ${trade_hit}"
  exit 1
fi
if [[ -z "${liquidity_source}" || "${liquidity_source}" == "null" ]]; then
  echo "[FAIL] trade-event liquiditySource missing: ${trade_hit}"
  exit 1
fi
if [[ -z "${dealer_account_id}" || "${dealer_account_id}" == "null" ]]; then
  echo "[FAIL] trade-event dealerAccountId missing: ${trade_hit}"
  exit 1
fi
if [[ -n "${EXPECTED_DEALER_ACCOUNT_ID}" && "${dealer_account_id}" != "${EXPECTED_DEALER_ACCOUNT_ID}" ]]; then
  echo "[FAIL] dealerAccountId mismatch, expected=${EXPECTED_DEALER_ACCOUNT_ID}, actual=${dealer_account_id}"
  exit 1
fi
if [[ "${dealer_account_id}" != "${maker_user_id}" && "${dealer_account_id}" != "${taker_user_id}" ]]; then
  echo "[FAIL] dealerAccountId not in maker/taker users: ${trade_hit}"
  exit 1
fi
if [[ -z "${trade_id}" ]]; then
  echo "[FAIL] trade-event missing tradeId: ${trade_hit}"
  exit 1
fi

exposure_json="$(
  TRADE_HIT_JSON="${trade_hit}" python3 - <<'PY'
import json
import os
import sys
from decimal import Decimal

event = json.loads(os.environ["TRADE_HIT_JSON"])
scale = Decimal("100000000")
qty = Decimal(str(event.get("quantity", "0"))) / scale
dealer = str(event.get("dealerAccountId"))
maker = str(event.get("makerUserId"))
taker = str(event.get("takerUserId"))
is_buyer_maker = bool(event.get("isBuyerMaker"))

if qty <= 0:
    print(json.dumps({"ok": False, "reason": "quantity <= 0"}))
    sys.exit(0)

if dealer == maker:
    dealer_signed = qty if is_buyer_maker else -qty
    user_signed = -dealer_signed
    user_id = taker
elif dealer == taker:
    dealer_signed = -qty if is_buyer_maker else qty
    user_signed = -dealer_signed
    user_id = maker
else:
    print(json.dumps({"ok": False, "reason": "dealer not in maker/taker"}))
    sys.exit(0)

net = dealer_signed + user_signed
ok = abs(net) <= Decimal("0.00000001") and abs(abs(dealer_signed) - abs(user_signed)) <= Decimal("0.00000001")
print(json.dumps({
    "ok": ok,
    "reason": "" if ok else "exposure mismatch",
    "dealerSignedQty": str(dealer_signed),
    "userSignedQty": str(user_signed),
    "userCounterpartyId": user_id
}))
PY
)"

exposure_ok="$(echo "${exposure_json}" | jq -r '.ok')"
if [[ "${exposure_ok}" != "true" ]]; then
  echo "[FAIL] exposure mirror check failed: ${exposure_json}, trade=${trade_hit}"
  exit 1
fi
user_counterparty_id="$(echo "${exposure_json}" | jq -r '.userCounterpartyId')"
if [[ "${user_counterparty_id}" != "${USER_ID}" ]]; then
  echo "[FAIL] counterparty user mismatch, expected=${USER_ID}, actual=${user_counterparty_id}, trade=${trade_hit}"
  exit 1
fi
echo "[PASS] trade-event contract + exposure mirror: tradeId=${trade_id}, dealer=${dealer_account_id}, user=${user_counterparty_id}, details=${exposure_json}"

entry_hit="$(consume_from_offset "${TRADE_ENTRY_TOPIC}" "${pre_entry_offset}" 240 \
  | jq -c --arg tid "${trade_id}" 'select(.tradeId==$tid)' \
  | head -n 1 || true)"
if [[ -z "${entry_hit}" ]]; then
  echo "[FAIL] missing trade-entry event for tradeId=${trade_id}, topic=${TRADE_ENTRY_TOPIC}"
  exit 1
fi

entry_execution_mode="$(echo "${entry_hit}" | jq -r '.executionMode // ""')"
entry_liquidity_source="$(echo "${entry_hit}" | jq -r '.liquiditySource // ""')"
entry_dealer_account_id="$(echo "${entry_hit}" | jq -r '.dealerAccountId // empty')"
if [[ "${entry_execution_mode}" != "CFD_DEALER" ]]; then
  echo "[FAIL] trade-entry executionMode invalid: ${entry_hit}"
  exit 1
fi
if [[ -z "${entry_liquidity_source}" || "${entry_liquidity_source}" == "null" ]]; then
  echo "[FAIL] trade-entry liquiditySource missing: ${entry_hit}"
  exit 1
fi
if [[ -z "${entry_dealer_account_id}" || "${entry_dealer_account_id}" != "${dealer_account_id}" ]]; then
  echo "[FAIL] trade-entry dealerAccountId mismatch: ${entry_hit}"
  exit 1
fi

entry_balance_json="$(
  ENTRY_HIT_JSON="${entry_hit}" DEALER_ACCOUNT_ID="${dealer_account_id}" USER_COUNTERPARTY_ID="${user_counterparty_id}" python3 - <<'PY'
import json
import os
import sys
from decimal import Decimal

event = json.loads(os.environ["ENTRY_HIT_JSON"])
entries = event.get("entries") or []
dealer_id = os.environ["DEALER_ACCOUNT_ID"]
user_id = os.environ["USER_COUNTERPARTY_ID"]

debit = Decimal("0")
credit = Decimal("0")
dealer_rows = 0
user_rows = 0
for item in entries:
    debit += Decimal(str(item.get("debit", "0")))
    credit += Decimal(str(item.get("credit", "0")))
    uid = str(item.get("userId"))
    if uid == dealer_id:
        dealer_rows += 1
    if uid == user_id:
        user_rows += 1

diff = abs(debit - credit)
ok = len(entries) >= 4 and diff <= Decimal("0.00000001") and dealer_rows >= 2 and user_rows >= 2
print(json.dumps({
    "ok": ok,
    "entryCount": len(entries),
    "debit": str(debit),
    "credit": str(credit),
    "diff": str(diff),
    "dealerRows": dealer_rows,
    "userRows": user_rows
}))
PY
)"

entry_ok="$(echo "${entry_balance_json}" | jq -r '.ok')"
if [[ "${entry_ok}" != "true" ]]; then
  echo "[FAIL] trade-entry balance check failed: ${entry_balance_json}, event=${entry_hit}"
  exit 1
fi
echo "[PASS] trade-entry balance check: ${entry_balance_json}"

db_line="$(docker exec "${MYSQL_CONTAINER}" mysql -uroot -proot123456 -N -e \
  "SELECT COUNT(*), IFNULL(SUM(debit),0), IFNULL(SUM(credit),0), IFNULL(SUM(CASE WHEN user_id=${dealer_account_id} THEN 1 ELSE 0 END),0), IFNULL(SUM(CASE WHEN user_id=${user_counterparty_id} THEN 1 ELSE 0 END),0) FROM exchange_ledger.t_ledger_entry WHERE ref_trade_id='${trade_id}';" 2>/dev/null | tr -d '\r')"
if [[ -z "${db_line}" ]]; then
  echo "[FAIL] ledger db query returned empty for tradeId=${trade_id}"
  exit 1
fi

db_balance_json="$(
  DB_LINE="${db_line}" python3 - <<'PY'
import json
import os
from decimal import Decimal

parts = os.environ["DB_LINE"].split("\t")
if len(parts) != 5:
    print(json.dumps({"ok": False, "reason": "unexpected db line", "raw": os.environ["DB_LINE"]}))
    raise SystemExit(0)

count = int(parts[0])
debit = Decimal(parts[1])
credit = Decimal(parts[2])
dealer_rows = int(parts[3])
user_rows = int(parts[4])
diff = abs(debit - credit)
ok = count >= 4 and diff <= Decimal("0.00000001") and dealer_rows >= 2 and user_rows >= 2
print(json.dumps({
    "ok": ok,
    "rowCount": count,
    "debit": str(debit),
    "credit": str(credit),
    "diff": str(diff),
    "dealerRows": dealer_rows,
    "userRows": user_rows
}))
PY
)"

db_ok="$(echo "${db_balance_json}" | jq -r '.ok')"
if [[ "${db_ok}" != "true" ]]; then
  echo "[FAIL] ledger db balance check failed: ${db_balance_json}"
  exit 1
fi
echo "[PASS] ledger db balance check: ${db_balance_json}"

echo "[C6] PASS"
