#!/usr/bin/env bash
set -euo pipefail

# C7 验收（日志门禁 + DB 一致性）：
# 1) 成交后 position-snapshot-core 发布 trade 相关持仓变更（OPEN/INCREASE/DECREASE/CLOSE）
# 2) 成交后 private-push-core 处理 ACCOUNT_BALANCE_UPDATE
# 3) mark 刷新后 position-snapshot-core 发布 MARK_PRICE_UPDATE
# 4) mark 刷新后 private-push-core 处理 ACCOUNT_MARK_UPDATE
# 5) DB 一致性：account.unrealized_pnl == sum(position.unrealized_pnl)

OMS_BASE_URL="${OMS_BASE_URL:-http://127.0.0.1:8081}"
INDEX_BASE_URL="${INDEX_BASE_URL:-http://127.0.0.1:8093}"
MARK_BASE_URL="${MARK_BASE_URL:-http://127.0.0.1:8094}"
SYMBOL="${SYMBOL:-BTCUSDT}"
USER_ID="${USER_ID:-18}"
LEVERAGE="${LEVERAGE:-10}"
QUANTITY="${QUANTITY:-0.001}"
TIME_IN_FORCE="${TIME_IN_FORCE:-IOC}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-web3-mysql}"
UPNL_EPSILON="${UPNL_EPSILON:-0.00000001}"
TRADE_WAIT_RETRIES="${TRADE_WAIT_RETRIES:-240}"
MARK_WAIT_RETRIES="${MARK_WAIT_RETRIES:-240}"
MARK_WAIT_INTERVAL="${MARK_WAIT_INTERVAL:-0.25}"
MARK_RETRIGGER_EVERY="${MARK_RETRIGGER_EVERY:-8}"
TRIGGER_CURL_MAX_TIME="${TRIGGER_CURL_MAX_TIME:-2}"
REQUIRE_POSITION_MARK_UPDATE="${REQUIRE_POSITION_MARK_UPDATE:-true}"

POSITION_LOG="${POSITION_LOG:-logs/servicectl/position-snapshot-core.log}"
ACCOUNT_LOG="${ACCOUNT_LOG:-logs/servicectl/snapshot-account-core.log}"
PRIVATE_PUSH_LOG="${PRIVATE_PUSH_LOG:-logs/servicectl/private-push-core.log}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[FAIL] missing command: $1"
    exit 1
  fi
}

require_cmd curl
require_cmd jq
require_cmd docker
require_cmd python3
require_cmd wc
require_cmd grep
require_cmd sed

for f in "${POSITION_LOG}" "${ACCOUNT_LOG}" "${PRIVATE_PUSH_LOG}"; do
  if [[ ! -f "${f}" ]]; then
    echo "[FAIL] log file not found: ${f}"
    exit 1
  fi
done

line_count() {
  local file="$1"
  wc -l < "${file}" | tr -d ' '
}

slice_from() {
  local file="$1"
  local start_line="$2"
  sed -n "$((start_line + 1)),\$p" "${file}"
}

query_position_upnl_sum() {
  docker exec "${MYSQL_CONTAINER}" mysql -uroot -proot123456 -N -e \
    "SELECT COALESCE(SUM(CASE WHEN size>0 THEN unrealized_pnl ELSE 0 END),0) FROM exchange_position.position_snapshot WHERE user_id=${USER_ID};" 2>/dev/null \
    | tr -d '\r' | head -n 1
}

query_account_upnl() {
  docker exec "${MYSQL_CONTAINER}" mysql -uroot -proot123456 -N -e \
    "SELECT COALESCE(unrealized_pnl,0) FROM exchange_snapshot.account_snapshot WHERE user_id=${USER_ID} LIMIT 1;" 2>/dev/null \
    | tr -d '\r' | head -n 1
}

wait_log_match() {
  local file="$1"
  local start_line="$2"
  local pattern="$3"
  local retries="${4:-60}"
  local interval="${5:-0.2}"

  for _ in $(seq 1 "${retries}"); do
    if slice_from "${file}" "${start_line}" | grep -E -q "${pattern}"; then
      return 0
    fi
    sleep "${interval}"
  done
  return 1
}

wait_mark_stage_with_retrigger() {
  local pos_start_line="$1"
  local push_start_line="$2"
  local pos_pattern="$3"
  local push_pattern="$4"
  local retries="${5:-240}"
  local interval="${6:-0.25}"
  local retrigger_every="${7:-8}"
  local require_pos="${8:-true}"
  local mark_pos_hit=""
  local mark_push_hit=""

  for i in $(seq 1 "${retries}"); do
    if [[ -z "${mark_pos_hit}" ]]; then
      mark_pos_hit="$(extract_first_match "${POSITION_LOG}" "${pos_start_line}" "${pos_pattern}" || true)"
    fi
    if [[ -z "${mark_push_hit}" ]]; then
      mark_push_hit="$(extract_first_match "${PRIVATE_PUSH_LOG}" "${push_start_line}" "${push_pattern}" || true)"
    fi

    if [[ "${require_pos}" == "true" ]]; then
      if [[ -n "${mark_pos_hit}" && -n "${mark_push_hit}" ]]; then
        echo "${mark_pos_hit}|||${mark_push_hit}"
        return 0
      fi
    else
      if [[ -n "${mark_push_hit}" ]]; then
        echo "${mark_pos_hit}|||${mark_push_hit}"
        return 0
      fi
    fi

    if (( i % retrigger_every == 0 )); then
      curl -sS --max-time "${TRIGGER_CURL_MAX_TIME}" -X POST "${INDEX_BASE_URL}/api/v1/index-price/calculate?symbol=${SYMBOL}" >/dev/null || true
      curl -sS --max-time "${TRIGGER_CURL_MAX_TIME}" -X POST "${MARK_BASE_URL}/api/v1/mark-price/calculate?symbol=${SYMBOL}" >/dev/null || true
    fi

    sleep "${interval}"
  done

  return 1
}

extract_first_match() {
  local file="$1"
  local start_line="$2"
  local pattern="$3"
  slice_from "${file}" "${start_line}" | grep -E "${pattern}" | head -n 1
}

pos_start_line="$(line_count "${POSITION_LOG}")"
acc_start_line="$(line_count "${ACCOUNT_LOG}")"
push_start_line="$(line_count "${PRIVATE_PUSH_LOG}")"

client_order_id="c7-push-$(date +%s)-$RANDOM"
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

echo "[C7] submit market order, userId=${USER_ID}, symbol=${SYMBOL}, clientOrderId=${client_order_id}"
submit_resp="$(curl -sS -X POST "${OMS_BASE_URL}/api/v1/oms/order/submit" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: ${USER_ID}" \
  -H "X-Trace-Id: c7-push-consistency" \
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

trade_pos_pattern="Published position change, userId=${USER_ID}, symbol=${SYMBOL}, positionSide=[0-9]+, changeType=(OPEN|INCREASE|DECREASE|CLOSE)"
if ! wait_log_match "${POSITION_LOG}" "${pos_start_line}" "${trade_pos_pattern}" "${TRADE_WAIT_RETRIES}" 0.2; then
  echo "[FAIL] missing trade-related position change log for user=${USER_ID}, symbol=${SYMBOL}, orderId=${order_id}"
  exit 1
fi
trade_pos_hit="$(extract_first_match "${POSITION_LOG}" "${pos_start_line}" "${trade_pos_pattern}")"
echo "[PASS] position trade change hit: ${trade_pos_hit}"

trade_push_pattern="Processed account change, userId=${USER_ID}, eventType=ACCOUNT_BALANCE_UPDATE"
if ! wait_log_match "${PRIVATE_PUSH_LOG}" "${push_start_line}" "${trade_push_pattern}" "${TRADE_WAIT_RETRIES}" 0.2; then
  echo "[FAIL] missing private-push ACCOUNT_BALANCE_UPDATE for user=${USER_ID}"
  exit 1
fi
trade_push_hit="$(extract_first_match "${PRIVATE_PUSH_LOG}" "${push_start_line}" "${trade_push_pattern}")"
echo "[PASS] private-push balance update hit: ${trade_push_hit}"

pos_mark_start_line="$(line_count "${POSITION_LOG}")"
push_mark_start_line="$(line_count "${PRIVATE_PUSH_LOG}")"
# 防止 mark 事件与 trade 阶段紧邻到达，给检索窗口预留少量回看行数。
pos_mark_search_line=$(( pos_mark_start_line > 200 ? pos_mark_start_line - 200 : 0 ))
push_mark_search_line=$(( push_mark_start_line > 200 ? push_mark_start_line - 200 : 0 ))

for _ in 1 2 3; do
  curl -sS --max-time "${TRIGGER_CURL_MAX_TIME}" -X POST "${INDEX_BASE_URL}/api/v1/index-price/calculate?symbol=${SYMBOL}" >/dev/null || true
  sleep 1
done
curl -sS --max-time "${TRIGGER_CURL_MAX_TIME}" -X POST "${MARK_BASE_URL}/api/v1/mark-price/calculate?symbol=${SYMBOL}" >/dev/null || true

mark_pos_pattern="Published position change, userId=${USER_ID}, symbol=${SYMBOL}, positionSide=[0-9]+, changeType=MARK_PRICE_UPDATE"
mark_push_pattern="Processed account change, userId=${USER_ID}, eventType=ACCOUNT_MARK_UPDATE"
mark_stage_hits="$(wait_mark_stage_with_retrigger \
  "${pos_mark_search_line}" \
  "${push_mark_search_line}" \
  "${mark_pos_pattern}" \
  "${mark_push_pattern}" \
  "${MARK_WAIT_RETRIES}" \
  "${MARK_WAIT_INTERVAL}" \
  "${MARK_RETRIGGER_EVERY}" \
  "${REQUIRE_POSITION_MARK_UPDATE}" || true)"

if [[ -z "${mark_stage_hits}" ]]; then
  if [[ "${REQUIRE_POSITION_MARK_UPDATE}" == "true" ]]; then
    echo "[FAIL] missing MARK stage logs, user=${USER_ID}, symbol=${SYMBOL}, required=[MARK_PRICE_UPDATE + ACCOUNT_MARK_UPDATE]"
  else
    echo "[FAIL] missing MARK stage logs, user=${USER_ID}, symbol=${SYMBOL}, required=[ACCOUNT_MARK_UPDATE]"
  fi
  exit 1
fi

mark_pos_hit="${mark_stage_hits%%|||*}"
mark_push_hit="${mark_stage_hits#*|||}"
if [[ "${REQUIRE_POSITION_MARK_UPDATE}" == "true" && -z "${mark_pos_hit}" ]]; then
  echo "[FAIL] missing MARK_PRICE_UPDATE position change log for user=${USER_ID}, symbol=${SYMBOL}"
  exit 1
fi
if [[ -z "${mark_push_hit}" ]]; then
  echo "[FAIL] missing private-push ACCOUNT_MARK_UPDATE for user=${USER_ID}"
  exit 1
fi

if [[ -n "${mark_pos_hit}" ]]; then
  echo "[PASS] position mark update hit: ${mark_pos_hit}"
elif [[ "${REQUIRE_POSITION_MARK_UPDATE}" != "true" ]]; then
  echo "[WARN] position MARK_PRICE_UPDATE check skipped (REQUIRE_POSITION_MARK_UPDATE=${REQUIRE_POSITION_MARK_UPDATE})"
fi
echo "[PASS] private-push mark update hit: ${mark_push_hit}"

consistency_ok="false"
pos_upnl=""
acc_upnl=""
for _ in $(seq 1 60); do
  pos_upnl="$(query_position_upnl_sum)"
  acc_upnl="$(query_account_upnl)"
  if [[ -z "${pos_upnl}" ]]; then
    pos_upnl="0"
  fi
  if [[ -z "${acc_upnl}" ]]; then
    acc_upnl="0"
  fi

  consistency_ok="$(
    POS_UPNL="${pos_upnl}" ACC_UPNL="${acc_upnl}" EPS="${UPNL_EPSILON}" python3 - <<'PY'
from decimal import Decimal
import os
pos = Decimal(os.environ["POS_UPNL"])
acc = Decimal(os.environ["ACC_UPNL"])
eps = Decimal(os.environ["EPS"])
print("true" if abs(pos - acc) <= eps else "false")
PY
)"
  if [[ "${consistency_ok}" == "true" ]]; then
    break
  fi
  sleep 0.2
done

if [[ "${consistency_ok}" != "true" ]]; then
  diff="$(
    POS_UPNL="${pos_upnl:-0}" ACC_UPNL="${acc_upnl:-0}" python3 - <<'PY'
from decimal import Decimal
import os
pos = Decimal(os.environ["POS_UPNL"])
acc = Decimal(os.environ["ACC_UPNL"])
print(str(pos - acc))
PY
)"
  echo "[FAIL] upnl mismatch, position_sum=${pos_upnl:-0}, account=${acc_upnl:-0}, diff=${diff}"
  exit 1
fi
echo "[PASS] upnl consistency, position_sum=${pos_upnl}, account=${acc_upnl}"

echo "[C7] PASS"
