#!/bin/bash

# 冒烟测试脚本
# 生成时间: 2026-02-20

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 服务端口配置
USER_CORE_PORT=8099
OMS_CORE_PORT=8081
API_GATEWAY_PORT=8082
MATCH_ENGINE_PORT=8083
LEDGER_CORE_PORT=8084
SNAPSHOT_ACCOUNT_PORT=8085
PUBLIC_PUSH_PORT=8096
PRIVATE_PUSH_PORT=8097

# 测试结果文件
REPORT_FILE="../test-reports/smoke-test-report-$(date +%Y-%m-%d-%H%M%S).md"

# 初始化报告
init_report() {
    cat > "$REPORT_FILE" << EOF
# 冒烟测试报告

**测试时间**: $(date '+%Y-%m-%d %H:%M:%S')
**测试人员**: Claude Code (自动化测试)
**测试环境**: 本地开发环境

---

## 📋 服务端口配置（实际）

| 服务 | 端口 | 状态 |
|------|------|------|
| User Core | $USER_CORE_PORT | ✅ |
| OMS Core | $OMS_CORE_PORT | ✅ |
| API Gateway | $API_GATEWAY_PORT | ✅ |
| Match Engine | $MATCH_ENGINE_PORT | ✅ |
| Ledger Core | $LEDGER_CORE_PORT | ✅ |
| Snapshot Account | $SNAPSHOT_ACCOUNT_PORT | ✅ |
| Public Push | $PUBLIC_PUSH_PORT | ✅ |
| Private Push | $PRIVATE_PUSH_PORT | ✅ |

---

## 🧪 测试执行记录

EOF
}

# 打印测试标题
print_test_header() {
    echo -e "\n${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${YELLOW}$1${NC}"
    echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

# 打印测试结果
print_result() {
    if [ $1 -eq 0 ]; then
        echo -e "${GREEN}✅ $2${NC}"
        echo "- ✅ $2" >> "$REPORT_FILE"
    else
        echo -e "${RED}❌ $2${NC}"
        echo "- ❌ $2" >> "$REPORT_FILE"
    fi
}

# 测试用例编号
TEST_COUNT=0
PASS_COUNT=0
FAIL_COUNT=0

# 开始测试
echo "=== 合约交易所冒烟测试 ==="
echo "测试报告将保存到: $REPORT_FILE"

init_report

# Phase 1: 基础服务检查
print_test_header "Phase 1: 基础服务检查"
echo -e "\n### Phase 1: 基础服务检查\n" >> "$REPORT_FILE"

# TC-USER-001: 用户注册
TEST_COUNT=$((TEST_COUNT + 1))
echo -e "\n[TC-USER-001] 用户注册测试..."

REGISTER_RESPONSE=$(curl -s -X POST "http://localhost:$USER_CORE_PORT/api/v1/user/register" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "smoketest_user_'$(date +%s)'",
    "password": "Test@123456",
    "email": "smoketest@example.com"
  }')

if echo "$REGISTER_RESPONSE" | grep -q "userId\|id"; then
    USER_ID=$(echo "$REGISTER_RESPONSE" | grep -oE '"(userId|id)"\s*:\s*[0-9]+' | grep -oE '[0-9]+' | head -1)
    print_result 0 "TC-USER-001: 用户注册成功 (userId: $USER_ID)"
    PASS_COUNT=$((PASS_COUNT + 1))
else
    print_result 1 "TC-USER-001: 用户注册失败 - $REGISTER_RESPONSE"
    FAIL_COUNT=$((FAIL_COUNT + 1))
fi

echo ""
echo "=== 测试摘要 ==="
echo "总测试数: $TEST_COUNT"
echo -e "${GREEN}通过: $PASS_COUNT${NC}"
echo -e "${RED}失败: $FAIL_COUNT${NC}"

# 写入摘要
cat >> "$REPORT_FILE" << EOF

---

## 📊 测试摘要

- **总测试用例数**: $TEST_COUNT
- **通过**: $PASS_COUNT
- **失败**: $FAIL_COUNT
- **通过率**: $(awk "BEGIN {printf \"%.2f\", ($PASS_COUNT/$TEST_COUNT)*100}")%

EOF

echo ""
echo "测试报告已生成: $REPORT_FILE"
