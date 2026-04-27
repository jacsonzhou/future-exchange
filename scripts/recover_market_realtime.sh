#!/bin/zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka-1}"
REDIS_CONTAINER="${REDIS_CONTAINER:-redis-dev}"
SYMBOL="${1:-BTCUSDT}"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
RESET_RETRIES="${RESET_RETRIES:-5}"
READY_RETRIES="${READY_RETRIES:-10}"
READY_INTERVAL_SEC="${READY_INTERVAL_SEC:-2}"
GROUP_INACTIVE_WAIT_SEC="${GROUP_INACTIVE_WAIT_SEC:-70}"

wait_kafka_ready() {
  local attempt=1
  while (( attempt <= READY_RETRIES )); do
    if docker exec "$KAFKA_CONTAINER" bash -lc "kafka-broker-api-versions --bootstrap-server ${KAFKA_BOOTSTRAP} >/dev/null 2>&1"; then
      echo "[INFO] Kafka broker is reachable (${KAFKA_BOOTSTRAP})"
      return 0
    fi
    echo "[WARN] Kafka not ready yet (attempt ${attempt}/${READY_RETRIES}), sleep ${READY_INTERVAL_SEC}s..."
    sleep "$READY_INTERVAL_SEC"
    ((attempt++))
  done
  echo "[ERROR] Kafka broker is not reachable after ${READY_RETRIES} attempts"
  return 1
}

reset_offsets_to_latest() {
  local group="$1"
  local attempt=1
  local out=""
  local rc=0

  while (( attempt <= RESET_RETRIES )); do
    echo "[INFO] Reset offsets to latest: ${group} (attempt ${attempt}/${RESET_RETRIES})"
    set +e
    out="$(docker exec "$KAFKA_CONTAINER" kafka-consumer-groups \
      --bootstrap-server "${KAFKA_BOOTSTRAP}" \
      --group "${group}" \
      --all-topics \
      --reset-offsets \
      --to-latest \
      --execute 2>&1)"
    rc=$?
    set -e

    echo "$out"
    if [[ "$rc" -eq 0 \
      && "$out" != *"TimeoutException"* \
      && "$out" != *"Executing consumer group command failed"* \
      && "$out" != *"Assignments can only be reset if the group"* \
      && "$out" != *"current state is Stable"* ]]; then
      echo "[INFO] Reset offsets succeeded for group=${group}"
      return 0
    fi

    if [[ "$out" == *"Assignments can only be reset if the group"* || "$out" == *"current state is Stable"* ]]; then
      echo "[WARN] Group ${group} is active, stopping market consumers before retry..."
      ./scripts/servicectl.sh stop binance-data-source market-price-core >/dev/null 2>&1 || true
      wait_group_inactive "${group}" || true
    fi

    echo "[WARN] Reset offsets failed for group=${group}, rc=${rc}"
    sleep "$READY_INTERVAL_SEC"
    ((attempt++))
  done

  echo "[ERROR] Reset offsets still failed for group=${group} after ${RESET_RETRIES} attempts"
  return 1
}

describe_group_lag() {
  local group="$1"
  echo "[INFO] Consumer lag snapshot for group=${group}"
  docker exec "$KAFKA_CONTAINER" kafka-consumer-groups \
    --bootstrap-server "${KAFKA_BOOTSTRAP}" \
    --group "${group}" \
    --describe || true
}

wait_group_inactive() {
  local group="$1"
  local deadline=$(( $(date +%s) + GROUP_INACTIVE_WAIT_SEC ))

  while (( $(date +%s) <= deadline )); do
    local out
    out="$(docker exec "$KAFKA_CONTAINER" kafka-consumer-groups \
      --bootstrap-server "${KAFKA_BOOTSTRAP}" \
      --group "${group}" \
      --describe 2>&1 || true)"

    if [[ "$out" == *"has no active members"* || "$out" != *"consumer-"* ]]; then
      echo "[INFO] Group ${group} is inactive"
      return 0
    fi

    echo "[WARN] Group ${group} still active, waiting 3s..."
    sleep 3
  done

  echo "[WARN] Group ${group} did not become inactive within ${GROUP_INACTIVE_WAIT_SEC}s"
  return 1
}

wait_kafka_ready
echo "[INFO] Stop market consumers before offset reset"
./scripts/servicectl.sh stop binance-data-source market-price-core >/dev/null 2>&1 || true
wait_group_inactive "market-price-core-external-market" || true
wait_group_inactive "market-price-core" || true
reset_offsets_to_latest "market-price-core-external-market"
reset_offsets_to_latest "market-price-core"
describe_group_lag "market-price-core-external-market"
describe_group_lag "market-price-core"

echo "[INFO] Restart market data chain services"
./scripts/servicectl.sh restart binance-data-source market-price-core public-push-core
./scripts/servicectl.sh status binance-data-source market-price-core public-push-core

echo "[INFO] Validate Redis snapshots for ${SYMBOL}"
TRADE_TS="$(docker exec "$REDIS_CONTAINER" redis-cli --raw GET "market:snapshot:trade:${SYMBOL}" | jq -r 'fromjson | .T // .E // 0' 2>/dev/null || echo 0)"
DEPTH_TS="$(docker exec "$REDIS_CONTAINER" redis-cli --raw GET "market:snapshot:depth:${SYMBOL}" | jq -r 'fromjson | .E // .T // 0' 2>/dev/null || echo 0)"

if [[ "$TRADE_TS" == "" || "$TRADE_TS" == "null" ]]; then
  TRADE_TS=0
fi
if [[ "$DEPTH_TS" == "" || "$DEPTH_TS" == "null" ]]; then
  DEPTH_TS=0
fi

echo "[INFO] trade timestamp(ms): ${TRADE_TS}"
if [[ "$TRADE_TS" -gt 0 ]]; then
  date -r $((TRADE_TS / 1000)) '+[INFO] trade wall time: %Y-%m-%d %H:%M:%S %Z'
else
  echo "[WARN] trade snapshot not available yet"
fi

echo "[INFO] depth timestamp(ms): ${DEPTH_TS}"
if [[ "$DEPTH_TS" -gt 0 ]]; then
  date -r $((DEPTH_TS / 1000)) '+[INFO] depth wall time: %Y-%m-%d %H:%M:%S %Z'
else
  echo "[WARN] depth snapshot not available yet"
fi

echo "[INFO] Done. If timestamps are old, rerun script after 3-5 seconds."
