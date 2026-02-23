#!/bin/bash

# 重启修改过的服务脚本
# 修改的服务：api-gateway, oms-core, ledger-core

echo "=========================================="
echo "  重启修改过的服务"
echo "=========================================="
echo ""

PROJECT_ROOT="/Users/zhoufan/project/future-exchange"
cd "$PROJECT_ROOT"

# 1. 停止现有服务
echo "[1] 停止现有服务..."
pkill -f "ledger-core.*jar" 2>/dev/null
pkill -f "oms-core.*spring-boot:run" 2>/dev/null
pkill -f "api-gateway.*jar" 2>/dev/null
sleep 3
echo "✅ 服务已停止"
echo ""

# 2. 检查端口是否释放
echo "[2] 检查端口状态..."
lsof -i :8084 -i :8081 -i :8082 -i :8080 2>/dev/null | grep LISTEN && echo "⚠️  端口仍被占用，等待释放..." && sleep 2 || echo "✅ 端口已释放"
echo ""

# 3. 启动 Ledger Core (8084)
echo "[3] 启动 Ledger Core (端口 8084)..."
cd "$PROJECT_ROOT/ledger-core"
if [ -f "target/ledger-core-1.0-SNAPSHOT.jar" ]; then
    nohup java -jar target/ledger-core-1.0-SNAPSHOT.jar > logs/ledger-core.log 2>&1 &
    LEDGER_PID=$!
    echo "✅ Ledger Core 启动中 (PID: $LEDGER_PID)"
else
    echo "❌ Ledger Core JAR 文件不存在，请先编译: mvn clean package -DskipTests"
fi
echo ""

# 4. 启动 OMS Core (8081)
echo "[4] 启动 OMS Core (端口 8081)..."
cd "$PROJECT_ROOT/oms-core"
if [ -f "pom.xml" ]; then
    nohup mvn spring-boot:run -pl oms-core > logs/oms-core.log 2>&1 &
    OMS_PID=$!
    echo "✅ OMS Core 启动中 (PID: $OMS_PID)"
else
    echo "❌ OMS Core POM 文件不存在"
fi
echo ""

# 5. 启动 API Gateway (8082)
echo "[5] 启动 API Gateway (端口 8082)..."
cd "$PROJECT_ROOT/api-gateway"
if [ -f "target/api-gateway-1.0-SNAPSHOT.jar" ]; then
    nohup java -jar target/api-gateway-1.0-SNAPSHOT.jar > logs/api-gateway.log 2>&1 &
    GATEWAY_PID=$!
    echo "✅ API Gateway 启动中 (PID: $GATEWAY_PID)"
else
    echo "❌ API Gateway JAR 文件不存在，请先编译: mvn clean package -DskipTests"
fi
echo ""

# 6. 等待服务启动
echo "[6] 等待服务启动 (10秒)..."
sleep 10
echo ""

# 7. 检查服务状态
echo "[7] 检查服务状态..."
echo ""
echo "端口监听状态:"
lsof -i :8084 -i :8081 -i :8082 -i :8080 2>/dev/null | grep LISTEN || echo "⚠️  暂无服务监听，可能还在启动中..."
echo ""

echo "服务日志位置:"
echo "  - Ledger Core: $PROJECT_ROOT/ledger-core/logs/ledger-core.log"
echo "  - OMS Core: $PROJECT_ROOT/oms-core/logs/oms-core.log"
echo "  - API Gateway: $PROJECT_ROOT/api-gateway/logs/api-gateway.log"
echo ""

echo "=========================================="
echo "  重启完成！"
echo "=========================================="
echo ""
echo "查看日志:"
echo "  tail -f ledger-core/logs/ledger-core.log"
echo "  tail -f oms-core/logs/oms-core.log"
echo "  tail -f api-gateway/logs/api-gateway.log"
echo ""

