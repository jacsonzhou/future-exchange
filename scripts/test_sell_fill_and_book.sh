#!/usr/bin/env bash

set -euo pipefail

NACOS_ADDR="${NACOS_ADDR:-http://localhost:8848/nacos}"
NACOS_USERNAME="${NACOS_USERNAME:-nacos}"
NACOS_PASSWORD="${NACOS_PASSWORD:-nacos}"
NACOS_GROUP="${NACOS_GROUP:-DEFAULT_GROUP}"
API_GATEWAY="${API_GATEWAY:-}"
OMS_BASE="${OMS_BASE:-}"
MATCH_BASE="${MATCH_BASE:-}"
MATCH_STATS_URL="${MATCH_STATS_URL:-}"
SYMBOL="${SYMBOL:-BTCUSDT}"
USERNAME="${USERNAME:-zhoufan6}"
PASSWORD="${PASSWORD:-123456}"
PRICE_INT="${PRICE_INT:-5000000000000}"     # 50000 * 1e8
QTY_INT="${QTY_INT:-100000000}"             # 1 * 1e8
LEVERAGE="${LEVERAGE:-10}"
POLL_TIMES="${POLL_TIMES:-20}"
POLL_INTERVAL="${POLL_INTERVAL:-1}"
SEED_BUY_FIRST="${SEED_BUY_FIRST:-true}"
POSITION_POLL_TIMES="${POSITION_POLL_TIMES:-20}"
POSITION_POLL_INTERVAL="${POSITION_POLL_INTERVAL:-1}"
TARGET_SIDE="${TARGET_SIDE:-SELL}"   # SELL | BUY

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log() {
  echo -e "$1"
}

warn() {
  echo -e "$1" >&2
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    log "${RED}Missing required command: $1${NC}"
    exit 1
  }
}

urlencode() {
  local raw="$1"
  jq -rn --arg v "$raw" '$v|@uri'
}

to_dec8() {
  local raw_int="$1"
  awk -v v="$raw_int" 'BEGIN { printf "%.8f", v / 100000000 }'
}

get_nacos_token() {
  local resp token
  resp=$(curl -sS --max-time 8 -X POST "${NACOS_ADDR}/v1/auth/login" \
    -d "username=${NACOS_USERNAME}&password=${NACOS_PASSWORD}" || true)
  token=$(echo "$resp" | jq -r '.accessToken // empty' 2>/dev/null || true)
  if [[ -z "$token" || "$token" == "null" ]]; then
    warn "${RED}Nacos login failed: ${resp}${NC}"
    return 1
  fi
  echo "$token"
}

resolve_service_base_from_nacos() {
  local service_name="$1"
  local token="$2"
  local encoded_service
  local resp
  local host
  local port

  encoded_service=$(urlencode "$service_name")
  resp=$(curl -sS --max-time 8 \
    "${NACOS_ADDR}/v1/ns/instance/list?serviceName=${encoded_service}&groupName=${NACOS_GROUP}&healthyOnly=true&accessToken=${token}" \
    || true)

  host=$(echo "$resp" | jq -r '.hosts[0].ip // empty' 2>/dev/null || true)
  port=$(echo "$resp" | jq -r '.hosts[0].port // empty' 2>/dev/null || true)

  if [[ -z "$host" || -z "$port" || "$host" == "null" || "$port" == "null" ]]; then
    warn "${RED}No healthy instance for service=${service_name} in Nacos. resp=${resp}${NC}"
    return 1
  fi

  echo "http://${host}:${port}"
}

safe_jq() {
  local input="$1"
  local expr="$2"
  echo "$input" | jq -r "$expr" 2>/dev/null || true
}

request_json() {
  local method="$1"
  local url="$2"
  local token="${3:-}"
  local payload="${4:-}"
  local response
  if [[ -n "$payload" ]]; then
    if [[ -n "$token" ]]; then
      response=$(curl -sS --max-time 8 -X "$method" "$url" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $token" \
        -d "$payload")
    else
      response=$(curl -sS --max-time 8 -X "$method" "$url" \
        -H "Content-Type: application/json" \
        -d "$payload")
    fi
  else
    if [[ -n "$token" ]]; then
      response=$(curl -sS --max-time 8 -X "$method" "$url" \
        -H "Authorization: Bearer $token")
    else
      response=$(curl -sS --max-time 8 -X "$method" "$url")
    fi
  fi
  echo "$response"
}

find_order_by_client_id() {
  local token="$1"
  local user_id="$2"
  local client_order_id="$3"
  local history_json
  local active_json
  local oid

  history_json=$(curl -sS --max-time 8 -X GET "${OMS_BASE}/api/v1/oms/order/list?limit=100&status=FILLED,CANCELED,REJECTED&symbol=${SYMBOL}" \
    -H "X-User-Id: ${user_id}")
  active_json=$(curl -sS --max-time 8 -X GET "${OMS_BASE}/api/v1/oms/order/list?limit=100&status=NEW,PENDING_RISK,FROZEN,PARTIALLY_FILLED&symbol=${SYMBOL}" \
    -H "X-User-Id: ${user_id}")

  oid=$(echo "$active_json" | jq -r --arg cid "$client_order_id" '
    (.orders // []) | map(select((.clientOrderId // "") == $cid)) | .[0].orderId // empty
  ' 2>/dev/null || true)
  if [[ -z "$oid" ]]; then
    oid=$(echo "$history_json" | jq -r --arg cid "$client_order_id" '
      (.orders // []) | map(select((.clientOrderId // "") == $cid)) | .[0].orderId // empty
    ' 2>/dev/null || true)
  fi

  echo "$oid"
}

submit_order() {
  local side="$1"
  local client_order_id="$2"
  local idem_key="$3"
  local payload="$4"
  local user_id="$5"
  local token="$6"
  local order_json
  local create_code
  local order_id

  order_json=$(curl -sS --max-time 8 -X POST "${API_GATEWAY}/api/order/create" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${token}" \
    -H "X-Idempotency-Key: ${idem_key}" \
    -d "${payload}")
  create_code=$(safe_jq "$order_json" '.code')

  order_id=$(safe_jq "$order_json" '.orderId // .data.orderId')
  if [[ -n "$order_id" && "$order_id" != "null" ]]; then
    echo "$order_id"
    return 0
  fi

  if [[ "$create_code" == "401" ]]; then
    sleep 1
    order_id=$(find_order_by_client_id "$token" "$user_id" "$client_order_id")
    if [[ -n "$order_id" ]]; then
      warn "${YELLOW}Gateway returned 401 but order exists in OMS, side=${side}, orderId=${order_id}${NC}"
      echo "$order_id"
      return 0
    fi
  fi

  order_json=$(curl -sS --max-time 8 -X POST "${OMS_BASE}/api/v1/oms/order/submit" \
    -H "Content-Type: application/json" \
    -H "X-User-Id: ${user_id}" \
    -H "X-Trace-Id: test-$(date +%s%N)" \
    -H "X-Request-Id: req-$(date +%s%N)" \
    -d "${payload}")

  order_id=$(safe_jq "$order_json" '.orderId // .data.orderId')
  if [[ -z "$order_id" || "$order_id" == "null" ]]; then
    order_id=$(find_order_by_client_id "$token" "$user_id" "$client_order_id")
  fi

  if [[ -z "$order_id" ]]; then
    log "${RED}Submit failed for side=${side}: ${order_json}${NC}"
    return 1
  fi

  echo "$order_id"
}

log "${BLUE}====================================================${NC}"
log "${BLUE}  Sell Fill + Orderbook + OMS Status E2E Test${NC}"
log "${BLUE}====================================================${NC}"

require_cmd curl
require_cmd jq

NACOS_TOKEN="$(get_nacos_token)"
if [[ -z "$API_GATEWAY" ]]; then
  API_GATEWAY="$(resolve_service_base_from_nacos "api-gateway" "$NACOS_TOKEN")"
fi
if [[ -z "$OMS_BASE" ]]; then
  OMS_BASE="$(resolve_service_base_from_nacos "oms-core" "$NACOS_TOKEN")"
fi
if [[ -z "$MATCH_BASE" ]]; then
  MATCH_BASE="$(resolve_service_base_from_nacos "match-engine-core" "$NACOS_TOKEN")"
fi
if [[ -z "$MATCH_STATS_URL" ]]; then
  MATCH_STATS_URL="${MATCH_BASE}/api/v1/match/orderbook/stats"
fi

log "Resolved endpoints: API_GATEWAY=${API_GATEWAY}, OMS_BASE=${OMS_BASE}, MATCH_BASE=${MATCH_BASE}"
PRICE_DEC="$(to_dec8 "$PRICE_INT")"
QTY_DEC="$(to_dec8 "$QTY_INT")"

log "${YELLOW}[1/8] Login as ${USERNAME}${NC}"
LOGIN_JSON=$(request_json "POST" "${API_GATEWAY}/api/v1/user/login" "" "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}")
LOGIN_CODE=$(safe_jq "$LOGIN_JSON" '.code')
TOKEN=$(safe_jq "$LOGIN_JSON" '.data.token')
USER_ID=$(safe_jq "$LOGIN_JSON" '.data.userId')

if [[ "$LOGIN_CODE" != "200" || -z "$TOKEN" || "$TOKEN" == "null" ]]; then
  log "${RED}Login failed: ${LOGIN_JSON}${NC}"
  exit 1
fi
log "${GREEN}Login ok, userId=${USER_ID}, tokenLen=${#TOKEN}${NC}"

log "${YELLOW}[2/8] Snapshot match-engine stats before order${NC}"
STATS_BEFORE=$(request_json "GET" "${MATCH_STATS_URL}" "" "")
BEST_BID_BEFORE=$(safe_jq "$STATS_BEFORE" '.bestBid')
BEST_ASK_BEFORE=$(safe_jq "$STATS_BEFORE" '.bestAsk')
COUNT_BEFORE=$(safe_jq "$STATS_BEFORE" '.orderCount')
log "Stats before: ${STATS_BEFORE}"

CLIENT_ORDER_ID="sell_50000_1btc_$(date +%s)"
IDEMPOTENCY_KEY="idem_${CLIENT_ORDER_ID}_sell"
BUY_CLIENT_ORDER_ID="buy_50000_1btc_$(date +%s)"
BUY_IDEMPOTENCY_KEY="idem_${BUY_CLIENT_ORDER_ID}_buy"
if [[ "$TARGET_SIDE" == "BUY" ]]; then
  CLIENT_ORDER_ID="buy_50000_1btc_$(date +%s)"
  IDEMPOTENCY_KEY="idem_${CLIENT_ORDER_ID}_buy"
  BUY_CLIENT_ORDER_ID="sell_50000_1btc_$(date +%s)"
  BUY_IDEMPOTENCY_KEY="idem_${BUY_CLIENT_ORDER_ID}_sell"
fi

SEED_SIDE="BUY"
if [[ "$TARGET_SIDE" == "BUY" ]]; then
  SEED_SIDE="SELL"
fi

BUY_ORDER_PAYLOAD=$(cat <<JSON
{
  "symbol":"${SYMBOL}",
  "side":"${SEED_SIDE}",
  "type":"LIMIT",
  "price":"${PRICE_INT}",
  "quantity":"${QTY_INT}",
  "leverage":${LEVERAGE},
  "timeInForce":"GTC",
  "clientOrderId":"${BUY_CLIENT_ORDER_ID}"
}
JSON
)

log "${YELLOW}[3/8] Submit orders${NC}"
ORDER_PAYLOAD=$(cat <<JSON
{
  "symbol":"${SYMBOL}",
  "side":"${TARGET_SIDE}",
  "type":"LIMIT",
  "price":"${PRICE_INT}",
  "quantity":"${QTY_INT}",
  "leverage":${LEVERAGE},
  "timeInForce":"GTC",
  "clientOrderId":"${CLIENT_ORDER_ID}"
}
JSON
)

if [[ "$SEED_BUY_FIRST" == "true" ]]; then
  log "Submit ${SEED_SIDE} seed order first at same price to improve crossing fill"
  BUY_ORDER_ID=$(submit_order "$SEED_SIDE" "$BUY_CLIENT_ORDER_ID" "$BUY_IDEMPOTENCY_KEY" "$BUY_ORDER_PAYLOAD" "$USER_ID" "$TOKEN")
  log "${GREEN}${SEED_SIDE} seed order submitted, orderId=${BUY_ORDER_ID}${NC}"
  sleep 1
fi

log "Submit target ${TARGET_SIDE} order: price=${PRICE_DEC} qty=${QTY_DEC}"
ORDER_ID=$(submit_order "$TARGET_SIDE" "$CLIENT_ORDER_ID" "$IDEMPOTENCY_KEY" "$ORDER_PAYLOAD" "$USER_ID" "$TOKEN")
log "${GREEN}Order submitted, orderId=${ORDER_ID}${NC}"

log "${YELLOW}[4/8] Poll order status until FILLED${NC}"
FOUND_STATUS=""
FOUND_FILLED_QTY=""
for ((i=1; i<=POLL_TIMES; i++)); do
  HISTORY_JSON=$(curl -sS --max-time 8 -X GET "${OMS_BASE}/api/v1/oms/order/list?limit=50&status=FILLED,CANCELED,REJECTED&symbol=${SYMBOL}" \
    -H "X-User-Id: ${USER_ID}")
  ACTIVE_JSON=$(curl -sS --max-time 8 -X GET "${OMS_BASE}/api/v1/oms/order/list?limit=50&status=NEW,PENDING_RISK,FROZEN,PARTIALLY_FILLED&symbol=${SYMBOL}" \
    -H "X-User-Id: ${USER_ID}")

  FOUND_STATUS=$(echo "$HISTORY_JSON" | jq -r --arg oid "$ORDER_ID" '
    (.orders // []) | map(select((.orderId|tostring) == $oid)) | .[0].status // empty
  ' 2>/dev/null || true)
  if [[ -z "$FOUND_STATUS" ]]; then
    FOUND_STATUS=$(echo "$ACTIVE_JSON" | jq -r --arg oid "$ORDER_ID" '
      (.orders // []) | map(select((.orderId|tostring) == $oid)) | .[0].status // empty
    ' 2>/dev/null || true)
  fi

  FOUND_FILLED_QTY=$(echo "$HISTORY_JSON" | jq -r --arg oid "$ORDER_ID" '
    (.orders // []) | map(select((.orderId|tostring) == $oid)) | .[0].filledQuantity // empty
  ' 2>/dev/null || true)
  if [[ -z "$FOUND_FILLED_QTY" ]]; then
    FOUND_FILLED_QTY=$(echo "$ACTIVE_JSON" | jq -r --arg oid "$ORDER_ID" '
      (.orders // []) | map(select((.orderId|tostring) == $oid)) | .[0].filledQuantity // empty
    ' 2>/dev/null || true)
  fi

  if [[ "$FOUND_STATUS" == "FILLED" ]]; then
    log "${GREEN}FILLED at poll ${i}/${POLL_TIMES}, filledQty=${FOUND_FILLED_QTY}${NC}"
    break
  fi
  log "poll ${i}/${POLL_TIMES}: status=${FOUND_STATUS:-UNKNOWN}, filledQty=${FOUND_FILLED_QTY:-N/A}"
  sleep "$POLL_INTERVAL"
done

if [[ "$FOUND_STATUS" != "FILLED" ]]; then
  log "${RED}Order not FILLED within timeout. Final status=${FOUND_STATUS:-UNKNOWN}${NC}"
  QUERY_JSON=$(curl -sS --max-time 8 -X GET "${OMS_BASE}/api/v1/oms/order/query?orderId=${ORDER_ID}" \
    -H "X-User-Id: ${USER_ID}" || true)
  REASON_CODE=$(echo "$QUERY_JSON" | jq -r '.reasonCode // .data.reasonCode // .errorCode // empty' 2>/dev/null || true)
  REASON_MSG=$(echo "$QUERY_JSON" | jq -r '.reasonMsg // .data.reasonMsg // .errorMessage // .message // empty' 2>/dev/null || true)
  if [[ -z "$REASON_CODE" || "$REASON_CODE" == "null" ]]; then
    REASON_CODE=$(echo "$HISTORY_JSON" | jq -r --arg oid "$ORDER_ID" '
      (.orders // []) | map(select((.orderId|tostring) == $oid)) | .[0].reasonCode // empty
    ' 2>/dev/null || true)
  fi
  if [[ -z "$REASON_MSG" || "$REASON_MSG" == "null" ]]; then
    REASON_MSG=$(echo "$HISTORY_JSON" | jq -r --arg oid "$ORDER_ID" '
      (.orders // []) | map(select((.orderId|tostring) == $oid)) | .[0].reasonMsg // empty
    ' 2>/dev/null || true)
  fi
  log "Reject diagnose: orderId=${ORDER_ID}, reasonCode=${REASON_CODE:-N/A}, reasonMsg=${REASON_MSG:-N/A}"
  log "Query by orderId: ${QUERY_JSON}"
  log "History list: ${HISTORY_JSON}"
  log "Active list: ${ACTIVE_JSON}"
  if [[ -f "logs/oms-core.log" ]]; then
    log "OMS log grep(orderId=${ORDER_ID}):"
    grep -E "orderId=${ORDER_ID}|${ORDER_ID}" logs/oms-core.log | tail -n 20 || true
  elif [[ -f "oms-core/logs/oms-core.log" ]]; then
    log "OMS log grep(orderId=${ORDER_ID}):"
    grep -E "orderId=${ORDER_ID}|${ORDER_ID}" oms-core/logs/oms-core.log | tail -n 20 || true
  fi
  if [[ -f "logs/match-engine-core.log" ]]; then
    log "Match log grep(orderId=${ORDER_ID}):"
    grep -E "orderId=${ORDER_ID}|${ORDER_ID}" logs/match-engine-core.log | tail -n 20 || true
  elif [[ -f "match-engine-core/logs/match-engine.log" ]]; then
    log "Match log grep(orderId=${ORDER_ID}):"
    grep -E "orderId=${ORDER_ID}|${ORDER_ID}" match-engine-core/logs/match-engine.log | tail -n 20 || true
  fi
  exit 1
fi

log "${YELLOW}[5/8] Query order by orderId${NC}"
QUERY_JSON=$(curl -sS --max-time 8 -X GET "${OMS_BASE}/api/v1/oms/order/query?orderId=${ORDER_ID}" \
  -H "X-User-Id: ${USER_ID}")
QUERY_STATUS=$(safe_jq "$QUERY_JSON" '.status // .data.status')
log "Query response: ${QUERY_JSON}"
if [[ "$QUERY_STATUS" != "FILLED" ]]; then
  log "${RED}Query API status mismatch: expected FILLED, got ${QUERY_STATUS}${NC}"
  exit 1
fi
log "${GREEN}Query API status is FILLED${NC}"

log "${YELLOW}[6/8] Verify positions include dual-side records (if hedge mode enabled)${NC}"
POSITION_JSON=""
POSITION_COUNT="0"
LONG_COUNT="0"
SHORT_COUNT="0"

for ((i=1; i<=POSITION_POLL_TIMES; i++)); do
  # 持仓查询通过 JWT Token 认证，userId 由 Gateway 从 Token 解析
  POSITION_JSON=$(request_json "GET" "${API_GATEWAY}/api/v1/position/list" "$TOKEN" "")
  POSITION_COUNT=$(echo "$POSITION_JSON" | jq -r '
    ((.positions // .data.positions // .data // . // []) | map(select((.size // "0" | tonumber) > 0)) | length)
  ' 2>/dev/null || echo "0")

  LONG_COUNT=$(echo "$POSITION_JSON" | jq -r '
    [(.positions // .data.positions // .data // . // [])[]?
      | select((.size // "0" | tonumber) > 0)
      | select((.side // (.positionSide|tostring) // "")|ascii_upcase|test("LONG|^1$"))] | length
  ' 2>/dev/null || echo "0")
  SHORT_COUNT=$(echo "$POSITION_JSON" | jq -r '
    [(.positions // .data.positions // .data // . // [])[]?
      | select((.size // "0" | tonumber) > 0)
      | select((.side // (.positionSide|tostring) // "")|ascii_upcase|test("SHORT|^2$"))] | length
  ' 2>/dev/null || echo "0")

  if [[ "$POSITION_COUNT" =~ ^[0-9]+$ ]] && [[ "$POSITION_COUNT" -gt 0 ]]; then
    log "${GREEN}Position updated at poll ${i}/${POSITION_POLL_TIMES}, total=${POSITION_COUNT}, long=${LONG_COUNT}, short=${SHORT_COUNT}${NC}"
    break
  fi
  log "position poll ${i}/${POSITION_POLL_TIMES}: total=${POSITION_COUNT}, long=${LONG_COUNT}, short=${SHORT_COUNT}"
  sleep "$POSITION_POLL_INTERVAL"
done

log "Position response: ${POSITION_JSON}"
if ! [[ "$POSITION_COUNT" =~ ^[0-9]+$ ]] || [[ "$POSITION_COUNT" -eq 0 ]]; then
  log "${RED}Position not updated after fill. total=${POSITION_COUNT}${NC}"
  exit 1
fi

log "${YELLOW}[7/8] Snapshot match-engine stats after fill${NC}"
STATS_AFTER=$(request_json "GET" "${MATCH_STATS_URL}" "" "")
BEST_BID_AFTER=$(safe_jq "$STATS_AFTER" '.bestBid')
BEST_ASK_AFTER=$(safe_jq "$STATS_AFTER" '.bestAsk')
COUNT_AFTER=$(safe_jq "$STATS_AFTER" '.orderCount')
log "Stats after: ${STATS_AFTER}"

log "${YELLOW}[8/8] Validate trade output and OMS state update from logs${NC}"
MATCH_LOG=""
for candidate in "logs/match-engine-core.log" "match-engine-core/logs/match-engine.log" "logs/match-engine.log"; do
  if [[ -f "$candidate" ]]; then
    MATCH_LOG="$candidate"
    break
  fi
done

OMS_LOG=""
for candidate in "logs/oms-core.log" "oms-core/logs/oms-core.log" "logs/oms.log"; do
  if [[ -f "$candidate" ]]; then
    OMS_LOG="$candidate"
    break
  fi
done

MATCH_HIT=""
OMS_HIT=""
if [[ -n "$MATCH_LOG" ]]; then
  MATCH_HIT=$(grep -E "orderId=${ORDER_ID}|${ORDER_ID}" "$MATCH_LOG" | tail -n 5 || true)
fi
if [[ -n "$OMS_LOG" ]]; then
  OMS_HIT=$(grep -E "orderId=${ORDER_ID}|${ORDER_ID}" "$OMS_LOG" | tail -n 8 || true)
fi

if [[ -z "$MATCH_HIT" ]]; then
  log "${RED}No match-engine log hit for orderId=${ORDER_ID}${NC}"
  exit 1
fi
if [[ -z "$OMS_HIT" ]]; then
  log "${RED}No OMS log hit for orderId=${ORDER_ID}${NC}"
  exit 1
fi

log "${GREEN}Match-engine log hits:${NC}"
echo "$MATCH_HIT"
log "${GREEN}OMS log hits:${NC}"
echo "$OMS_HIT"

log "${BLUE}====================================================${NC}"
log "${GREEN}PASS${NC} orderId=${ORDER_ID} status=FILLED"
log "book(before): bid=${BEST_BID_BEFORE} ask=${BEST_ASK_BEFORE} count=${COUNT_BEFORE}"
log "book(after):  bid=${BEST_BID_AFTER} ask=${BEST_ASK_AFTER} count=${COUNT_AFTER}"
log "positions: long=${LONG_COUNT}, short=${SHORT_COUNT}"
log "${BLUE}====================================================${NC}"
