#!/usr/bin/env bash
set -euo pipefail

# C9 验收（保险基金契约闭环）：
# 1) /internal/insurance-fund/income 可增资
# 2) /internal/insurance-fund/expense 可扣减
# 3) 同一 bizSeq 重放幂等（不重复扣减）

ADL_BASE="${ADL_BASE:-http://127.0.0.1:8103}"
SYMBOL="${SYMBOL:-BTCUSDT}"
TOPUP_AMOUNT_RAW="${TOPUP_AMOUNT_RAW:-200000000000}"   # 2000 USDT
EXPENSE_AMOUNT_RAW="${EXPENSE_AMOUNT_RAW:-50000000000}" # 500 USDT

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[FAIL] missing command: $1"
    exit 1
  fi
}

require_cmd curl
require_cmd jq

get_balance_raw() {
  local resp
  resp="$(curl -sS -X POST "${ADL_BASE}/internal/insurance-fund/balance?symbol=${SYMBOL}")"
  local ok
  ok="$(echo "${resp}" | jq -r '.success // false')"
  if [[ "${ok}" != "true" ]]; then
    echo "[FAIL] get balance failed: ${resp}" >&2
    exit 1
  fi
  echo "${resp}" | jq -r '.balance // 0'
}

post_income() {
  local biz_seq="$1"
  curl -sS -X POST "${ADL_BASE}/internal/insurance-fund/income?bizSeq=${biz_seq}" \
    -H "Content-Type: application/json" \
    -d "{\"symbol\":\"${SYMBOL}\",\"amount\":${TOPUP_AMOUNT_RAW},\"reason\":\"C9 topup ${biz_seq}\"}"
}

post_expense() {
  local biz_seq="$1"
  curl -sS -X POST "${ADL_BASE}/internal/insurance-fund/expense?bizSeq=${biz_seq}" \
    -H "Content-Type: application/json" \
    -d "{\"symbol\":\"${SYMBOL}\",\"amount\":${EXPENSE_AMOUNT_RAW},\"reason\":\"C9 expense ${biz_seq}\"}"
}

assert_ge() {
  local a="$1"
  local b="$2"
  local msg="$3"
  if (( a < b )); then
    echo "[FAIL] ${msg}: ${a} < ${b}"
    exit 1
  fi
}

assert_eq() {
  local a="$1"
  local b="$2"
  local msg="$3"
  if [[ "${a}" != "${b}" ]]; then
    echo "[FAIL] ${msg}: ${a} != ${b}"
    exit 1
  fi
}

run_id="$(date +%s)"
income_seq="c9-income-${SYMBOL}-${run_id}"
expense_seq="c9-expense-${SYMBOL}-${run_id}"

before_balance="$(get_balance_raw)"
echo "[C9] before balance=${before_balance}"

income_resp="$(post_income "${income_seq}")"
income_ok="$(echo "${income_resp}" | jq -r '.success // false')"
if [[ "${income_ok}" != "true" ]]; then
  echo "[FAIL] income failed: ${income_resp}"
  exit 1
fi
income_amount="$(echo "${income_resp}" | jq -r '.incomeAmount // 0')"
assert_eq "${income_amount}" "${TOPUP_AMOUNT_RAW}" "income amount mismatch"
echo "[PASS] income accepted, amount=${income_amount}"

after_income_balance="$(get_balance_raw)"
delta_income=$(( after_income_balance - before_balance ))
assert_ge "${delta_income}" "${TOPUP_AMOUNT_RAW}" "balance delta after income is too small"
echo "[PASS] balance increased after income, delta=${delta_income}"

expense_resp="$(post_expense "${expense_seq}")"
expense_ok="$(echo "${expense_resp}" | jq -r '.success // false')"
if [[ "${expense_ok}" != "true" ]]; then
  echo "[FAIL] expense failed: ${expense_resp}"
  exit 1
fi
cover_amount="$(echo "${expense_resp}" | jq -r '.coverAmount // 0')"
assert_eq "${cover_amount}" "${EXPENSE_AMOUNT_RAW}" "cover amount mismatch"
echo "[PASS] expense accepted, coverAmount=${cover_amount}"

after_expense_balance="$(get_balance_raw)"
delta_expense=$(( after_income_balance - after_expense_balance ))
assert_ge "${delta_expense}" "${EXPENSE_AMOUNT_RAW}" "balance delta after expense is too small"
echo "[PASS] balance decreased after expense, delta=${delta_expense}"

# 幂等重放：同一 bizSeq 不应重复扣减
expense_retry_resp="$(post_expense "${expense_seq}")"
retry_ok="$(echo "${expense_retry_resp}" | jq -r '.success // false')"
if [[ "${retry_ok}" != "true" ]]; then
  echo "[FAIL] expense retry failed: ${expense_retry_resp}"
  exit 1
fi
retry_idempotent="$(echo "${expense_retry_resp}" | jq -r '.idempotent // false')"
assert_eq "${retry_idempotent}" "true" "expense retry should be idempotent"

after_retry_balance="$(get_balance_raw)"
assert_eq "${after_retry_balance}" "${after_expense_balance}" "balance should not change on idempotent retry"
echo "[PASS] expense idempotent replay verified"

echo "[C9] PASS insurance fund contract closed"
