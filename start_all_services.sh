#!/bin/zsh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
ACTION="${1:-start}"
case "$ACTION" in
  start|restart|stop|status)
    ;;
  *)
    ACTION="start"
    ;;
esac

exec "${ROOT_DIR}/scripts/servicectl.sh" "$ACTION" all
