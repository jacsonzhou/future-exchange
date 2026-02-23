#!/bin/bash
# ============================================
# ClickHouse 启动脚本
# ============================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

echo "========================================"
echo "Starting ClickHouse..."
echo "========================================"

# 检查 Docker 是否安装
if ! command -v docker-compose &> /dev/null; then
    echo "Error: docker-compose is not installed"
    exit 1
fi

# 创建必要的目录
mkdir -p clickhouse/{config.d,users.d,init}
mkdir -p clickhouse/data

# 启动 ClickHouse
docker-compose -f docker-compose.clickhouse.yml up -d

# 等待服务启动
echo "Waiting for ClickHouse to start..."
sleep 5

# 检查健康状态
MAX_RETRY=30
RETRY=0
while [ $RETRY -lt $MAX_RETRY ]; do
    if curl -s http://localhost:8123/ping 2>/dev/null | grep -q "Ok"; then
        echo ""
        echo "========================================"
        echo "ClickHouse is ready!"
        echo "========================================"
        echo "HTTP Interface: http://localhost:8123"
        echo "TCP Interface: localhost:9000"
        echo "Web UI: http://localhost:8088"
        echo ""
        echo "Default credentials:"
        echo "  User: default"
        echo "  Password: clickhouse123456"
        echo "========================================"
        exit 0
    fi
    echo -n "."
    sleep 2
    RETRY=$((RETRY + 1))
done

echo ""
echo "Error: ClickHouse failed to start within 60 seconds"
docker-compose -f docker-compose.clickhouse.yml logs --tail=50
exit 1
