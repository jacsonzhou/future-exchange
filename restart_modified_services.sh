#!/bin/zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVICECTL="${ROOT_DIR}/scripts/servicectl.sh"

if [[ ! -x "$SERVICECTL" ]]; then
  echo "[ERROR] servicectl not found or not executable: $SERVICECTL" >&2
  exit 1
fi

if [[ $# -gt 0 ]]; then
  # 兼容：允许外部传入模块名
  exec "$SERVICECTL" restart "$@"
fi

# 默认重启常改动链路
exec "$SERVICECTL" restart ledger-core oms-core api-gateway
