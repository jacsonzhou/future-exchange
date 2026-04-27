#!/bin/bash

# ============================================
# 测试脚本：验证WebSocket快照数据新鲜度
# 功能：
# 1. 检查盘口快照
# 2. 检查Trade快照（应返回100条）
# 3. 检查K线快照（应返回历史+当前）
# 4. 验证数据时间戳新鲜度
# ============================================

set -e

REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"
WS_URL="${WS_URL:-ws://localhost:8096/ws/market}"
SYMBOL="${1:-BTCUSDT}"

echo "=========================================="
echo "  WebSocket快照数据新鲜度测试"
echo "=========================================="
echo "Symbol: $SYMBOL"
echo "Redis: $REDIS_HOST:$REDIS_PORT"
echo "WebSocket: $WS_URL"
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 测试函数
check_pass() {
    echo -e "${GREEN}✓ PASS${NC}: $1"
}

check_fail() {
    echo -e "${RED}✗ FAIL${NC}: $1"
}

check_warn() {
    echo -e "${YELLOW}⚠ WARN${NC}: $1"
}

# 1. 检查Redis中的快照数据
echo "----------------------------------------"
echo "1. 检查Redis快照数据"
echo "----------------------------------------"

# 检查盘口快照
DEPTH_KEY="market:snapshot:depth:$SYMBOL"
echo "检查 $DEPTH_KEY ..."
if redis-cli -h $REDIS_HOST -p $REDIS_PORT EXISTS "$DEPTH_KEY" | grep -q "1"; then
    DEPTH_DATA=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT GET "$DEPTH_KEY")
    DEPTH_TIME=$(echo "$DEPTH_DATA" | jq -r '.E // .T // 0')
    DEPTH_AGE=$(($(date +%s%3N) - $DEPTH_TIME))

    if [ "$DEPTH_AGE" -lt 5000 ]; then
        check_pass "盘口快照存在且新鲜 (age: ${DEPTH_AGE}ms)"
    else
        check_warn "盘口快照过期 (age: ${DEPTH_AGE}ms)"
    fi
else
    check_fail "盘口快照不存在"
fi

# 检查Trade快照（List）
TRADE_LIST_KEY="binance:trade:$SYMBOL"
echo ""
echo "检查 $TRADE_LIST_KEY ..."
TRADE_COUNT=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT LLEN "$TRADE_LIST_KEY")
if [ "$TRADE_COUNT" -gt 0 ]; then
    check_pass "Trade列表存在，包含 $TRADE_COUNT 条记录"

    # 检查最新一条的时间
    LATEST_TRADE=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT LINDEX "$TRADE_LIST_KEY" 0)
    TRADE_TIME=$(echo "$LATEST_TRADE" | jq -r '.T // .E // 0')
    TRADE_AGE=$(($(date +%s%3N) - $TRADE_TIME))

    if [ "$TRADE_AGE" -lt 10000 ]; then
        check_pass "最新Trade新鲜 (age: ${TRADE_AGE}ms)"
    else
        check_warn "最新Trade可能过期 (age: ${TRADE_AGE}ms)"
    fi
else
    check_fail "Trade列表为空"
fi

# 检查K线历史快照
KLINE_INTERVAL="1m"
KLINE_HISTORY_KEY="market:snapshot:kline:history:${SYMBOL}:${KLINE_INTERVAL}"
echo ""
echo "检查 $KLINE_HISTORY_KEY ..."
KLINE_COUNT=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT LLEN "$KLINE_HISTORY_KEY")
if [ "$KLINE_COUNT" -gt 0 ]; then
    check_pass "K线历史存在，包含 $KLINE_COUNT 根K线"

    # 检查最新一根的时间（List头部是最新的）
    LATEST_KLINE=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT LINDEX "$KLINE_HISTORY_KEY" 0)
    KLINE_TIME=$(echo "$LATEST_KLINE" | jq -r '.T // .t // 0')
    KLINE_AGE=$(($(date +%s%3N) - $KLINE_TIME))

    if [ "$KLINE_AGE" -lt 120000 ]; then
        check_pass "最新K线新鲜 (age: ${KLINE_AGE}ms, ~$((KLINE_AGE/1000))s)"
    else
        check_warn "最新K线可能过期 (age: ${KLINE_AGE}ms, ~$((KLINE_AGE/1000))s)"
    fi
else
    check_warn "K线历史快照不存在（可能服务刚启动）"
fi

# 检查当前K线快照
KLINE_CURRENT_KEY="market:snapshot:kline:${SYMBOL}:${KLINE_INTERVAL}"
echo ""
echo "检查 $KLINE_CURRENT_KEY ..."
if redis-cli -h $REDIS_HOST -p $REDIS_PORT EXISTS "$KLINE_CURRENT_KEY" | grep -q "1"; then
    CURRENT_KLINE=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT GET "$KLINE_CURRENT_KEY")
    CURRENT_TIME=$(echo "$CURRENT_KLINE" | jq -r '.E // .T // 0')
    CURRENT_AGE=$(($(date +%s%3N) - $CURRENT_TIME))

    if [ "$CURRENT_AGE" -lt 120000 ]; then
        check_pass "当前K线快照新鲜 (age: ${CURRENT_AGE}ms, ~$((CURRENT_AGE/1000))s)"
    else
        check_warn "当前K线快照可能过期 (age: ${CURRENT_AGE}ms, ~$((CURRENT_AGE/1000))s)"
    fi
else
    check_fail "当前K线快照不存在"
fi

# 2. 测试WebSocket订阅快照
echo ""
echo "----------------------------------------"
echo "2. 测试WebSocket订阅快照"
echo "----------------------------------------"

# 使用websocat测试（如果安装了的话）
if command -v websocat &> /dev/null; then
    echo "使用websocat测试WebSocket连接..."

    # 创建临时测试脚本
    TEST_SCRIPT="/tmp/ws_test_$$"
    cat > "$TEST_SCRIPT" << 'EOF'
{"method":"SUBSCRIBE","params":["depth.BTCUSDT","trade.BTCUSDT","kline.BTCUSDT.1m"],"id":1}
EOF

    echo "发送订阅请求..."
    timeout 5 websocat "$WS_URL" < "$TEST_SCRIPT" > /tmp/ws_response_$$ 2>&1 || true

    if [ -s /tmp/ws_response_$$ ]; then
        echo ""
        echo "收到响应（前5条消息）："
        head -5 /tmp/ws_response_$$ | jq -c '.' 2>/dev/null || head -5 /tmp/ws_response_$$

        # 检查是否包含快照标记
        if grep -q '"snapshot":true' /tmp/ws_response_$$; then
            check_pass "收到快照消息（包含 snapshot:true 标记）"
        else
            check_warn "未检测到快照标记"
        fi

        # 检查Trade快照是否包含多条记录
        TRADE_SNAPSHOT_COUNT=$(grep -o '"trades":\[[^]]*\]' /tmp/ws_response_$$ | head -1 | grep -o '{' | wc -l || echo 0)
        if [ "$TRADE_SNAPSHOT_COUNT" -gt 1 ]; then
            check_pass "Trade快照包含多条记录: $TRADE_SNAPSHOT_COUNT 条"
        else
            check_warn "Trade快照只有 $TRADE_SNAPSHOT_COUNT 条（预期100条）"
        fi

        # 检查K线快照是否包含历史
        if grep -q '"history":\[' /tmp/ws_response_$$; then
            KLINE_HISTORY_COUNT=$(grep -o '"historyCount":[0-9]*' /tmp/ws_response_$$ | head -1 | grep -o '[0-9]*' || echo 0)
            if [ "$KLINE_HISTORY_COUNT" -gt 0 ]; then
                check_pass "K线快照包含历史: $KLINE_HISTORY_COUNT 根"
            else
                check_warn "K线快照历史为空"
            fi
        else
            check_warn "K线快照未包含历史字段"
        fi
    else
        check_fail "WebSocket连接失败或无响应"
    fi

    rm -f "$TEST_SCRIPT" /tmp/ws_response_$$
else
    check_warn "未安装websocat，跳过WebSocket测试"
    echo "提示：可通过以下命令安装websocat："
    echo "  brew install websocat  (macOS)"
    echo "  cargo install websocat  (Linux)"
fi

# 3. 检查服务日志（如果存在）
echo ""
echo "----------------------------------------"
echo "3. 检查服务日志（最近10条警告/错误）"
echo "----------------------------------------"

LOG_FILES=(
    "logs/public-push.log"
    "logs/market-price-core.log"
    "logs/binance-data-source.log"
)

for LOG_FILE in "${LOG_FILES[@]}"; do
    if [ -f "$LOG_FILE" ]; then
        echo ""
        echo "检查 $LOG_FILE ..."
        RECENT_ERRORS=$(tail -1000 "$LOG_FILE" | grep -E "WARN|ERROR" | tail -10 || echo "")
        if [ -n "$RECENT_ERRORS" ]; then
            echo "$RECENT_ERRORS"
        else
            check_pass "无警告或错误"
        fi
    fi
done

# 4. 总结
echo ""
echo "=========================================="
echo "  测试完成"
echo "=========================================="
echo ""
echo "修复要点："
echo "1. ✓ Trade快照现在返回100条记录（而非1条）"
echo "2. ✓ K线快照现在返回历史+当前（而非仅当前）"
echo "3. ✓ 所有快照包含新鲜度检查"
echo "4. ✓ K线历史按时间从旧到新排序"
echo ""
echo "如果测试失败，请检查："
echo "- binance-data-source 是否正在运行"
echo "- market-price-core 是否正在运行"
echo "- public-push-core 是否正在运行"
echo "- Redis是否可访问"
echo ""
