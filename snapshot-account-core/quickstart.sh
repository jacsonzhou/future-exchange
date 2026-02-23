#!/bin/bash

# Snapshot Account Core 快速启动脚本

set -e

echo "========== Snapshot Account Core 启动 =========="

# 1. 检查MySQL
echo "[1/5] 检查MySQL连接..."
mysql -u root -proot -e "SELECT 1" > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "✅ MySQL连接正常"
else
    echo "❌ MySQL连接失败，请检查配置"
    exit 1
fi

# 2. 初始化数据库
echo "[2/5] 初始化数据库..."
mysql -u root -proot -e "CREATE DATABASE IF NOT EXISTS exchange_snapshot DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
echo "✅ 数据库创建成功"

# 3. 执行建表脚本
echo "[3/5] 执行建表脚本..."
if [ -f "../sql/account_snapshot_schema.sql" ]; then
    mysql -u root -proot exchange_snapshot < ../sql/account_snapshot_schema.sql
    echo "✅ 表结构创建成功"
else
    echo "⚠️  建表脚本不存在，跳过"
fi

# 4. 检查Redis
echo "[4/5] 检查Redis..."
redis-cli -n 1 PING > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "✅ Redis正常运行"
else
    echo "⚠️  Redis未启动，请先启动Redis"
fi

# 5. 检查Kafka
echo "[5/5] 检查Kafka..."
if nc -z localhost 9092 2>/dev/null; then
    echo "✅ Kafka正常运行"
else
    echo "⚠️  Kafka未启动，请先启动Kafka"
    echo "   提示：执行 ../init_kafka.sh"
fi

# 6. 启动服务
echo ""
echo "========== 启动 Snapshot Account Core =========="
mvn spring-boot:run

echo "========== 启动完成 =========="

