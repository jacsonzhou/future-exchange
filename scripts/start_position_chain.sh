#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICECTL="${ROOT_DIR}/scripts/servicectl.sh"

SERVICES=(
  "index-price-core"
  "mark-price-core"
  "position-snapshot-core"
  "private-push-core"
)

if [[ ! -x "$SERVICECTL" ]]; then
  echo "[ERROR] servicectl not found or not executable: $SERVICECTL" >&2
  exit 1
fi

ACTION="${1:-restart}"
case "$ACTION" in
  start|restart|stop|status)
    ;;
  *)
    echo "[ERROR] unsupported action: $ACTION (allowed: start|restart|stop|status)" >&2
    exit 1
    ;;
esac

# 默认低内存参数，可通过外部环境变量覆盖
export JAVA_LOW_MEM_OPTS="${JAVA_LOW_MEM_OPTS:--Xms128m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8}"

echo "========================================"
echo "Position chain via servicectl (${ACTION})"
echo "========================================"
echo "root: ${ROOT_DIR}"
echo "services: ${SERVICES[*]}"
echo "JAVA_LOW_MEM_OPTS=${JAVA_LOW_MEM_OPTS}"
echo

exec "$SERVICECTL" "$ACTION" "${SERVICES[@]}"
