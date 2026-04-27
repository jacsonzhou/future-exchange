#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT_DIR"

SYMBOL="${1:-${SYMBOL:-BTCUSDT}}"
DO_RESTART="${DO_RESTART:-true}"
WAIT_AFTER_RESTART_SEC="${WAIT_AFTER_RESTART_SEC:-12}"
REQUIRE_BINANCE_CONNECTED="${REQUIRE_BINANCE_CONNECTED:-true}"
STRICT_KLINE="${STRICT_KLINE:-false}"

DEPTH_MAX_LATENCY_SEC="${DEPTH_MAX_LATENCY_SEC:-5}"
TRADE_MAX_LATENCY_SEC="${TRADE_MAX_LATENCY_SEC:-5}"
KLINE_MAX_LATENCY_SEC="${KLINE_MAX_LATENCY_SEC:-180}"

REDIS_CONTAINER="${REDIS_CONTAINER:-redis-dev}"
BINANCE_STATUS_URL="${BINANCE_STATUS_URL:-http://127.0.0.1:8105/api/binance/status}"
HEALTH_8105="${HEALTH_8105:-http://127.0.0.1:8105/actuator/health}"
HEALTH_8095="${HEALTH_8095:-http://127.0.0.1:8095/actuator/health}"
HEALTH_8096="${HEALTH_8096:-http://127.0.0.1:8096/actuator/health}"

FAIL_COUNT=0
SNAPSHOT_KEY=""
SNAPSHOT_JSON=""

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[FAIL] missing command: $1"
    exit 1
  fi
}

is_true() {
  case "${1:-}" in
    true|TRUE|True|1|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

mark_fail() {
  echo "[FAIL] $*"
  FAIL_COUNT=$((FAIL_COUNT + 1))
}

mark_warn() {
  echo "[WARN] $*"
}

mark_pass() {
  echo "[PASS] $*"
}

to_wall_time() {
  local ts_ms="$1"
  if [[ -z "$ts_ms" || "$ts_ms" -le 0 ]]; then
    printf 'n/a'
    return
  fi
  local sec=$((ts_ms / 1000))
  if date -r "$sec" '+%Y-%m-%d %H:%M:%S %Z' >/dev/null 2>&1; then
    date -r "$sec" '+%Y-%m-%d %H:%M:%S %Z'
  else
    date -d "@$sec" '+%Y-%m-%d %H:%M:%S %Z'
  fi
}

check_health() {
  local name="$1"
  local url="$2"
  local resp
  resp="$(curl -sS --max-time 3 "$url" || true)"
  if echo "$resp" | jq -e '.status == "UP"' >/dev/null 2>&1; then
    mark_pass "${name} health=UP"
  else
    mark_fail "${name} health check failed, url=${url}, resp=${resp:-<empty>}"
  fi
}

fetch_snapshot_by_keys() {
  SNAPSHOT_KEY=""
  SNAPSHOT_JSON=""
  local key
  for key in "$@"; do
    local raw normalized
    raw="$(docker exec "$REDIS_CONTAINER" redis-cli --raw GET "$key" 2>/dev/null || true)"
    if [[ -z "$raw" ]]; then
      continue
    fi
    normalized="$(printf '%s' "$raw" | jq -cer 'if type=="string" then (fromjson? // empty) else . end' 2>/dev/null || true)"
    if [[ -n "$normalized" ]]; then
      SNAPSHOT_KEY="$key"
      SNAPSHOT_JSON="$normalized"
      return 0
    fi
  done
  return 1
}

check_latency() {
  local stream="$1"
  local threshold_sec="$2"
  local strict="$3"
  local ts_jq="$4"
  shift 4
  local keys=("$@")

  if ! fetch_snapshot_by_keys "${keys[@]}"; then
    if is_true "$strict"; then
      mark_fail "${stream} snapshot missing, keys=${keys[*]}"
    else
      mark_warn "${stream} snapshot missing, keys=${keys[*]}"
    fi
    return
  fi

  local ts_ms now_ms latency_sec wall
  ts_ms="$(printf '%s' "$SNAPSHOT_JSON" | jq -r "$ts_jq // 0" 2>/dev/null || echo 0)"
  if [[ -z "$ts_ms" || "$ts_ms" == "null" || ! "$ts_ms" =~ ^[0-9]+$ ]]; then
    ts_ms=0
  fi
  now_ms="$(( $(date +%s) * 1000 ))"

  if (( ts_ms <= 0 )); then
    if is_true "$strict"; then
      mark_fail "${stream} timestamp invalid, key=${SNAPSHOT_KEY}"
    else
      mark_warn "${stream} timestamp invalid, key=${SNAPSHOT_KEY}"
    fi
    return
  fi

  if (( now_ms < ts_ms )); then
    latency_sec=0
  else
    latency_sec=$(( (now_ms - ts_ms) / 1000 ))
  fi

  wall="$(to_wall_time "$ts_ms")"
  echo "[METRIC] ${stream} key=${SNAPSHOT_KEY} tsMs=${ts_ms} wall='${wall}' latencySec=${latency_sec} thresholdSec=${threshold_sec}"

  if (( latency_sec <= threshold_sec )); then
    mark_pass "${stream} latency ${latency_sec}s <= ${threshold_sec}s"
  else
    if is_true "$strict"; then
      mark_fail "${stream} latency ${latency_sec}s > ${threshold_sec}s"
    else
      mark_warn "${stream} latency ${latency_sec}s > ${threshold_sec}s (non-strict)"
    fi
  fi
}

require_cmd curl
require_cmd jq
require_cmd docker
require_cmd date

echo "[INFO] Market realtime freshness acceptance"
echo "[INFO] symbol=${SYMBOL}, restart=${DO_RESTART}, requireBinanceConnected=${REQUIRE_BINANCE_CONNECTED}, strictKline=${STRICT_KLINE}"
echo "[INFO] thresholds: depth=${DEPTH_MAX_LATENCY_SEC}s, trade=${TRADE_MAX_LATENCY_SEC}s, kline=${KLINE_MAX_LATENCY_SEC}s"

if is_true "$DO_RESTART"; then
  echo "[STEP] restart binance-data-source + market-price-core + public-push-core"
  ./scripts/servicectl.sh restart binance-data-source market-price-core public-push-core
  sleep "$WAIT_AFTER_RESTART_SEC"
fi

echo "[STEP] service health checks"
check_health "binance-data-source(8105)" "$HEALTH_8105"
check_health "market-price-core(8095)" "$HEALTH_8095"
check_health "public-push-core(8096)" "$HEALTH_8096"

echo "[STEP] binance websocket status"
status_resp="$(curl -sS --max-time 4 "$BINANCE_STATUS_URL" || true)"
if [[ -z "$status_resp" ]]; then
  mark_fail "binance status api unavailable: ${BINANCE_STATUS_URL}"
else
  connected="$(echo "$status_resp" | jq -r '.connected // false' 2>/dev/null || echo false)"
  reconnect_total="$(echo "$status_resp" | jq -r '.reconnectCount // 0' 2>/dev/null || echo 0)"
  reconnect_consecutive="$(echo "$status_resp" | jq -r '.reconnectConsecutive // -1' 2>/dev/null || echo -1)"
  disconnected_duration="$(echo "$status_resp" | jq -r '.disconnectedDurationSeconds // 0' 2>/dev/null || echo 0)"
  echo "[METRIC] binance.connected=${connected} reconnectTotal=${reconnect_total} reconnectConsecutive=${reconnect_consecutive} disconnectedDurationSec=${disconnected_duration}"
  if is_true "$REQUIRE_BINANCE_CONNECTED" && [[ "$connected" != "true" ]]; then
    mark_fail "binance websocket is disconnected"
  fi
fi

echo "[STEP] redis snapshot latency checks"
check_latency "depth" "$DEPTH_MAX_LATENCY_SEC" "true" '.ingestTime // .E // .T' \
  "market:snapshot:depth:${SYMBOL}" \
  "market:snapshot:depth:ext:binance:${SYMBOL}"

check_latency "trade" "$TRADE_MAX_LATENCY_SEC" "true" '.T // .E' \
  "market:snapshot:trade:${SYMBOL}" \
  "market:snapshot:trade:ext:binance:${SYMBOL}"

check_latency "kline.1m" "$KLINE_MAX_LATENCY_SEC" "$STRICT_KLINE" '.E // .k.T // .T' \
  "market:snapshot:kline:${SYMBOL}:1m" \
  "market:snapshot:kline:ext:binance:${SYMBOL}:1m"

if (( FAIL_COUNT > 0 )); then
  echo "[RESULT] FAIL realtime freshness checks, failCount=${FAIL_COUNT}"
  exit 1
fi

echo "[RESULT] PASS realtime freshness checks"
