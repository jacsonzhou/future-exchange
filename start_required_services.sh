#!/bin/zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

SERVICES=(
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
  "replay-core"
  "index-price-core"
  "mark-price-core"
  "funding-rate-core"
  "liquidation-core"
  "margin-mode-core"
  "market-maker-core"
  "binance-data-source"
  "cfd-dealer-core"
)

ACTION="start"
if [[ $# -gt 0 ]]; then
  case "$1" in
    start|restart|stop|status)
      ACTION="$1"
      shift
      ;;
  esac
fi

if [[ $# -gt 0 ]]; then
  TARGETS=("$@")
else
  TARGETS=("${SERVICES[@]}")
fi

exec "${ROOT_DIR}/scripts/servicectl.sh" "$ACTION" "${TARGETS[@]}"
