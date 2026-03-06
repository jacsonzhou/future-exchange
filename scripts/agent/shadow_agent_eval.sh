#!/usr/bin/env bash
set -euo pipefail

# Shadow Agent 体验脚本
# 用途:
# 1) 通过网关跑 preview -> validate -> adopt -> replay 闭环
# 2) 产出可追溯日志，验证系统是否真实促进“纪律化训练”
#
# 示例:
#   bash scripts/agent/shadow_agent_eval.sh
#   BASE_URL=http://127.0.0.1:8082 ROUNDS=20 USER_ID=1001 bash scripts/agent/shadow_agent_eval.sh

BASE_URL="${BASE_URL:-http://127.0.0.1:8082}"
ROUNDS="${ROUNDS:-12}"
USER_ID="${USER_ID:-1001}"
BASE="${BASE_URL%/}/api/v1/agent"
NOW_TS="$(date +%s)"
OUT_DIR="/tmp/shadow_agent_eval_${NOW_TS}"

mkdir -p "${OUT_DIR}"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing command: $1" >&2
    exit 1
  }
}

require_cmd curl
require_cmd jq

echo "== Shadow Agent Eval =="
echo "BASE=${BASE}"
echo "ROUNDS=${ROUNDS}"
echo "OUT_DIR=${OUT_DIR}"
echo ""

curl -sS "${BASE}/health" | jq -c '.' | tee "${OUT_DIR}/health.json"

for i in $(seq 1 "${ROUNDS}"); do
  if [ $((i % 2)) -eq 0 ]; then
    SYMBOL="ETHUSDT"
    STRAT="claude-code-shadow-v1.${i}"
  else
    SYMBOL="BTCUSDT"
    STRAT="codex-agent-shadow-v1.${i}"
  fi

  PRE=$(curl -sS -X POST "${BASE}/decisions/preview" \
    -H 'Content-Type: application/json' \
    -d "{\"symbol\":\"${SYMBOL}\",\"strategyVersion\":\"${STRAT}\",\"interval\":\"1m\",\"marketState\":\"trend\"}")

  DID=$(echo "${PRE}" | jq -r '.data.decisionId')
  CONF=$(echo "${PRE}" | jq -r '.data.confidence')
  EXP=$(echo "${PRE}" | jq -r '.data.exposure')

  VAL=$(curl -sS -X POST "${BASE}/decisions/validate" \
    -H 'Content-Type: application/json' \
    -d "{\"decisionId\":\"${DID}\"}")
  PASSED=$(echo "${VAL}" | jq -r '.data.passed')

  ADOPT_STATUS="skipped"
  if [ "${PASSED}" = "true" ]; then
    AD=$(curl -sS -X POST "${BASE}/decisions/adopt" \
      -H 'Content-Type: application/json' \
      -d "{\"decisionId\":\"${DID}\"}")
    ADOPT_STATUS=$(echo "${AD}" | jq -r '.data.status')
  fi

  RP=$(curl -sS -X POST "${BASE}/replays" \
    -H 'Content-Type: application/json' \
    -d "{\"decisionId\":\"${DID}\",\"paperOrderId\":\"paper_shadow_${NOW_TS}_${i}\",\"symbol\":\"${SYMBOL}\",\"notes\":\"shadow-drill\"}")
  RID=$(echo "${RP}" | jq -r '.data.id')

  printf "%s|%s|conf=%s|exp=%s|passed=%s|adopt=%s|replay=%s\n" \
    "${i}" "${DID}" "${CONF}" "${EXP}" "${PASSED}" "${ADOPT_STATUS}" "${RID}" \
    | tee -a "${OUT_DIR}/drill.log" >/dev/null
done

curl -sS "${BASE}/decisions/logs" | jq -c '.data[:20]' > "${OUT_DIR}/decision_logs.json"
curl -sS "${BASE}/score/profile?subjectType=AGENT&subjectId=codex-agent" | jq -c '.data' > "${OUT_DIR}/score_profile_agent.json"
curl -sS "${BASE}/score/profile?subjectType=HUMAN&subjectId=user_${USER_ID}&userId=${USER_ID}" | jq -c '.data' > "${OUT_DIR}/score_profile_human.json"
curl -sS "${BASE}/score/leaderboard" | jq -c '.data' > "${OUT_DIR}/leaderboard.json"
curl -sS "${BASE}/growth/weekly-plan?userId=${USER_ID}" | jq -c '.data' > "${OUT_DIR}/weekly_plan_before.json"

curl -sS -X POST "${BASE}/growth/weekly-plan?userId=${USER_ID}" \
  -H 'Content-Type: application/json' \
  -d '{"items":["每天完成2次preview-validate-adopt闭环","每日1次复盘并输出1条改进假设","连续5天遵守风险暴露<45%"]}' \
  | jq -c '.data' > "${OUT_DIR}/weekly_plan_after.json"

jq -c 'map(.status) | group_by(.) | map({status:.[0], count:length})' "${OUT_DIR}/decision_logs.json" > "${OUT_DIR}/decision_log_status_summary.json"

echo ""
echo "== Completed =="
echo "drill log: ${OUT_DIR}/drill.log"
echo "status summary: ${OUT_DIR}/decision_log_status_summary.json"
echo "score(agent): ${OUT_DIR}/score_profile_agent.json"
echo "score(human): ${OUT_DIR}/score_profile_human.json"
echo "leaderboard: ${OUT_DIR}/leaderboard.json"
echo "weekly plan(after): ${OUT_DIR}/weekly_plan_after.json"
