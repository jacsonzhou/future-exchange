#!/usr/bin/env bash
set -euo pipefail

# C2 验收：
# 1) /api/binance/reference-book/{symbol} 连续返回 success=true
# 2) bestBid / bestAsk 非空
# 3) stalenessMs 小于阈值（默认 1500ms）
# 4) topic / offset 可追溯字段存在

BASE_URL="${BASE_URL:-http://127.0.0.1:8105}"
SYMBOL="${SYMBOL:-BTCUSDT}"
DEPTH="${DEPTH:-20}"
SAMPLES="${SAMPLES:-30}"
INTERVAL_MS="${INTERVAL_MS:-200}"
MAX_STALENESS_MS="${MAX_STALENESS_MS:-1500}"

if ! command -v jq >/dev/null 2>&1; then
  echo "[FAIL] jq is required"
  exit 1
fi

fail_count=0
echo "[C2] probing ${BASE_URL}/api/binance/reference-book/${SYMBOL}?depth=${DEPTH}"

for ((i = 1; i <= SAMPLES; i++)); do
  response="$(curl -sS "${BASE_URL}/api/binance/reference-book/${SYMBOL}?depth=${DEPTH}")" || {
    echo "[FAIL] request failed at sample #${i}"
    fail_count=$((fail_count + 1))
    sleep 1
    continue
  }

  success="$(echo "${response}" | jq -r '.success // false')"
  best_bid="$(echo "${response}" | jq -r '.data.bestBid // empty')"
  best_ask="$(echo "${response}" | jq -r '.data.bestAsk // empty')"
  staleness_ms="$(echo "${response}" | jq -r '.data.stalenessMs // 999999999')"
  topic="$(echo "${response}" | jq -r '.data.topic // empty')"
  offset="$(echo "${response}" | jq -r '.data.offset // empty')"

  sample_fail=0
  if [[ "${success}" != "true" ]]; then
    sample_fail=1
  fi
  if [[ -z "${best_bid}" || -z "${best_ask}" ]]; then
    sample_fail=1
  fi
  if [[ -z "${topic}" || -z "${offset}" ]]; then
    sample_fail=1
  fi
  if [[ "${staleness_ms}" =~ ^[0-9]+$ ]]; then
    if (( staleness_ms > MAX_STALENESS_MS )); then
      sample_fail=1
    fi
  else
    sample_fail=1
  fi

  if (( sample_fail == 1 )); then
    fail_count=$((fail_count + 1))
    echo "[FAIL] #${i}: success=${success}, bestBid=${best_bid}, bestAsk=${best_ask}, stalenessMs=${staleness_ms}, topic=${topic}, offset=${offset}"
  else
    echo "[PASS] #${i}: bid=${best_bid}, ask=${best_ask}, stalenessMs=${staleness_ms}, offset=${offset}"
  fi

  sleep "$(awk "BEGIN {print ${INTERVAL_MS}/1000}")"
done

if (( fail_count > 0 )); then
  echo "[C2] FAIL: ${fail_count}/${SAMPLES} samples failed"
  exit 1
fi

echo "[C2] PASS"
