#!/bin/bash

# Kafka快速启动脚本（交易所级双通道架构）
# 
# 用途：启动Kafka并创建所需的Topic

echo "=================================================="
echo "🔥 交易所级双通道Kafka架构 - 启动脚本"
echo "=================================================="

# 1. 检查Kafka是否已启动
echo ""
echo "Step 1: 检查Kafka状态..."
if ! nc -z localhost 9092 2>/dev/null; then
    echo "❌ Kafka未启动！请先启动Kafka"
    echo ""
    echo "启动命令："
    echo "  # 启动Zookeeper"
    echo "  bin/zookeeper-server-start.sh config/zookeeper.properties"
    echo ""
    echo "  # 启动Kafka"
    echo "  bin/kafka-server-start.sh config/server.properties"
    exit 1
else
    echo "✅ Kafka已启动"
fi

# 2. 创建Topic

echo ""
echo "Step 2: 创建Kafka Topics..."
echo ""

# 通道1：OMS → Match Engine（单向顺序日志）
echo "创建 order-event-BTCUSDT (通道1: OMS → Match Engine)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic order-event-BTCUSDT \
    --partitions 1 \
    --replication-factor 1 \
    --config retention.ms=86400000 \
    --config segment.ms=3600000 \
    --if-not-exists

echo "创建 order-event-ETHUSDT..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic order-event-ETHUSDT \
    --partitions 1 \
    --replication-factor 1 \
    --config retention.ms=86400000 \
    --if-not-exists

echo "创建 order-event-XRPUSDT..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic order-event-XRPUSDT \
    --partitions 1 \
    --replication-factor 1 \
    --config retention.ms=86400000 \
    --if-not-exists

echo ""

# 通道2：Match Engine → OMS（独立回传通道）
echo "创建 order-state-BTCUSDT (通道2: Match Engine → OMS)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic order-state-BTCUSDT \
    --partitions 1 \
    --replication-factor 1 \
    --config retention.ms=86400000 \
    --if-not-exists

echo "创建 order-state-ETHUSDT..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic order-state-ETHUSDT \
    --partitions 1 \
    --replication-factor 1 \
    --config retention.ms=86400000 \
    --if-not-exists

echo "创建 order-state-XRPUSDT..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic order-state-XRPUSDT \
    --partitions 1 \
    --replication-factor 1 \
    --config retention.ms=86400000 \
    --if-not-exists

echo ""

# 成交事件（全局）
echo "创建 trade-event (全局成交事件)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic trade-event \
    --partitions 3 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo ""

# 私有推送 Topic (Private Push)
echo "=================================================="
echo "创建 私有推送 Topics (Private Push)..."
echo "=================================================="
echo ""

# 订单状态推送 (OMS → Private Push Service)
echo "创建 private-order-state (订单状态推送)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic private-order-state \
    --partitions 100 \
    --replication-factor 1 \
    --config retention.ms=86400000 \
    --if-not-exists

# 账户变化推送 (Ledger → Private Push Service)
echo "创建 private-account-change (账户变化推送)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic private-account-change \
    --partitions 100 \
    --replication-factor 1 \
    --config retention.ms=86400000 \
    --if-not-exists

# 持仓变化推送 (Position → Private Push Service)
echo "创建 private-position-change (持仓变化推送)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic private-position-change \
    --partitions 100 \
    --replication-factor 1 \
    --config retention.ms=86400000 \
    --if-not-exists

echo ""

# 行情数据 Topics (Market Data Engine → Public Push)
echo "=================================================="
echo "创建 行情数据 Topics (Market Data)..."
echo "=================================================="
echo ""

# 深度数据 (Match Engine → Market Price Engine → Public Push)
echo "创建 market.depth.BTCUSDT (深度数据)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic market.depth.BTCUSDT \
    --partitions 1 \
    --replication-factor 1 \
    --config retention.ms=3600000 \
    --if-not-exists

echo "创建 market.depth.ETHUSDT..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic market.depth.ETHUSDT \
    --partitions 1 \
    --replication-factor 1 \
    --config retention.ms=3600000 \
    --if-not-exists

# 成交数据 (Market Price Engine → Public Push)
echo "创建 market.trade.BTCUSDT (实时成交)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic market.trade.BTCUSDT \
    --partitions 1 \
    --replication-factor 1 \
    --config retention.ms=3600000 \
    --if-not-exists

echo "创建 market.trade.ETHUSDT..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic market.trade.ETHUSDT \
    --partitions 1 \
    --replication-factor 1 \
    --config retention.ms=3600000 \
    --if-not-exists

# K线数据
echo "创建 market.kline.BTCUSDT.1m (K线数据)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic market.kline.BTCUSDT.1m \
    --partitions 1 \
    --replication-factor 1 \
    --config retention.ms=86400000 \
    --if-not-exists

# Ticker数据
echo "创建 market.ticker.BTCUSDT (24h统计)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic market.ticker.BTCUSDT \
    --partitions 1 \
    --replication-factor 1 \
    --config retention.ms=3600000 \
    --if-not-exists

echo ""

# 3. 列出所有Topic
echo ""
echo "Step 3: 列出所有Topics..."
kafka-topics --list --bootstrap-server localhost:9092 | grep -E "(order-event|order-state|trade-event)"

echo ""

# 4. 查看Topic详情
echo ""
echo "Step 4: Topic详情..."
echo ""

echo "order-event-BTCUSDT:"
kafka-topics --describe \
    --bootstrap-server localhost:9092 \
    --topic order-event-BTCUSDT

echo ""
echo "order-state-BTCUSDT:"
kafka-topics --describe \
    --bootstrap-server localhost:9092 \
    --topic order-state-BTCUSDT

echo ""
echo "trade-event:"
kafka-topics --describe \
    --bootstrap-server localhost:9092 \
    --topic trade-event

echo ""
echo "=================================================="
echo "✅ Kafka Topics创建完成！"
echo "=================================================="
echo ""
echo "架构说明："
echo "  通道1 (OMS → Match Engine)："
echo "    - order-event-BTCUSDT (单分区)"
echo "    - order-event-ETHUSDT (单分区)"
echo "    - order-event-XRPUSDT (单分区)"
echo ""
echo "  通道2 (Match Engine → OMS)："
echo "    - order-state-BTCUSDT (单分区)"
echo "    - order-state-ETHUSDT (单分区)"
echo "    - order-state-XRPUSDT (单分区)"
echo ""
echo "  成交事件 (Match Engine → 全系统)："
echo "    - trade-event (3分区)"
echo ""
echo "  私有推送 (Private Push)："
echo "    - private-order-state (100分区，按 userId 分区)"
echo "    - private-account-change (100分区，按 userId 分区)"
echo "    - private-position-change (100分区，按 userId 分区)"
echo ""
echo "  行情数据 (Market Data)："
echo "    - market.depth.BTCUSDT (深度数据)"
echo "    - market.trade.BTCUSDT (实时成交)"
echo "    - market.kline.BTCUSDT.1m (K线数据)"
echo "    - market.ticker.BTCUSDT (24h统计)"
echo ""
echo "下一步："
echo "  1. 启动OMS: cd oms-core && mvn spring-boot:run"
echo "  2. 启动Match Engine: cd match-engine-core && mvn spring-boot:run"
echo "  3. 启动Private Push: cd private-push-core && mvn spring-boot:run"
echo "  4. 测试下单流程"
echo ""



