#!/bin/bash
# ============================================
# ClickHouse 完整安装和测试脚本
# ============================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

echo "========================================"
echo "ClickHouse Setup Script"
echo "========================================"

# 1. 启动 ClickHouse
echo ""
echo "[1/5] Starting ClickHouse..."
./scripts/start-clickhouse.sh

# 2. 等待 ClickHouse 完全启动
echo ""
echo "[2/5] Waiting for ClickHouse to be ready..."
sleep 5

# 3. 验证表结构
echo ""
echo "[3/5] Verifying table structure..."
curl -s -X POST \
    -H "Content-Type: application/json" \
    -d "SELECT name, engine FROM system.tables WHERE database = 'exchange_kline'" \
    http://localhost:8123 \
    | head -20

echo ""
echo "Tables created:"
curl -s -X POST \
    -d "SHOW TABLES FROM exchange_kline" \
    http://localhost:8123

# 4. 插入测试数据
echo ""
echo "[4/5] Inserting test data..."
curl -s -X POST \
    -H "Content-Type: application/json" \
    -d "
    INSERT INTO exchange_kline.kline_data 
    (symbol, interval, open_time, close_time, open_price, high_price, low_price, close_price, volume, quote_volume, trade_count, taker_buy_volume, taker_buy_quote_volume)
    VALUES 
    ('BTCUSDT', '1m', now() - INTERVAL 5 MINUTE, now() - INTERVAL 4 MINUTE - 1 SECOND, 50000.00, 51000.00, 49500.00, 50500.00, 10.5, 525000.00, 100, 5.25, 262500.00),
    ('BTCUSDT', '1m', now() - INTERVAL 4 MINUTE, now() - INTERVAL 3 MINUTE - 1 SECOND, 50500.00, 51500.00, 50000.00, 51200.00, 12.3, 615000.00, 120, 6.15, 307500.00),
    ('BTCUSDT', '1m', now() - INTERVAL 3 MINUTE, now() - INTERVAL 2 MINUTE - 1 SECOND, 51200.00, 52000.00, 50800.00, 51800.00, 15.0, 777000.00, 150, 7.50, 388500.00)
    " \
    http://localhost:8123

echo "Test data inserted."

# 5. 查询测试
echo ""
echo "[5/5] Querying test data..."
echo ""
echo "Sample Kline Data:"
curl -s -X POST \
    -H "Content-Type: application/json" \
    -d "SELECT * FROM exchange_kline.kline_data LIMIT 5 FORMAT PrettyCompact" \
    http://localhost:8123

echo ""
echo "========================================"
echo "ClickHouse Setup Complete!"
echo "========================================"
echo ""
echo "Access Information:"
echo "  - HTTP: http://localhost:8123"
echo "  - TCP: localhost:9000"
echo "  - Web UI: http://localhost:8088"
echo ""
echo "Default Credentials:"
echo "  - User: default"
echo "  - Password: clickhouse123456"
echo ""
echo "Database: exchange_kline"
echo "Tables:"
echo "  - kline_data (历史K线)"
echo "  - kline_realtime (实时K线)"
echo "  - trade_data (成交明细)"
echo "========================================"
