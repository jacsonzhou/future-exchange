#!/usr/bin/env bash
set -euo pipefail

# 全链路回归入口：
# 注册/初始化资金 -> C8 资金主链(暂扣/撤单/成交/持仓) -> R2 撤单解冻(账本分录) -> PNL 推送 -> R6 强平 E2E

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"

API_GATEWAY="${API_GATEWAY:-http://127.0.0.1:8082}"
OMS_BASE="${OMS_BASE:-http://127.0.0.1:8081}"
MATCH_BASE="${MATCH_BASE:-http://127.0.0.1:8083}"
SNAPSHOT_BASE="${SNAPSHOT_BASE:-http://127.0.0.1:8085}"
POSITION_INTERNAL_BASE="${POSITION_INTERNAL_BASE:-http://127.0.0.1:8086}"
LIQUIDATION_BASE="${LIQUIDATION_BASE:-http://127.0.0.1:8102}"
BINANCE_BASE_URL="${BINANCE_BASE_URL:-http://127.0.0.1:8105}"
SYMBOL="${SYMBOL:-BTCUSDT}"
PASSWORD="${PASSWORD:-Test@123456}"
RUN_ACCEPTANCE="${RUN_ACCEPTANCE:-true}"
RUN_C8="${RUN_C8:-true}"
RUN_R2="${RUN_R2:-true}"
RUN_R6="${RUN_R6:-${RUN_ACCEPTANCE}}"
RUN_PNL_PUSH="${RUN_PNL_PUSH:-${RUN_ACCEPTANCE}}"
WITH_LIQUIDATION="${WITH_LIQUIDATION:-true}"
STRICT_LIQUIDATION="${STRICT_LIQUIDATION:-true}"
AUTO_START="${AUTO_START:-true}"

timestamp() {
  date '+%Y-%m-%d %H:%M:%S'
}

log() {
  echo "[$(timestamp)] $*"
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing command: $1" >&2
    exit 2
  fi
}

require_cmd curl
require_cmd jq
require_cmd python3
require_cmd bash

start_minimal_services() {
  local modules=(
    "match-engine-core"
    "ledger-core"
    "oms-core"
    "snapshot-account-core"
    "position-snapshot-core"
    "market-price-core"
    "public-push-core"
    "private-push-core"
    "api-gateway"
    "user-core"
    "index-price-core"
    "mark-price-core"
    "liquidation-core"
    "margin-mode-core"
    "binance-data-source"
    "cfd-dealer-core"
  )
  log "Auto start minimal CFD regression services"
  ./scripts/servicectl.sh start "${modules[@]}"
}

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
    -d "{\"username\":\"${username}\",\"password\":\"${password}\"}")"

  local token
  token="$(echo "${resp}" | jq -r '.data.token // .data.accessToken // ""')"
  local user_id
  user_id="$(echo "${resp}" | jq -r '.data.userId // .data.id // ""')"
  if [[ -z "${token}" || -z "${user_id}" || "${token}" == "null" || "${user_id}" == "null" ]]; then
    echo "[FAIL] login failed for ${username}: ${resp}" >&2
    return 1
  fi
  echo "${token}:${user_id}"
}

ensure_initial_funding() {
  local token="$1"
  local user_id="$2"

  local retries=30
  local i
  for ((i=1; i<=retries; i++)); do
    local resp
    resp="$(curl -sS "${API_GATEWAY}/api/v1/account/balance" \
      -H "Authorization: Bearer ${token}" \
      -H "X-User-Id: ${user_id}")"
    local equity
    equity="$(echo "${resp}" | jq -r '.equity // .data.equity // "0"')"
    local ok
    ok="$(python3 - "$equity" <<'PY'
from decimal import Decimal
import sys
print("true" if Decimal(sys.argv[1]) > Decimal("0") else "false")
PY
)"
    if [[ "${ok}" == "true" ]]; then
      log "Initial funding ready, userId=${user_id}, equity=${equity}"
      return 0
    fi
    sleep 1
  done

  echo "[FAIL] initial funding not observed in time, userId=${user_id}" >&2
  return 1
}

run_r2_cancel_unfreeze() {
  local user_id="$1"
  log "Run R2 cancel/unfreeze, userId=${user_id}"
  USER_ID="${user_id}" \
  SYMBOL="${SYMBOL}" \
  OMS_BASE_URL="${OMS_BASE}" \
  SNAPSHOT_BASE_URL="${SNAPSHOT_BASE}" \
  BINANCE_BASE_URL="${BINANCE_BASE_URL}" \
    bash scripts/cfd/test_r2_cancel_unfreeze.sh
}

run_c8_funding_prehold_flow() {
  local username="$1"
  local password="$2"
  log "Run C8 funding/prehold/fill flow, username=${username}"
  API_GATEWAY="${API_GATEWAY}" \
  OMS_BASE_URL="${OMS_BASE}" \
  BINANCE_BASE_URL="${BINANCE_BASE_URL}" \
  SYMBOL="${SYMBOL}" \
  USERNAME="${username}" \
  PASSWORD="${password}" \
    bash scripts/cfd/test_c8_funding_prehold_flow.sh
}

run_r6_liquidation_e2e() {
  local username="$1"
  local password="$2"
  local maker_username="$3"
  local maker_password="$4"

  if [[ "${WITH_LIQUIDATION}" != "true" ]]; then
    log "Skip R6 liquidation e2e, WITH_LIQUIDATION=${WITH_LIQUIDATION}"
    return 0
  fi
  if [[ "${STRICT_LIQUIDATION}" != "true" ]]; then
    log "WARN STRICT_LIQUIDATION=${STRICT_LIQUIDATION}, R6 脚本将固定按 strict 执行"
  fi

  log "Run R6 liquidation e2e, taker=${username}, maker=${maker_username}"
  API_GATEWAY="${API_GATEWAY}" \
  OMS_BASE="${OMS_BASE}" \
  MATCH_BASE="${MATCH_BASE}" \
  POSITION_INTERNAL_BASE="${POSITION_INTERNAL_BASE}" \
  LIQUIDATION_BASE="${LIQUIDATION_BASE}" \
  SYMBOL="${SYMBOL}" \
  USERNAME="${username}" \
  PASSWORD="${password}" \
  MAKER_USERNAME="${maker_username}" \
  MAKER_PASSWORD="${maker_password}" \
  AUTO_REGISTER="false" \
  AUTO_START="${AUTO_START}" \
  SKIP_TEST_STATE_RESET="true" \
    bash scripts/cfd/test_r6_liquidation_e2e.sh
}

run_pnl_push_check() {
  local username="$1"
  local password="$2"
  log "Run PnL push check"
  USERNAME="${username}" PASSWORD="${password}" SYMBOL="${SYMBOL}" \
    python3 scripts/test_position_up_mark_push.py
}

main() {
  if [[ "${AUTO_START}" == "true" ]]; then
    start_minimal_services
  else
    log "Skip auto start services, AUTO_START=${AUTO_START}"
  fi

  local run_id
  run_id="$(date +%s)"
  local taker_username="cfd_taker_${run_id}"
  local maker_username="cfd_maker_${run_id}"
  local taker_email="${taker_username}@example.com"
  local maker_email="${maker_username}@example.com"

  log "Register taker user: ${taker_username}"
  taker_user_id="$(register_user "${taker_username}" "${PASSWORD}" "${taker_email}")"
  log "Register maker user: ${maker_username}"
  maker_user_id="$(register_user "${maker_username}" "${PASSWORD}" "${maker_email}")"
  log "Registered users: taker=${taker_user_id}, maker=${maker_user_id}"

  taker_login="$(login_user "${taker_username}" "${PASSWORD}")"
  maker_login="$(login_user "${maker_username}" "${PASSWORD}")"
  taker_token="${taker_login%%:*}"
  maker_token="${maker_login%%:*}"

  ensure_initial_funding "${taker_token}" "${taker_user_id}"
  ensure_initial_funding "${maker_token}" "${maker_user_id}"

  if [[ "${RUN_C8}" == "true" ]]; then
    run_c8_funding_prehold_flow "${taker_username}" "${PASSWORD}"
  else
    log "Skip C8 funding/prehold/fill flow, RUN_C8=${RUN_C8}"
  fi

  if [[ "${RUN_R2}" == "true" ]]; then
    run_r2_cancel_unfreeze "${taker_user_id}"
  fi

  if [[ "${RUN_PNL_PUSH}" == "true" ]]; then
    run_pnl_push_check "${taker_username}" "${PASSWORD}"
  fi

  if [[ "${RUN_R6}" == "true" ]]; then
    run_r6_liquidation_e2e "${taker_username}" "${PASSWORD}" "${maker_username}" "${PASSWORD}"
  fi

  log "Full chain regression PASS"
  log "Users: taker=${taker_username}, maker=${maker_username}"
}

main "$@"
