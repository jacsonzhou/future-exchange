#!/bin/bash

# K线API快速修复脚本
# 用途：诊断并修复market-price-core的K线API 500错误
# 创建时间：2026-03-07

set -e

echo "=========================================="
echo "K线API快速修复脚本"
echo "=========================================="
echo ""

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 步骤1：诊断ClickHouse
echo -e "${YELLOW}[1/5] 检查ClickHouse服务...${NC}"

if command -v clickhouse-client &> /dev/null; then
    echo -e "${GREEN}✓ ClickHouse客户端已安装${NC}"

    if clickhouse-client --query "SELECT version()" &> /dev/null; then
        echo -e "${GREEN}✓ ClickHouse服务运行中${NC}"
        CLICKHOUSE_AVAILABLE=true

        # 检查数据库
        DB_EXISTS=$(clickhouse-client --query "SHOW DATABASES" | grep -c "exchange_kline" || true)
        if [ "$DB_EXISTS" -eq 1 ]; then
            echo -e "${GREEN}✓ exchange_kline数据库存在${NC}"
        else
            echo -e "${RED}✗ exchange_kline数据库不存在${NC}"
            CLICKHOUSE_AVAILABLE=false
        fi
    else
        echo -e "${RED}✗ ClickHouse服务未运行${NC}"
        CLICKHOUSE_AVAILABLE=false
    fi
else
    echo -e "${RED}✗ ClickHouse未安装${NC}"
    CLICKHOUSE_AVAILABLE=false
fi

echo ""

# 步骤2：选择修复方案
echo -e "${YELLOW}[2/5] 选择修复方案${NC}"

if [ "$CLICKHOUSE_AVAILABLE" = true ]; then
    echo -e "${GREEN}方案A: 使用ClickHouse（推荐）${NC}"
    echo "将配置ClickHouse表结构并启用服务"
    REPAIR_MODE="clickhouse"
else
    echo -e "${YELLOW}方案B: 禁用ClickHouse（快速修复）${NC}"
    echo "将禁用ClickHouse，使用缓存降级"
    REPAIR_MODE="cache"
fi

echo ""
read -p "按Enter键继续使用 ${REPAIR_MODE} 模式，或输入'c'切换模式: " choice

if [ "$choice" = "c" ]; then
    if [ "$REPAIR_MODE" = "clickhouse" ]; then
        REPAIR_MODE="cache"
    else
        REPAIR_MODE="clickhouse"
    fi
fi

echo -e "${GREEN}选择的修复模式: ${REPAIR_MODE}${NC}"
echo ""

# 步骤3：执行修复
echo -e "${YELLOW}[3/5] 执行修复${NC}"

if [ "$REPAIR_MODE" = "cache" ]; then
    # 方案B：禁用ClickHouse
    echo "禁用ClickHouse配置..."

    CONFIG_FILE="./nacos-configs/market-price-core-dev.yml"

    if [ -f "$CONFIG_FILE" ]; then
        # 添加 enabled: false
        if grep -q "clickhouse:" "$CONFIG_FILE"; then
            # 检查是否已有enabled配置
            if grep -q "enabled:" "$CONFIG_FILE" | grep -A1 "clickhouse:"; then
                # 替换现有enabled
                sed -i.bak '/clickhouse:/,/url:/ s/enabled: true/enabled: false/' "$CONFIG_FILE"
            else
                # 添加enabled: false
                sed -i.bak '/clickhouse:/a\    enabled: false' "$CONFIG_FILE"
            fi
            echo -e "${GREEN}✓ 已禁用ClickHouse${NC}"
        else
            echo -e "${YELLOW}! 配置文件中未找到clickhouse配置${NC}"
        fi
    else
        echo -e "${RED}✗ 配置文件不存在: ${CONFIG_FILE}${NC}"
        exit 1
    fi

    echo ""
    echo -e "${GREEN}快速修复完成${NC}"
    echo "注意：此模式下只能查询当前K线（缓存数据），历史查询将返回空"

elif [ "$REPAIR_MODE" = "clickhouse" ]; then
    # 方案A：配置ClickHouse
    echo "配置ClickHouse..."

    # 创建数据库
    clickhouse-client -u default --password clickhouse123456 --query "CREATE DATABASE IF NOT EXISTS exchange_kline" 2>/dev/null || {
        echo -e "${RED}✗ 创建数据库失败，请检查密码${NC}"
        exit 1
    }

    echo -e "${GREEN}✓ 数据库创建成功${NC}"

    # 创建表结构
    echo "创建K线表结构..."

    clickhouse-client -u default --password clickhouse123456 --database exchange_kline --multiquery <<'EOF'
CREATE TABLE IF NOT EXISTS kline_data (
    symbol String,
    interval String,
    open_time DateTime64(3, 'UTC'),
    close_time DateTime64(3, 'UTC'),
    open_price Decimal64(8),
    high_price Decimal64(8),
    low_price Decimal64(8),
    close_price Decimal64(8),
    volume Decimal64(8),
    quote_volume Decimal64(8),
    trade_count UInt32,
    taker_buy_volume Decimal64(8),
    taker_buy_quote_volume Decimal64(8),
    is_closed UInt8,
    source String DEFAULT 'binance',
    created_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(open_time)
ORDER BY (symbol, interval, open_time)
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS kline_realtime (
    symbol String,
    interval String,
    open_time DateTime64(3, 'UTC'),
    close_time DateTime64(3, 'UTC'),
    open_price Decimal64(8),
    high_price Decimal64(8),
    low_price Decimal64(8),
    close_price Decimal64(8),
    volume Decimal64(8),
    quote_volume Decimal64(8),
    trade_count UInt32,
    taker_buy_volume Decimal64(8),
    taker_buy_quote_volume Decimal64(8),
    is_closed UInt8,
    source String DEFAULT 'binance',
    updated_at DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY toYYYYMM(open_time)
ORDER BY (symbol, interval, open_time)
SETTINGS index_granularity = 8192;
EOF

    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ 表结构创建成功${NC}"
    else
        echo -e "${RED}✗ 表结构创建失败${NC}"
        exit 1
    fi

    # 确保配置启用
    CONFIG_FILE="./nacos-configs/market-price-core-dev.yml"
    if [ -f "$CONFIG_FILE" ]; then
        sed -i.bak '/clickhouse:/,/url:/ s/enabled: false/enabled: true/' "$CONFIG_FILE"
        echo -e "${GREEN}✓ 已启用ClickHouse配置${NC}"
    fi
fi

echo ""

# 步骤4：重启服务
echo -e "${YELLOW}[4/5] 重启market-price-core服务${NC}"

# 查找进程
PID=$(lsof -t -i:8095 || true)

if [ -n "$PID" ]; then
    echo "停止现有服务 (PID: $PID)..."
    kill -9 $PID
    sleep 2
    echo -e "${GREEN}✓ 服务已停止${NC}"
else
    echo "服务未运行"
fi

echo ""
echo "请手动重启服务："
echo "  cd market-price-core"
echo "  mvn spring-boot:run"
echo ""

# 步骤5：验证
echo -e "${YELLOW}[5/5] 验证修复${NC}"
echo ""
echo "等待服务启动后，执行以下命令验证："
echo ""
echo -e "${GREEN}# 测试1m K线（应该有数据）${NC}"
echo "curl 'http://localhost:8095/api/v1/klines?symbol=BTCUSDT&interval=1m&limit=10'"
echo ""

if [ "$REPAIR_MODE" = "clickhouse" ]; then
    echo -e "${GREEN}# 测试1h K线（需要等待回补完成）${NC}"
    echo "curl 'http://localhost:8095/api/v1/klines?symbol=BTCUSDT&interval=1h&limit=500'"
    echo ""
    echo -e "${YELLOW}注意：首次启动会自动回补历史数据，请等待5-10分钟${NC}"
    echo "查看回补进度："
    echo "  tail -f logs/market-price-core.log | grep Backfill"
fi

echo ""
echo "=========================================="
echo -e "${GREEN}修复脚本执行完成！${NC}"
echo "=========================================="
echo ""
echo "详细修复指南：docs/KLINE_API_FIX_GUIDE.md"
echo ""

# 生成诊断报告
echo "生成诊断报告..."
cat > /tmp/kline_diagnostic.txt <<EOF
K线API诊断报告
生成时间: $(date)

ClickHouse状态:
$(clickhouse-client --query "SELECT version()" 2>&1 || echo "未安装或未运行")

数据库状态:
$(clickhouse-client --query "SHOW DATABASES" 2>&1 || echo "无法连接")

修复模式: ${REPAIR_MODE}

配置文件:
$(grep -A5 "clickhouse:" ./nacos-configs/market-price-core-dev.yml 2>/dev/null || echo "配置文件未找到")
EOF

echo -e "${GREEN}✓ 诊断报告已保存：/tmp/kline_diagnostic.txt${NC}"
cat /tmp/kline_diagnostic.txt
