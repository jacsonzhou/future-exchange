#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka-1}"
KAFKA_BROKER="${KAFKA_BROKER:-localhost:9092}"
AUTO_START="${AUTO_START:-true}"
WITH_LIQUIDATION="${WITH_LIQUIDATION:-false}"
STRICT_LIQUIDATION="${STRICT_LIQUIDATION:-false}"
RUN_KAFKA_RECOVER="${RUN_KAFKA_RECOVER:-true}"
SHOW_TAIL_LINES="${SHOW_TAIL_LINES:-120}"

timestamp() {
  date '+%Y-%m-%d %H:%M:%S'
}

log() {
  echo "[$(timestamp)] $*"
}

warn() {
  echo "[$(timestamp)] [WARN] $*" >&2
}

require_cmd() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || {
    echo "Missing required command: $cmd" >&2
    exit 2
  }
}

wait_kafka_ready() {
  local tries="${1:-30}"
  local sleep_sec="${2:-2}"
  local i

  for ((i=1; i<=tries; i++)); do
    if docker exec "${KAFKA_CONTAINER}" sh -lc \
      "kafka-topics --bootstrap-server '${KAFKA_BROKER}' --list >/dev/null 2>&1"; then
      return 0
    fi
    sleep "${sleep_sec}"
  done
  return 1
}

ensure_kafka_ready() {
  require_cmd docker

  if ! docker ps --format '{{.Names}}' | grep -qx "${KAFKA_CONTAINER}"; then
    if docker ps -a --format '{{.Names}}' | grep -qx "${KAFKA_CONTAINER}"; then
      log "Kafka container not running, starting ${KAFKA_CONTAINER}"
      docker start "${KAFKA_CONTAINER}" >/dev/null
    else
      warn "Kafka container ${KAFKA_CONTAINER} not found, skip Kafka auto-recover"
      return 1
    fi
  fi

  if wait_kafka_ready 45 2; then
    log "Kafka broker is reachable (${KAFKA_BROKER})"
    return 0
  fi

  warn "Kafka broker not ready after wait, tail logs for diagnostics"
  docker logs --tail 120 "${KAFKA_CONTAINER}" || true
  return 1
}

recover_kafka_topics() {
  if [[ "${RUN_KAFKA_RECOVER}" != "true" ]]; then
    log "RUN_KAFKA_RECOVER=false, skip topic recovery"
    return 0
  fi

  if ! ensure_kafka_ready; then
    warn "Kafka not ready, continue to suite (suite may fail fast)"
    return 0
  fi

  log "Recovering Kafka topics via init_kafka.sh"
  if bash init_kafka.sh >/tmp/e2e_acceptance_kafka_init.log 2>&1; then
    log "Kafka topic recovery done"
  else
    warn "init_kafka.sh failed, tail output:"
    tail -n "${SHOW_TAIL_LINES}" /tmp/e2e_acceptance_kafka_init.log || true
    return 1
  fi
}

extract_report_path() {
  local log_file="$1"
  local pattern='test-reports/acceptance-suite-[0-9-]+\.md'
  local report

  report="$(grep -Eo "${pattern}" "${log_file}" | tail -n 1 || true)"
  if [[ -z "${report}" ]]; then
    report="$(ls -1t test-reports/acceptance-suite-*.md 2>/dev/null | head -n 1 || true)"
  fi
  echo "${report}"
}

main() {
  require_cmd python3
  require_cmd tee
  require_cmd grep
  require_cmd tail
  require_cmd ls

  log "One-click acceptance started"
  recover_kafka_topics

  local suite_log
  suite_log="$(mktemp /tmp/e2e_acceptance.XXXXXX)"
  local -a cmd=(python3 scripts/e2e_acceptance_suite.py)
  if [[ "${AUTO_START}" == "true" ]]; then
    cmd+=(--auto-start)
  fi
  if [[ "${WITH_LIQUIDATION}" == "true" ]]; then
    cmd+=(--with-liquidation)
  fi
  if [[ "${STRICT_LIQUIDATION}" == "true" ]]; then
    cmd+=(--strict-liquidation)
  fi
  cmd+=("$@")

  log "Running suite: ${cmd[*]}"
  set +e
  "${cmd[@]}" 2>&1 | tee "${suite_log}"
  local suite_rc=${PIPESTATUS[0]}
  set -e

  local report_md report_json
  report_md="$(extract_report_path "${suite_log}")"
  report_json="${report_md%.md}.json"

  if [[ -n "${report_md}" ]]; then
    log "REPORT_MD=${report_md}"
  else
    warn "No markdown report path found"
  fi
  if [[ -n "${report_json}" && -f "${report_json}" ]]; then
    log "REPORT_JSON=${report_json}"
  fi

  if [[ ${suite_rc} -eq 0 ]]; then
    log "ACCEPTANCE_RESULT=PASS"
  else
    log "ACCEPTANCE_RESULT=FAIL"
  fi

  return "${suite_rc}"
}

main "$@"
