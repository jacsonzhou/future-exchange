#!/usr/bin/env bash
set -euo pipefail

# R6 验收（强平 E2E）：
# 1) 触发强平事件后，liquidation-core 能创建并执行强平订单
# 2) liquidation_trigger_execution 阶段必须 PASS（strict 模式）
# 3) 验收报告落盘，便于回溯
#
# 实现方式：复用 scripts/e2e_acceptance_suite.py 的强平阶段，
# 强制打开 --with-liquidation --strict-liquidation。

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"

API_GATEWAY="${API_GATEWAY:-http://127.0.0.1:8082}"
OMS_BASE="${OMS_BASE:-http://127.0.0.1:8081}"
MATCH_BASE="${MATCH_BASE:-http://127.0.0.1:8083}"
POSITION_INTERNAL_BASE="${POSITION_INTERNAL_BASE:-http://127.0.0.1:8086}"
LIQUIDATION_BASE="${LIQUIDATION_BASE:-http://127.0.0.1:8102}"
SYMBOL="${SYMBOL:-BTCUSDT}"
USERNAME="${R6_USERNAME:-${USERNAME:-}}"
PASSWORD="${R6_PASSWORD:-${PASSWORD:-Test@123456}}"
MAKER_USERNAME="${R6_MAKER_USERNAME:-${MAKER_USERNAME:-}}"
MAKER_PASSWORD="${R6_MAKER_PASSWORD:-${MAKER_PASSWORD:-${PASSWORD}}}"
AUTO_REGISTER="${AUTO_REGISTER:-true}"
AUTO_START="${AUTO_START:-true}"
SKIP_RUNTIME_FIX="${SKIP_RUNTIME_FIX:-false}"
SKIP_TEST_STATE_RESET="${SKIP_TEST_STATE_RESET:-true}"
REPORT_DIR="${REPORT_DIR:-test-reports}"
TRADE_QTY_INT="${TRADE_QTY_INT:-100000}"
CLOSE_QTY_INT="${CLOSE_QTY_INT:-50000}"
CANCEL_QTY_INT="${CANCEL_QTY_INT:-100000}"
LIQUIDATION_QTY_INT="${LIQUIDATION_QTY_INT:-50000}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-web3-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root123456}"
REDIS_CONTAINER="${REDIS_CONTAINER:-redis-dev}"
REDIS_PASSWORD="${REDIS_PASSWORD:-redis}"
LIQUIDATION_IDEMPOTENCY_KEY_PREFIX="${LIQUIDATION_IDEMPOTENCY_KEY_PREFIX:-liquidation:idempotency:}"

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

register_user() {
  local username="$1"
  local password="$2"
  local email="$3"
  local payload
  payload="$(cat <<JSON
{
  "username":"${username}",
  "password":"${password}",
  "email":"${email}"
}
JSON
)"

  local resp
  resp="$(curl -sS -X POST "${API_GATEWAY}/api/v1/user/register" \
    -H "Content-Type: application/json" \
    -d "${payload}")"

  local code
  code="$(echo "${resp}" | jq -r '.code // "" | tostring')"
  local user_id
  user_id="$(echo "${resp}" | jq -r '.data.userId // .data.id // ""')"
  if [[ -z "${user_id}" || "${user_id}" == "null" ]]; then
    echo "[FAIL] register missing userId for ${username}: ${resp}" >&2
    return 1
  fi
  if [[ -n "${code}" && "${code}" != "0" && "${code}" != "200" ]]; then
    echo "[FAIL] register failed for ${username}: ${resp}" >&2
    return 1
  fi
  echo "${user_id}"
}

login_user() {
  local username="$1"
  local password="$2"
  local resp
  resp="$(curl -sS -X POST "${API_GATEWAY}/api/v1/user/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"${username}\",\"password\":\"${password}\"}" || true)"
  echo "${resp}" | jq -r '.data.userId // .data.id // ""'
}

cleanup_liquidation_history() {
  local user_id="$1"
  local symbol="$2"
  [[ -n "${user_id}" && "${user_id}" != "null" ]] || return 0
  docker exec "${MYSQL_CONTAINER}" mysql "-u${MYSQL_USER}" "-p${MYSQL_PASSWORD}" -e "
DELETE FROM exchange_liquidation.t_liquidation_event
WHERE liquidation_id IN (
  SELECT liquidation_id FROM exchange_liquidation.t_liquidation_execution
  WHERE user_id=${user_id} AND symbol='${symbol}'
);
DELETE FROM exchange_liquidation.t_liquidation_execution
WHERE user_id=${user_id} AND symbol='${symbol}';
" >/dev/null 2>&1 || true
}

query_open_position_ids() {
  local user_id="$1"
  local symbol="$2"
  [[ -n "${user_id}" && "${user_id}" != "null" ]] || return 0
  docker exec "${MYSQL_CONTAINER}" mysql "-u${MYSQL_USER}" "-p${MYSQL_PASSWORD}" -Nse "
SELECT id
FROM exchange_position.position_snapshot
WHERE user_id=${user_id}
  AND symbol='${symbol}'
  AND size > 0;
" 2>/dev/null || true
}

redis_del_key() {
  local key="$1"
  local out=""
  if [[ -n "${REDIS_PASSWORD}" ]]; then
    out="$(docker exec "${REDIS_CONTAINER}" redis-cli -a "${REDIS_PASSWORD}" DEL "${key}" 2>/dev/null || true)"
    if [[ "${out}" =~ ^[0-9]+$ ]]; then
      echo "${out}"
      return 0
    fi
  fi
  out="$(docker exec "${REDIS_CONTAINER}" redis-cli DEL "${key}" 2>/dev/null || true)"
  if [[ "${out}" =~ ^[0-9]+$ ]]; then
    echo "${out}"
    return 0
  fi
  echo "${out}"
}

cleanup_liquidation_idempotency_keys() {
  local user_id="$1"
  local symbol="$2"
  [[ -n "${user_id}" && "${user_id}" != "null" ]] || return 0

  local position_ids
  position_ids="$(query_open_position_ids "${user_id}" "${symbol}")"
  if [[ -z "${position_ids}" ]]; then
    echo "[R6] no open positions found for redis idempotency cleanup, userId=${user_id}, symbol=${symbol}"
    return 0
  fi

  local count=0
  while IFS= read -r position_id; do
    [[ -n "${position_id}" ]] || continue
    local key="${LIQUIDATION_IDEMPOTENCY_KEY_PREFIX}${position_id}"
    local del_result
    del_result="$(redis_del_key "${key}")"
    echo "[R6] cleanup redis idempotency key=${key}, del=${del_result:-n/a}"
    count=$((count + 1))
  done <<< "${position_ids}"
  echo "[R6] redis idempotency cleanup done, keys_checked=${count}"
}

run_id="$(date +%s)"
if [[ -z "${USERNAME}" ]]; then
  USERNAME="r6_taker_${run_id}"
fi
if [[ -z "${MAKER_USERNAME}" ]]; then
  MAKER_USERNAME="r6_maker_${run_id}"
fi

if [[ "${AUTO_REGISTER}" == "true" ]]; then
  echo "[R6] register taker=${USERNAME}, maker=${MAKER_USERNAME}"
  register_user "${USERNAME}" "${PASSWORD}" "${USERNAME}@example.com" >/dev/null
  register_user "${MAKER_USERNAME}" "${MAKER_PASSWORD}" "${MAKER_USERNAME}@example.com" >/dev/null
fi

if [[ "${SKIP_TEST_STATE_RESET}" == "true" ]]; then
  if [[ "${AUTO_START}" == "true" ]]; then
    ./scripts/servicectl.sh start api-gateway user-core >/dev/null 2>&1 || true
  fi
  taker_user_id=""
  for _ in $(seq 1 20); do
    taker_user_id="$(login_user "${USERNAME}" "${PASSWORD}")"
    if [[ -n "${taker_user_id}" && "${taker_user_id}" != "null" ]]; then
      break
    fi
    sleep 1
  done
  cleanup_liquidation_history "${taker_user_id}" "${SYMBOL}"
  cleanup_liquidation_idempotency_keys "${taker_user_id}" "${SYMBOL}"
fi

cmd=(python3 scripts/e2e_acceptance_suite.py
  --api-gateway "${API_GATEWAY}"
  --oms-base "${OMS_BASE}"
  --match-base "${MATCH_BASE}"
  --position-internal-base "${POSITION_INTERNAL_BASE}"
  --liquidation-base "${LIQUIDATION_BASE}"
  --symbol "${SYMBOL}"
  --username "${USERNAME}"
  --password "${PASSWORD}"
  --maker-username "${MAKER_USERNAME}"
  --maker-password "${MAKER_PASSWORD}"
  --trade-qty-int "${TRADE_QTY_INT}"
  --close-qty-int "${CLOSE_QTY_INT}"
  --cancel-qty-int "${CANCEL_QTY_INT}"
  --liquidation-qty-int "${LIQUIDATION_QTY_INT}"
  --with-liquidation
  --strict-liquidation
  --skip-pnl-push
  --report-dir "${REPORT_DIR}"
)

if [[ "${AUTO_START}" == "true" ]]; then
  cmd+=(--auto-start)
fi
if [[ "${SKIP_RUNTIME_FIX}" == "true" ]]; then
  cmd+=(--skip-runtime-fix)
fi
if [[ "${SKIP_TEST_STATE_RESET}" == "true" ]]; then
  cmd+=(--skip-test-state-reset)
fi

echo "[R6] run liquidation e2e: ${cmd[*]}"
tmp_log="$(mktemp /tmp/r6-e2e-log.XXXXXX)"
set +e
"${cmd[@]}" 2>&1 | tee "${tmp_log}"
suite_rc=$?
set -e

report_json="$(awk '/Details: /{print $NF}' "${tmp_log}" | tail -n 1)"
if [[ -z "${report_json}" || ! -f "${report_json}" ]]; then
  report_json="$(ls -1t "${REPORT_DIR}"/acceptance-suite-*.json 2>/dev/null | head -n 1 || true)"
fi
if [[ -z "${report_json}" || ! -f "${report_json}" ]]; then
  echo "[FAIL] R6 cannot locate acceptance report json, suite_rc=${suite_rc}"
  exit 1
fi

liq_ok="$(jq -r '[.steps[] | select(.name=="liquidation_trigger_execution") | .ok][0] // "false"' "${report_json}")"
if [[ "${liq_ok}" != "true" ]]; then
  echo "[FAIL] R6 liquidation_trigger_execution not passed, suite_rc=${suite_rc}, report=${report_json}"
  exit 1
fi

echo "[R6] PASS liquidation_trigger_execution, report=${report_json}"
