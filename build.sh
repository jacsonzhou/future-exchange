#!/bin/bash

# 合约交易系统快速构建脚本

echo "================================"
echo "合约交易系统 - 快速构建"
echo "================================"

# 检查Java版本
echo ""
echo "1. 检查Java版本..."
java -version
if [ $? -ne 0 ]; then
    echo "❌ Java未安装，请先安装Java 17+"
    exit 1
fi

# 检查Maven
echo ""
echo "2. 检查Maven..."
mvn -version
if [ $? -ne 0 ]; then
    echo "❌ Maven未安装，请先安装Maven 3.8+"
    exit 1
fi

# 清理编译
echo ""
echo "3. 清理旧的编译文件..."
mvn clean

# 编译项目
echo ""
echo "4. 编译项目（跳过测试）..."
mvn package -DskipTests

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ 编译成功！"
    echo ""
    echo "================================"
    echo "下一步操作："
    echo "================================"
    echo ""
    echo "1. 启动基础设施："
    echo "   - MySQL (端口3306)"
    echo "   - Redis (端口6379)"
    echo "   - Nacos (端口8848)"
    echo ""
    echo "2. 初始化数据库："
    echo "   mysql -u root -p exchange_oms < sql/oms_schema.sql"
    echo "   mysql -u root -p exchange_ledger < sql/ledger_schema.sql"
    echo ""
    echo "3. 启动服务（按顺序）："
    echo "   cd match-engine-core && java -jar target/match-engine-core-1.0.0-SNAPSHOT.jar"
    echo "   cd ledger-core && java -jar target/ledger-core-1.0.0-SNAPSHOT.jar"
    echo "   cd snapshot-core && java -jar target/snapshot-core-1.0.0-SNAPSHOT.jar"
    echo "   cd hard-risk-core && java -jar target/hard-risk-core-1.0.0-SNAPSHOT.jar"
    echo "   cd oms-core && java -jar target/oms-core-1.0.0-SNAPSHOT.jar"
    echo "   cd api-gateway && java -jar target/api-gateway-1.0.0-SNAPSHOT.jar"
    echo ""
    echo "4. 测试下单："
    echo "   curl -X POST http://localhost:8080/api/order/create \\"
    echo "     -H 'Content-Type: application/json' \\"
    echo "     -d '{\"userId\":1,\"symbol\":\"BTCUSDT\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"price\":5000000000000,\"quantity\":100000000,\"leverage\":10}'"
    echo ""
else
    echo ""
    echo "❌ 编译失败，请检查错误信息"
    exit 1
fi







