#!/usr/bin/env bash
set -euo pipefail

# C1 contract check:
# 1) exchange_oms.t_order has CFD execution/reference columns
# 2) exchange_oms.t_cfd_working_order exists with hot-path indexes
# 3) Kafka topic cfd-order-command-BTCUSDT exists

MYSQL_CONTAINER="${MYSQL_CONTAINER:-web3-mysql}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka-1}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root123456}"
BROKER="${BROKER:-localhost:9092}"

required_t_order_columns=(
  execution_mode
  liquidity_source
  reference_topic
  reference_offset
  reference_event_time
  reference_best_bid
  reference_best_ask
  reference_vwap_price
  slippage_bps
)

required_cfd_indexes=(
  idx_cfd_symbol_status_updated
  idx_cfd_user_symbol_status
  idx_cfd_status_trigger_time
)

echo "[C1] checking exchange_oms.t_order columns..."
for col in "${required_t_order_columns[@]}"; do
  count="$(docker exec "${MYSQL_CONTAINER}" mysql -uroot -p"${MYSQL_PASSWORD}" -N -e \
    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='exchange_oms' AND table_name='t_order' AND column_name='${col}';")"
  if [[ "${count}" != "1" ]]; then
    echo "[FAIL] missing t_order column: ${col}"
    exit 1
  fi
done
echo "[PASS] t_order columns ready"

echo "[C1] checking exchange_oms.t_cfd_working_order..."
table_exists="$(docker exec "${MYSQL_CONTAINER}" mysql -uroot -p"${MYSQL_PASSWORD}" -N -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='exchange_oms' AND table_name='t_cfd_working_order';")"
if [[ "${table_exists}" != "1" ]]; then
  echo "[FAIL] missing table: t_cfd_working_order"
  exit 1
fi

for idx in "${required_cfd_indexes[@]}"; do
  idx_count="$(docker exec "${MYSQL_CONTAINER}" mysql -uroot -p"${MYSQL_PASSWORD}" -N -e \
    "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='exchange_oms' AND table_name='t_cfd_working_order' AND index_name='${idx}';")"
  if [[ "${idx_count}" == "0" ]]; then
    echo "[FAIL] missing index on t_cfd_working_order: ${idx}"
    exit 1
  fi
done
echo "[PASS] t_cfd_working_order and indexes ready"

echo "[C1] checking kafka topic cfd-order-command-BTCUSDT..."
if docker exec "${KAFKA_CONTAINER}" kafka-topics --bootstrap-server "${BROKER}" --list | grep -q "^cfd-order-command-BTCUSDT$"; then
  echo "[PASS] kafka topic exists"
else
  echo "[FAIL] kafka topic missing: cfd-order-command-BTCUSDT"
  exit 1
fi

echo "[C1] PASS"
