#!/bin/bash

# OMS Core Service - 快速启动指南

echo "========================================"
echo "  OMS Core Service - Quick Start"
echo "========================================"
echo ""

# 检查MySQL
echo "[Step 1] 检查MySQL..."
if ! command -v mysql &> /dev/null; then
    echo "❌ MySQL未安装，请先安装MySQL 8.0+"
    exit 1
fi
echo "✅ MySQL已安装"
echo ""

# 创建数据库
echo "[Step 2] 创建数据库..."
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS exchange_oms DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
echo "✅ 数据库 exchange_oms 创建成功"
echo ""

# 执行SQL脚本
echo "[Step 3] 执行SQL脚本..."
mysql -u root -p exchange_oms < ../sql/oms_schema_enhanced.sql
echo "✅ 数据库表创建成功"
echo ""

# 检查Maven
echo "[Step 4] 检查Maven..."
if ! command -v mvn &> /dev/null; then
    echo "❌ Maven未安装，请先安装Maven 3.6+"
    exit 1
fi
echo "✅ Maven已安装"
echo ""

# 编译项目
echo "[Step 5] 编译项目..."
cd /Users/zhoufan/project/future-exchange/oms-core
mvn clean package -DskipTests
if [ $? -eq 0 ]; then
    echo "✅ 项目编译成功"
else
    echo "❌ 项目编译失败"
    exit 1
fi
echo ""

# 启动服务
echo "[Step 6] 启动OMS服务..."
echo "运行命令: java -jar target/oms-core-1.0-SNAPSHOT.jar"
echo ""
echo "服务将在 http://localhost:8081 启动"
echo ""
echo "启动成功后，可以运行测试脚本："
echo "  ./test.sh"
echo ""
echo "========================================"
echo "  Quick Start Guide Completed"
echo "========================================"

