#!/usr/bin/env bash
set -euo pipefail

# PACK-04 B5 验收：K线权威化 + 去重 + 冲突记录 + 水位恢复基础能力

MYSQL_CONTAINER="${MYSQL_CONTAINER:-web3-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root123456}"
MARKET_DB="${MARKET_DB:-exchange_market}"

KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka-1}"
KAFKA_BROKER="${KAFKA_BROKER:-localhost:9092}"
CONSUMER_WARMUP_SECONDS="${CONSUMER_WARMUP_SECONDS:-12}"

SOURCE="${SOURCE:-binance}"
SYMBOL="${SYMBOL:-BTCUSDT}"
INTERVAL="${INTERVAL:-1m}"
TOPIC="market.ext.${SOURCE}.kline.${SYMBOL}.${INTERVAL}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[FAIL] missing command: $1"
    exit 1
  fi
}

require_cmd docker
require_cmd date

mysql_exec() {
  local sql="$1"
  docker exec "${MYSQL_CONTAINER}" mysql "-u${MYSQL_USER}" "-p${MYSQL_PASSWORD}" -Nse "${sql}"
}

produce_kline() {
  local open_time="$1"
  local close_time="$2"
  local close_price="$3"

  local payload
  payload="$(cat <<JSON
{"e":"kline","E":${close_time},"s":"${SYMBOL}","k":{"t":${open_time},"T":${close_time},"s":"${SYMBOL}","i":"${INTERVAL}","o":"50000.00000000","c":"${close_price}","h":"50020.00000000","l":"49980.00000000","v":"1.20000000","q":"60000.00000000","n":123,"V":"0.80000000","Q":"40000.00000000","x":true}}
JSON
)"

  if ! printf '%s\n' "${payload}" | docker exec -i "${KAFKA_CONTAINER}" \
    kafka-console-producer \
      --bootstrap-server "${KAFKA_BROKER}" \
      --topic "${TOPIC}" \
      --producer-property max.block.ms=5000 \
      --producer-property request.timeout.ms=5000 \
      --producer-property delivery.timeout.ms=7000 \
      --producer-property retries=0 >/dev/null; then
    echo "[WARN] failed to produce kline payload, topic=${TOPIC}, openTime=${open_time}" >&2
  fi
}

wait_until() {
  local cmd="$1"
  local retries="${2:-30}"
  local interval="${3:-1}"

  for _ in $(seq 1 "${retries}"); do
    if eval "${cmd}"; then
      return 0
    fi
    sleep "${interval}"
  done
  return 1
}

wait_authority_with_replay() {
  local retries="${1:-40}"
  local interval="${2:-1}"
  local healthy_price="${3:-50010.00000000}"
  local conflict_price="${4:-50030.00000000}"

  for i in $(seq 1 "${retries}"); do
    local count
    count="$(mysql_exec "SELECT COUNT(*) FROM ${MARKET_DB}.t_kline_authority WHERE source='${SOURCE}' AND symbol='${SYMBOL}' AND interval_val='${INTERVAL}' AND open_time=${open_time};")"
    if [[ "${count}" =~ ^[0-9]+$ ]] && [[ "${count}" -ge 1 ]]; then
      return 0
    fi

    # 启动阶段 consumer 可能尚未订阅成功，按固定节奏重放同一根K线，确保最终可观测。
    if (( i % 5 == 0 )); then
      produce_kline "${open_time}" "${close_time}" "${healthy_price}"
      produce_kline "${open_time}" "${close_time}" "${conflict_price}"
    fi
    sleep "${interval}"
  done

  return 1
}

wait_conflict_with_replay() {
  local retries="${1:-40}"
  local interval="${2:-1}"
  local conflict_price="${3:-50030.00000000}"

  for i in $(seq 1 "${retries}"); do
    local count
    count="$(mysql_exec "SELECT COUNT(*) FROM ${MARKET_DB}.t_kline_conflict WHERE source='${SOURCE}' AND symbol='${SYMBOL}' AND interval_val='${INTERVAL}' AND open_time=${open_time};")"
    if [[ "${count}" =~ ^[0-9]+$ ]] && [[ "${count}" -ge 1 ]]; then
      return 0
    fi

    if (( i % 5 == 0 )); then
      produce_kline "${open_time}" "${close_time}" "${conflict_price}"
    fi
    sleep "${interval}"
  done

  return 1
}

# 采用未来窗口作为测试键，避免与 Binance 回补/实时流同一 open_time 冲突。
now_ms="$(($(date +%s) * 1000))"
open_time="$(( (now_ms / 60000 + 120) * 60000 ))"
close_time="$((open_time + 59999))"

echo "[B5] topic=${TOPIC}, openTime=${open_time}, closeTime=${close_time}"

if [[ "${CONSUMER_WARMUP_SECONDS}" =~ ^[0-9]+$ ]] && (( CONSUMER_WARMUP_SECONDS > 0 )); then
  echo "[B5] waiting consumer warmup ${CONSUMER_WARMUP_SECONDS}s"
  sleep "${CONSUMER_WARMUP_SECONDS}"
fi

# 清理测试键位，避免历史噪音
docker exec "${MYSQL_CONTAINER}" mysql "-u${MYSQL_USER}" "-p${MYSQL_PASSWORD}" -e "
DELETE FROM ${MARKET_DB}.t_kline_conflict
 WHERE source='${SOURCE}' AND symbol='${SYMBOL}' AND interval_val='${INTERVAL}' AND open_time=${open_time};
DELETE FROM ${MARKET_DB}.t_kline_authority
 WHERE source='${SOURCE}' AND symbol='${SYMBOL}' AND interval_val='${INTERVAL}' AND open_time=${open_time};
DELETE FROM ${MARKET_DB}.t_kline_backfill_watermark
 WHERE source='${SOURCE}' AND symbol='${SYMBOL}' AND interval_val='${INTERVAL}';
" >/dev/null

echo "[B5] produce first closed candle"
produce_kline "${open_time}" "${close_time}" "50010.00000000"

echo "[B5] replay duplicate closed candle"
produce_kline "${open_time}" "${close_time}" "50010.00000000"

echo "[B5] replay conflicting closed candle (different close price)"
produce_kline "${open_time}" "${close_time}" "50030.00000000"

if ! wait_authority_with_replay 40 1 "50010.00000000" "50030.00000000"; then
  echo "[FAIL] authority row not observed in time"
  exit 1
fi

authority_count="$(mysql_exec "SELECT COUNT(*) FROM ${MARKET_DB}.t_kline_authority WHERE source='${SOURCE}' AND symbol='${SYMBOL}' AND interval_val='${INTERVAL}' AND open_time=${open_time};")"
if [[ "${authority_count}" != "1" ]]; then
  echo "[FAIL] authority dedup failed, expected 1 row, got ${authority_count}"
  exit 1
fi
authority_close_price="$(mysql_exec "SELECT close_price FROM ${MARKET_DB}.t_kline_authority WHERE source='${SOURCE}' AND symbol='${SYMBOL}' AND interval_val='${INTERVAL}' AND open_time=${open_time} LIMIT 1;")"
if [[ "${authority_close_price}" != "5001000000000" ]]; then
  echo "[FAIL] authority row mismatch, expected close_price=5001000000000, got ${authority_close_price}"
  exit 1
fi
echo "[PASS] authority dedup ok, rowCount=${authority_count}"

if ! wait_conflict_with_replay 40 1 "50030.00000000"; then
  echo "[FAIL] conflict row not observed in time"
  exit 1
fi

conflict_count="$(mysql_exec "SELECT COUNT(*) FROM ${MARKET_DB}.t_kline_conflict WHERE source='${SOURCE}' AND symbol='${SYMBOL}' AND interval_val='${INTERVAL}' AND open_time=${open_time};")"
echo "[PASS] conflict capture ok, conflictCount=${conflict_count}"

watermark="$(mysql_exec "SELECT COALESCE(last_closed_open_time,0) FROM ${MARKET_DB}.t_kline_backfill_watermark WHERE source='${SOURCE}' AND symbol='${SYMBOL}' AND interval_val='${INTERVAL}' LIMIT 1;")"
if [[ -z "${watermark}" || "${watermark}" == "0" ]]; then
  echo "[FAIL] watermark not updated"
  exit 1
fi
if (( watermark < open_time )); then
  echo "[FAIL] watermark behind openTime, watermark=${watermark}, openTime=${open_time}"
  exit 1
fi
echo "[PASS] watermark updated, value=${watermark}"

dup_rows="$(mysql_exec "SELECT COUNT(*) FROM (SELECT source,symbol,interval_val,open_time,COUNT(*) c FROM ${MARKET_DB}.t_kline_authority WHERE source='${SOURCE}' AND symbol='${SYMBOL}' AND interval_val='${INTERVAL}' AND open_time=${open_time} GROUP BY source,symbol,interval_val,open_time HAVING c>1) t;")"
if [[ "${dup_rows}" != "0" ]]; then
  echo "[FAIL] authority duplicate rows detected, duplicateGroups=${dup_rows}"
  exit 1
fi

echo "[B5] PASS kline recovery & dedup basic checks"
