#!/bin/bash

# Ledger Core 快速启动脚本

set -e

echo "========== Ledger Core 启动 =========="

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
mysql -u root -proot -e "CREATE DATABASE IF NOT EXISTS exchange_ledger DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
echo "✅ 数据库创建成功"

# 3. 执行建表脚本
echo "[3/5] 执行建表脚本..."
mysql -u root -proot exchange_ledger < ../sql/ledger_schema_production.sql
echo "✅ 表结构创建成功"

# 4. 检查Kafka
echo "[4/5] 检查Kafka..."
if nc -z localhost 9092 2>/dev/null; then
    echo "✅ Kafka正常运行"
else
    echo "⚠️  Kafka未启动，请先启动Kafka"
    echo "   提示：执行 ../init_kafka.sh"
fi

# 5. 启动服务
echo "[5/5] 启动Ledger Core..."
mvn spring-boot:run

echo "========== 启动完成 =========="






