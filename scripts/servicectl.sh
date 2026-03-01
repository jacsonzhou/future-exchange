#!/bin/zsh

set -euo pipefail
unsetopt BG_NICE 2>/dev/null || true

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/logs/servicectl"
PID_DIR="${LOG_DIR}/pids"

NACOS_ADDR="${NACOS_ADDR:-http://localhost:8848/nacos}"
NACOS_USERNAME="${NACOS_USERNAME:-nacos}"
NACOS_PASSWORD="${NACOS_PASSWORD:-nacos}"
NACOS_GROUP="${NACOS_GROUP:-DEFAULT_GROUP}"
NACOS_NAMESPACE="${NACOS_NAMESPACE:-}"
NACOS_TIMEOUT_SEC="${NACOS_TIMEOUT_SEC:-5}"

JAVA_CMD="${JAVA_CMD:-java}"
JAVA_LOW_MEM_OPTS="${JAVA_LOW_MEM_OPTS:--Xms128m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8}"
START_TIMEOUT_SEC="${START_TIMEOUT_SEC:-90}"
STOP_TIMEOUT_SEC="${STOP_TIMEOUT_SEC:-20}"

NACOS_LOGIN_DONE=0
NACOS_TOKEN=""
FETCH_CONTENT=""

typeset -A META_PORT=()
typeset -A META_APP=()
typeset -A META_DATAID=()
typeset -A META_SOURCE=()
typeset -A META_READY=()

typeset -A ALIAS_TO_MODULE=()
typeset -A MODULE_DATAID_CANDIDATES=(
  ["api-gateway"]="api-gateway-dev.yml"
  ["oms-core"]="oms-core-dev.yml"
  ["match-engine-core"]="match-engine-core-dev.yml"
  ["ledger-core"]="ledger-core-dev.yml"
  ["snapshot-account-core"]="snapshot-account-core-dev.yml"
  ["position-snapshot-core"]="position-snapshot-core-dev.yml"
  ["replay-core"]="replay-core-dev.yml"
  ["market-price-core"]="market-price-core-dev.yml,market-price-service-dev.yml"
  ["public-push-core"]="public-push-service-dev.yml,public-push-core-dev.yml"
  ["private-push-core"]="private-push-core-dev.yml"
  ["index-price-core"]="index-price-service-dev.yml,index-price-core-dev.yml"
  ["mark-price-core"]="mark-price-service-dev.yml,mark-price-core-dev.yml"
  ["user-core"]="user-core-dev.yml"
  ["funding-rate-core"]="funding-rate-core-dev.yml"
  ["liquidation-core"]="liquidation-core-dev.yml"
  ["adl-core"]="adl-core-dev.yml"
  ["tp-sl-core"]="tp-sl-core-dev.yml"
  ["hard-risk-core"]="hard-risk-core-dev.yml"
  ["margin-mode-core"]="margin-mode-core-dev.yml"
  ["market-maker-core"]="market-maker-core-dev.yml"
  ["binance-data-source"]="binance-data-source-dev.yml"
  ["cfd-dealer-core"]="cfd-dealer-core-dev.yml"
)

START_ORDER=(
  "match-engine-core"
  "ledger-core"
  "oms-core"
  "hard-risk-core"
  "snapshot-account-core"
  "position-snapshot-core"
  "market-price-core"
  "public-push-core"
  "private-push-core"
  "api-gateway"
  "user-core"
  "index-price-core"
  "mark-price-core"
  "funding-rate-core"
  "liquidation-core"
  "adl-core"
  "tp-sl-core"
  "replay-core"
  "margin-mode-core"
  "market-maker-core"
  "binance-data-source"
  "cfd-dealer-core"
)

usage() {
  cat <<'EOF'
Usage:
  scripts/servicectl.sh <action> [all|service1 service2|service1,service2]

Actions:
  start      启动指定服务
  stop       停止指定服务
  restart    重启指定服务
  status     查看指定服务状态
  list       列出支持的服务、端口来源、配置DataId
  help       显示帮助

Examples:
  scripts/servicectl.sh restart all
  scripts/servicectl.sh restart oms-core api-gateway
  scripts/servicectl.sh restart public-push-service,private-push-core
  scripts/servicectl.sh status all

Environment:
  NACOS_ADDR=http://localhost:8848/nacos
  NACOS_USERNAME=nacos
  NACOS_PASSWORD=nacos
  NACOS_GROUP=DEFAULT_GROUP
  NACOS_NAMESPACE=
  JAVA_CMD=java
  JAVA_LOW_MEM_OPTS="-Xms128m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"
  MYSQL_PASSWORD=root123456
  DB_PASSWORD=root123456
  DB_USERNAME=root
  MYSQL_USERNAME=root
  SPRING_PROFILES_ACTIVE=dev
  START_TIMEOUT_SEC=90
  STOP_TIMEOUT_SEC=20
EOF
}

info() { echo "[INFO] $*"; }
warn() { echo "[WARN] $*" >&2; }
err()  { echo "[ERROR] $*" >&2; }

trim() {
  local s="${1:-}"
  s="${s#"${s%%[![:space:]]*}"}"
  s="${s%"${s##*[![:space:]]}"}"
  printf '%s' "$s"
}

leading_spaces() {
  local s="${1:-}"
  local prefix="${s%%[^ ]*}"
  echo "${#prefix}"
}

require_cmd() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || {
    err "Missing required command: $cmd"
    exit 1
  }
}

init_aliases() {
  local module
  for module in "${START_ORDER[@]}"; do
    ALIAS_TO_MODULE[$module]="$module"
  done

  # spring.application.name 别名
  ALIAS_TO_MODULE[public-push-service]="public-push-core"
  ALIAS_TO_MODULE[private-push-service]="private-push-core"
  ALIAS_TO_MODULE[index-price-service]="index-price-core"
  ALIAS_TO_MODULE[mark-price-service]="mark-price-core"
  ALIAS_TO_MODULE[market-price-service]="market-price-core"

  # 历史别名
  ALIAS_TO_MODULE[snapshot-core]="snapshot-account-core"
}

ensure_java() {
  if ! command -v "$JAVA_CMD" >/dev/null 2>&1; then
    err "java not found, set JAVA_CMD first."
    exit 1
  fi
  JAVA_CMD="$(command -v "$JAVA_CMD")"
}

ensure_nacos_token() {
  if (( NACOS_LOGIN_DONE == 1 )); then
    [[ -n "$NACOS_TOKEN" ]]
    return
  fi

  NACOS_LOGIN_DONE=1
  local resp
  resp="$(curl -sS --max-time "$NACOS_TIMEOUT_SEC" -X POST \
    "${NACOS_ADDR}/v1/auth/login" \
    -d "username=${NACOS_USERNAME}&password=${NACOS_PASSWORD}" || true)"

  NACOS_TOKEN="$(echo "$resp" | sed -n 's/.*"accessToken"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
  if [[ -z "$NACOS_TOKEN" ]]; then
    warn "Nacos login failed or Nacos unavailable, fallback to local nacos-configs."
    return 1
  fi
  info "Nacos login success."
  return 0
}

fetch_config_from_nacos() {
  local data_id="$1"
  FETCH_CONTENT=""
  [[ -z "$data_id" ]] && return 1
  ensure_nacos_token || return 1

  local query="dataId=${data_id}&group=${NACOS_GROUP}&accessToken=${NACOS_TOKEN}"
  if [[ -n "$NACOS_NAMESPACE" ]]; then
    query="${query}&tenant=${NACOS_NAMESPACE}"
  fi

  local body
  body="$(curl -sS --max-time "$NACOS_TIMEOUT_SEC" \
    "${NACOS_ADDR}/v1/cs/configs?${query}" || true)"

  [[ -z "$body" ]] && return 1
  [[ "$body" == *"config data not exist"* ]] && return 1
  if [[ "$body" != *"server:"* && "$body" != *"spring:"* ]]; then
    return 1
  fi
  FETCH_CONTENT="$body"
  return 0
}

fetch_config_from_local() {
  local data_id="$1"
  local cfg_path="${ROOT_DIR}/nacos-configs/${data_id}"
  FETCH_CONTENT=""
  [[ -f "$cfg_path" ]] || return 1
  FETCH_CONTENT="$(<"$cfg_path")"
  return 0
}

normalize_port_value() {
  local raw
  raw="$(trim "${1:-}")"
  raw="${raw%\"}"
  raw="${raw#\"}"
  raw="${raw%\'}"
  raw="${raw#\'}"

  if [[ "$raw" == <-> ]]; then
    printf '%s' "$raw"
    return 0
  fi

  if [[ "$raw" == '${'*'}' ]]; then
    local inner="${raw#\$\{}"
    inner="${inner%\}}"
    if [[ "$inner" == *:* ]]; then
      local fallback="${inner#*:}"
      fallback="$(trim "$fallback")"
      if [[ "$fallback" == <-> ]]; then
        printf '%s' "$fallback"
        return 0
      fi
    fi
  fi
  return 1
}

normalize_app_name() {
  local raw
  raw="$(trim "${1:-}")"
  raw="${raw%\"}"
  raw="${raw#\"}"
  raw="${raw%\'}"
  raw="${raw#\'}"

  if [[ "$raw" == '${'*'}' ]]; then
    local inner="${raw#\$\{}"
    inner="${inner%\}}"
    if [[ "$inner" == *:* ]]; then
      raw="${inner#*:}"
    else
      raw=""
    fi
  fi
  printf '%s' "$raw"
}

extract_server_port_raw() {
  local content="$1"
  local in_server=0
  local server_indent=-1
  local line line_clean indent trimmed

  while IFS= read -r line; do
    line_clean="${line%%#*}"
    trimmed="$(trim "$line_clean")"
    [[ -z "$trimmed" ]] && continue
    indent="$(leading_spaces "$line_clean")"

    if [[ "$trimmed" == "server:" ]]; then
      in_server=1
      server_indent="$indent"
      continue
    fi

    if (( in_server == 1 )); then
      if (( indent <= server_indent )); then
        in_server=0
      elif [[ "$trimmed" == port:* ]]; then
        printf '%s' "$(trim "${trimmed#port:}")"
        return 0
      fi
    fi
  done <<< "$content"

  return 1
}

extract_app_name_raw() {
  local content="$1"
  local in_spring=0 in_app=0
  local spring_indent=-1 app_indent=-1
  local line line_clean indent trimmed

  while IFS= read -r line; do
    line_clean="${line%%#*}"
    trimmed="$(trim "$line_clean")"
    [[ -z "$trimmed" ]] && continue
    indent="$(leading_spaces "$line_clean")"

    if [[ "$trimmed" == "spring:" ]]; then
      in_spring=1
      spring_indent="$indent"
      in_app=0
      continue
    fi

    if (( in_spring == 1 )) && (( indent <= spring_indent )); then
      in_spring=0
      in_app=0
    fi

    if (( in_spring == 1 )) && [[ "$trimmed" == "application:" ]]; then
      in_app=1
      app_indent="$indent"
      continue
    fi

    if (( in_app == 1 )) && (( indent <= app_indent )); then
      in_app=0
    fi

    if (( in_app == 1 )) && [[ "$trimmed" == name:* ]]; then
      printf '%s' "$(trim "${trimmed#name:}")"
      return 0
    fi
  done <<< "$content"

  return 1
}

get_dataid_candidates() {
  local module="$1"
  local candidates=()

  local mapped="${MODULE_DATAID_CANDIDATES[$module]:-}"
  if [[ -n "$mapped" ]]; then
    local -a parts
    local part
    parts=("${(@s:,:)mapped}")
    for part in "${parts[@]}"; do
      part="$(trim "$part")"
      [[ -n "$part" ]] && candidates+=("$part")
    done
  fi

  candidates+=("${module}-dev.yml")
  local alt="${module/-core/-service}-dev.yml"
  if [[ "$alt" != "${module}-dev.yml" ]]; then
    candidates+=("$alt")
  fi

  typeset -A seen=()
  local dedup=()
  local d
  for d in "${candidates[@]}"; do
    if [[ -z "${seen[$d]:-}" ]]; then
      dedup+=("$d")
      seen[$d]=1
    fi
  done
  printf '%s\n' "${dedup[@]}"
}

resolve_service_meta() {
  local module="$1"
  if [[ "${META_READY[$module]:-0}" == "1" ]]; then
    return 0
  fi

  local candidates=()
  local c
  while IFS= read -r c; do
    [[ -n "$c" ]] && candidates+=("$c")
  done < <(get_dataid_candidates "$module")

  local content="" dataid="" source="" port_raw="" app_raw="" port="" app=""

  for dataid in "${candidates[@]}"; do
    if fetch_config_from_nacos "$dataid"; then
      content="$FETCH_CONTENT"
      source="nacos"
      break
    fi
  done

  if [[ -z "$content" ]]; then
    for dataid in "${candidates[@]}"; do
      if fetch_config_from_local "$dataid"; then
        content="$FETCH_CONTENT"
        source="local"
        break
      fi
    done
  fi

  if [[ -z "$content" ]]; then
    err "No config found for module=${module} (Nacos and local fallback both failed)."
    return 1
  fi

  port_raw="$(extract_server_port_raw "$content" || true)"
  port="$(normalize_port_value "$port_raw" || true)"
  if [[ -z "$port" ]]; then
    err "Cannot parse server.port for module=${module} from dataId=${dataid} (${source}). raw=${port_raw:-<empty>}"
    return 1
  fi

  app_raw="$(extract_app_name_raw "$content" || true)"
  app="$(normalize_app_name "$app_raw")"
  if [[ -z "$app" ]]; then
    app="$module"
  fi

  META_PORT[$module]="$port"
  META_APP[$module]="$app"
  META_DATAID[$module]="$dataid"
  META_SOURCE[$module]="$source"
  META_READY[$module]="1"
  return 0
}

resolve_module_jar() {
  local module="$1"
  local module_dir="${ROOT_DIR}/${module}"
  local candidate jar=""

  while IFS= read -r candidate; do
    [[ -z "$candidate" ]] && continue
    case "$candidate" in
      *-sources.jar|*-javadoc.jar|*-tests.jar|*.original|*plain.jar)
        continue
        ;;
    esac
    jar="$candidate"
    break
  done < <(ls -1t "${module_dir}"/target/"${module}"-*.jar 2>/dev/null || true)

  if [[ -z "$jar" ]]; then
    return 1
  fi
  printf '%s' "$jar"
  return 0
}

module_from_target() {
  local raw="$1"
  local key
  key="$(echo "$raw" | tr '[:upper:]' '[:lower:]')"

  if [[ -n "${ALIAS_TO_MODULE[$key]:-}" ]]; then
    printf '%s' "${ALIAS_TO_MODULE[$key]}"
    return 0
  fi

  if [[ -d "${ROOT_DIR}/${raw}" ]]; then
    printf '%s' "$raw"
    return 0
  fi

  if [[ -d "${ROOT_DIR}/${key}" ]]; then
    printf '%s' "$key"
    return 0
  fi

  return 1
}

expand_targets() {
  local input=("$@")
  local expanded=()
  local item part

  if [[ ${#input[@]} -eq 0 ]]; then
    expanded=("all")
  else
    for item in "${input[@]}"; do
      local -a parts
      parts=("${(@s:,:)item}")
      for part in "${parts[@]}"; do
        part="$(trim "$part")"
        [[ -n "$part" ]] && expanded+=("$part")
      done
    done
  fi
  printf '%s\n' "${expanded[@]}"
}

resolve_modules() {
  local targets=("$@")
  local has_all=0
  local t
  for t in "${targets[@]}"; do
    if [[ "$(echo "$t" | tr '[:upper:]' '[:lower:]')" == "all" ]]; then
      has_all=1
      break
    fi
  done

  if (( has_all == 1 )); then
    printf '%s\n' "${START_ORDER[@]}"
    return 0
  fi

  typeset -A seen=()
  local modules=()
  local module
  for t in "${targets[@]}"; do
    module="$(module_from_target "$t" || true)"
    if [[ -z "$module" ]]; then
      err "Unknown service: $t"
      return 1
    fi
    if [[ -z "${seen[$module]:-}" ]]; then
      modules+=("$module")
      seen[$module]=1
    fi
  done

  printf '%s\n' "${modules[@]}"
}

order_modules_for_start() {
  local selected=("$@")
  typeset -A selected_set=()
  typeset -A added_set=()
  local out=()
  local module

  for module in "${selected[@]}"; do
    selected_set[$module]=1
  done

  for module in "${START_ORDER[@]}"; do
    if [[ -n "${selected_set[$module]+1}" ]]; then
      out+=("$module")
      added_set[$module]=1
    fi
  done

  for module in "${selected[@]}"; do
    if [[ -z "${added_set[$module]+1}" ]]; then
      out+=("$module")
    fi
  done

  printf '%s\n' "${out[@]}"
}

order_modules_for_stop() {
  local selected=("$@")
  typeset -A selected_set=()
  typeset -A added_set=()
  local out=()
  local module i

  for module in "${selected[@]}"; do
    selected_set[$module]=1
  done

  for (( i=${#START_ORDER[@]}; i>=1; i-- )); do
    module="${START_ORDER[$i]}"
    if [[ -n "${selected_set[$module]+1}" ]]; then
      out+=("$module")
      added_set[$module]=1
    fi
  done

  for module in "${selected[@]}"; do
    if [[ -z "${added_set[$module]+1}" ]]; then
      out+=("$module")
    fi
  done

  printf '%s\n' "${out[@]}"
}

wait_for_port_state() {
  local port="$1"
  local expect="$2" # up|down
  local timeout="$3"
  local waited=0

  while (( waited < timeout )); do
    local listening=0
    if lsof -tiTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
      listening=1
    fi

    if [[ "$expect" == "up" && "$listening" == "1" ]]; then
      return 0
    fi
    if [[ "$expect" == "down" && "$listening" == "0" ]]; then
      return 0
    fi
    sleep 1
    (( waited += 1 ))
  done
  return 1
}

wait_for_port_up_or_exit() {
  local port="$1"
  local starter_pid="$2"
  local timeout="$3"
  local waited=0

  while (( waited < timeout )); do
    if lsof -tiTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
      return 0
    fi

    if [[ -n "$starter_pid" && "$starter_pid" == <-> ]] && ! ps -p "$starter_pid" >/dev/null 2>&1; then
      # Starter process has exited before the service bound the port.
      return 2
    fi

    sleep 1
    (( waited += 1 ))
  done

  return 1
}

pid_matches_service() {
  local pid="$1"
  local module="$2"
  local app_name="${3:-}"
  local cmd=""

  [[ -n "$pid" && "$pid" == <-> ]] || return 1
  ps -p "$pid" >/dev/null 2>&1 || return 1

  cmd="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  if [[ -n "$cmd" ]]; then
    if [[ "$cmd" == *"$module"* ]]; then
      return 0
    fi
    if [[ -n "$app_name" && "$cmd" == *"$app_name"* ]]; then
      return 0
    fi
  fi

  return 1
}

collect_module_pids() {
  local module="$1"
  local app_name="${2:-}"
  local port="${3:-}"
  typeset -A seen=()
  local pid_file="${PID_DIR}/${module}.pid"
  local pid cmd

  if [[ -f "$pid_file" ]]; then
    pid="$(cat "$pid_file" 2>/dev/null || true)"
    if pid_matches_service "$pid" "$module" "$app_name"; then
      seen[$pid]=1
    else
      rm -f "$pid_file"
    fi
  fi

  while IFS= read -r pid; do
    [[ -n "$pid" ]] && seen[$pid]=1
  done < <(pgrep -f "spring-boot:run.*${module}" 2>/dev/null || true)

  while IFS= read -r pid; do
    [[ -n "$pid" ]] && seen[$pid]=1
  done < <(pgrep -f "${module}.*spring-boot:run" 2>/dev/null || true)

  while IFS= read -r pid; do
    [[ -n "$pid" ]] && seen[$pid]=1
  done < <(pgrep -f "${module}.*\\.jar" 2>/dev/null || true)

  if [[ -n "$app_name" ]]; then
    while IFS= read -r pid; do
      [[ -z "$pid" ]] && continue
      cmd="$(ps -p "$pid" -o command= 2>/dev/null || true)"
      if [[ "$cmd" == *"$module"* || "$cmd" == *"$app_name"* ]]; then
        seen[$pid]=1
      fi
    done < <(pgrep -f "$app_name" 2>/dev/null || true)
  fi

  printf '%s\n' "${(@k)seen}"
}

stop_service() {
  local module="$1"
  resolve_service_meta "$module" || return 1

  local port="${META_PORT[$module]}"
  local app="${META_APP[$module]}"
  local pid_file="${PID_DIR}/${module}.pid"
  local pids=()
  local pid

  while IFS= read -r pid; do
    [[ -n "$pid" ]] && pids+=("$pid")
  done < <(collect_module_pids "$module" "$app" "$port")

  if [[ ${#pids[@]} -eq 0 ]]; then
    info "stop ${module}: no running process found."
    rm -f "$pid_file"
    return 0
  fi

  info "stop ${module}: port=${port}, pids=${pids[*]}"
  for pid in "${pids[@]}"; do
    kill "$pid" >/dev/null 2>&1 || true
  done

  if ! wait_for_port_state "$port" "down" "$STOP_TIMEOUT_SEC"; then
    warn "stop ${module}: graceful stop timeout, force kill."
    for pid in "${pids[@]}"; do
      kill -9 "$pid" >/dev/null 2>&1 || true
    done
    if ! wait_for_port_state "$port" "down" 5; then
      warn "stop ${module}: port ${port} still occupied."
      lsof -nP -iTCP:"${port}" -sTCP:LISTEN || true
    fi
  fi

  rm -f "$pid_file"
  return 0
}

start_service() {
  local module="$1"
  resolve_service_meta "$module" || return 1

  local port="${META_PORT[$module]}"
  local app="${META_APP[$module]}"
  local dataid="${META_DATAID[$module]}"
  local source="${META_SOURCE[$module]}"
  local module_dir="${ROOT_DIR}/${module}"
  local jar_file=""
  local log_file="${LOG_DIR}/${module}.log"
  local pid_file="${PID_DIR}/${module}.pid"
  local starter_pid=""
  local existing_pids=()
  local mysql_password="${MYSQL_PASSWORD:-root123456}"
  local db_password="${DB_PASSWORD:-${mysql_password}}"
  local db_username="${DB_USERNAME:-root}"
  local mysql_username="${MYSQL_USERNAME:-${db_username}}"
  local spring_profile="${SPRING_PROFILES_ACTIVE:-dev}"
  local java_opts_str="${JAVA_LOW_MEM_OPTS:-}"
  local -a java_opts=()
  local pid

  if [[ ! -d "$module_dir" ]]; then
    err "start ${module}: module dir not found: ${module_dir}"
    return 1
  fi
  jar_file="$(resolve_module_jar "$module" || true)"
  if [[ -z "$jar_file" ]]; then
    err "start ${module}: runnable jar not found in ${module_dir}/target/${module}-*.jar"
    err "build hint: mvn -pl ${module} -am clean package -DskipTests"
    return 1
  fi
  if [[ -n "$java_opts_str" ]]; then
    java_opts=(${=java_opts_str})
  fi

  while IFS= read -r pid; do
    [[ -n "$pid" ]] && existing_pids+=("$pid")
  done < <(collect_module_pids "$module" "$app" "$port")

  if lsof -tiTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
    if [[ ${#existing_pids[@]} -gt 0 ]]; then
      info "start ${module}: already running on :${port}, skip."
      return 0
    fi
    err "start ${module}: port ${port} occupied by other process."
    lsof -nP -iTCP:"${port}" -sTCP:LISTEN || true
    return 1
  fi

  mkdir -p "$LOG_DIR" "$PID_DIR" "${module_dir}/logs"

  info "start ${module}: port=${port}, app=${app}, dataId=${dataid}, source=${source}, jar=$(basename "$jar_file")"
  (
    cd "$module_dir"
    # zsh "&!" = run in background and disown, avoids child shutdown when parent shell exits.
    nohup env \
      SERVER_PORT="$port" \
      SPRING_PROFILES_ACTIVE="$spring_profile" \
      DB_USERNAME="$db_username" \
      DB_PASSWORD="$db_password" \
      MYSQL_USERNAME="$mysql_username" \
      MYSQL_PASSWORD="$mysql_password" \
      "$JAVA_CMD" "${java_opts[@]}" -jar "$jar_file" < /dev/null >"$log_file" 2>&1 &!
    echo "$!" > "$pid_file"
  )
  starter_pid="$(cat "$pid_file" 2>/dev/null || true)"

  local wait_rc=0
  if wait_for_port_up_or_exit "$port" "$starter_pid" "$START_TIMEOUT_SEC"; then
    local listener_pid
    listener_pid="$(lsof -tiTCP:"${port}" -sTCP:LISTEN | head -n 1 || true)"
    if [[ -n "$listener_pid" ]]; then
      echo "$listener_pid" > "$pid_file"
    fi
    info "start ${module}: listening on :${port}"
    return 0
  else
    wait_rc="$?"
  fi

  if [[ "$wait_rc" == "2" ]]; then
    err "start ${module}: startup process exited before listening on :${port}"
  else
    err "start ${module}: failed to listen on :${port} within ${START_TIMEOUT_SEC}s"
  fi
  rm -f "$pid_file"
  warn "last log lines (${log_file}):"
  tail -n 30 "$log_file" || true
  return 1
}

status_service() {
  local module="$1"
  if ! resolve_service_meta "$module"; then
    printf "%-24s %-8s %-10s %-8s %-16s %s\n" "$module" "-" "ERROR" "-" "-" "metadata resolve failed"
    return
  fi

  local port="${META_PORT[$module]}"
  local source="${META_SOURCE[$module]}"
  local dataid="${META_DATAID[$module]}"
  local app="${META_APP[$module]}"
  local listener_pids=()
  local matched_pids=()
  local pid pids cmd

  while IFS= read -r pid; do
    [[ -n "$pid" ]] && listener_pids+=("$pid")
  done < <(lsof -tiTCP:"${port}" -sTCP:LISTEN 2>/dev/null || true)

  for pid in "${listener_pids[@]}"; do
    if pid_matches_service "$pid" "$module" "$app"; then
      matched_pids+=("$pid")
    fi
  done

  if [[ ${#matched_pids[@]} -gt 0 ]]; then
    pids="$(printf '%s\n' "${matched_pids[@]}" | tr '\n' ',' | sed 's/,$//')"
    printf "%-24s %-8s %-10s %-8s %-16s %s\n" "$module" "$port" "RUNNING" "$source" "$app" "pids=${pids}, dataId=${dataid}"
    return
  fi

  if [[ ${#listener_pids[@]} -gt 0 ]]; then
    pids="$(printf '%s\n' "${listener_pids[@]}" | tr '\n' ',' | sed 's/,$//')"
    cmd="$(ps -p "${listener_pids[1]}" -o command= 2>/dev/null || true)"
    printf "%-24s %-8s %-10s %-8s %-16s %s\n" "$module" "$port" "OCCUPIED" "$source" "$app" "by=${pids}, owner=${cmd:-unknown}, dataId=${dataid}"
    return
  fi

  printf "%-24s %-8s %-10s %-8s %-16s %s\n" "$module" "$port" "STOPPED" "$source" "$app" "dataId=${dataid}"
}

validate_port_conflicts() {
  local modules=("$@")
  typeset -A port_to_modules=()
  local module port entries
  local conflict=0
  local -a mods=()

  for module in "${modules[@]}"; do
    resolve_service_meta "$module" || return 1
    port="${META_PORT[$module]}"
    entries="${port_to_modules[$port]:-}"
    port_to_modules[$port]="${entries} ${module}"
  done

  for port in "${(@k)port_to_modules}"; do
    entries="$(trim "${port_to_modules[$port]:-}")"
    mods=(${=entries})
    if (( ${#mods[@]} > 1 )); then
      conflict=1
      err "Port conflict on :${port} -> ${entries}"
    fi
  done

  if (( conflict == 1 )); then
    err "Port conflicts detected from Nacos config. Fix Nacos before start/restart."
    return 1
  fi
  return 0
}

list_services() {
  local modules=("${START_ORDER[@]}")
  printf "%-24s %-8s %-10s %-8s %-16s %s\n" "MODULE" "PORT" "STATUS" "SOURCE" "APP_NAME" "DETAIL"
  printf "%-24s %-8s %-10s %-8s %-16s %s\n" "------" "----" "------" "------" "--------" "------"
  local module
  for module in "${modules[@]}"; do
    status_service "$module"
  done
}

run_action() {
  local action="$1"
  shift
  local modules=("$@")
  local failed=()
  local module

  case "$action" in
    status)
      printf "%-24s %-8s %-10s %-8s %-16s %s\n" "MODULE" "PORT" "STATUS" "SOURCE" "APP_NAME" "DETAIL"
      printf "%-24s %-8s %-10s %-8s %-16s %s\n" "------" "----" "------" "------" "--------" "------"
      for module in "${modules[@]}"; do
        status_service "$module"
      done
      ;;
    stop)
      while IFS= read -r module; do
        [[ -z "$module" ]] && continue
        if ! stop_service "$module"; then
          failed+=("$module")
        fi
      done < <(order_modules_for_stop "${modules[@]}")
      ;;
    start)
      validate_port_conflicts "${modules[@]}" || return 1
      while IFS= read -r module; do
        [[ -z "$module" ]] && continue
        if ! start_service "$module"; then
          failed+=("$module")
        fi
      done < <(order_modules_for_start "${modules[@]}")
      ;;
    restart)
      validate_port_conflicts "${modules[@]}" || return 1
      while IFS= read -r module; do
        [[ -z "$module" ]] && continue
        if ! stop_service "$module"; then
          failed+=("$module")
        fi
      done < <(order_modules_for_stop "${modules[@]}")
      while IFS= read -r module; do
        [[ -z "$module" ]] && continue
        if ! start_service "$module"; then
          failed+=("$module")
        fi
      done < <(order_modules_for_start "${modules[@]}")
      ;;
    *)
      err "Unknown action: ${action}"
      usage
      return 1
      ;;
  esac

  if [[ ${#failed[@]} -gt 0 ]]; then
    err "Action ${action} completed with failures: ${failed[*]}"
    return 1
  fi
  return 0
}

main() {
  local action="${1:-help}"
  shift || true

  case "$action" in
    help|-h|--help)
      usage
      return 0
      ;;
    list)
      list_services
      return 0
      ;;
  esac

  ensure_java
  require_cmd "$JAVA_CMD"
  require_cmd curl
  require_cmd lsof
  require_cmd pgrep
  require_cmd pkill
  require_cmd nohup
  require_cmd ps

  init_aliases

  local expanded=()
  local target
  while IFS= read -r target; do
    [[ -n "$target" ]] && expanded+=("$target")
  done < <(expand_targets "$@")

  local modules=()
  local m
  while IFS= read -r m; do
    [[ -n "$m" ]] && modules+=("$m")
  done < <(resolve_modules "${expanded[@]}")

  if [[ ${#modules[@]} -eq 0 ]]; then
    err "No modules selected."
    return 1
  fi

  run_action "$action" "${modules[@]}"
}

main "$@"
