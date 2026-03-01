#!/bin/bash
# =====================================================
# Kafka Topic 初始化脚本 - 新增P0模块
# =====================================================

KAFKA_HOME=${KAFKA_HOME:-/usr/local/kafka}
BROKER_LIST=${KAFKA_BROKER:-localhost:9092}

echo "=============================================="
echo "Initializing Kafka Topics for New P0 Modules"
echo "Broker: $BROKER_LIST"
echo "=============================================="

# 检查kafka是否可用
if ! $KAFKA_HOME/bin/kafka-broker-api-versions.sh --bootstrap-server $BROKER_LIST > /dev/null 2>&1; then
    echo "Error: Cannot connect to Kafka at $BROKER_LIST"
    echo "Please check KAFKA_HOME and KAFKA_BROKER environment variables"
    exit 1
fi

# 资金费率模块
echo ""
echo "[1/5] Creating Funding Rate topics..."
$KAFKA_HOME/bin/kafka-topics.sh --create --topic funding-rate-calc --partitions 16 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic funding-rate-calc already exists"
$KAFKA_HOME/bin/kafka-topics.sh --create --topic funding-settlement --partitions 64 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic funding-settlement already exists"
$KAFKA_HOME/bin/kafka-topics.sh --create --topic index-price-update --partitions 16 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic index-price-update already exists"

# 止盈止损模块
echo ""
echo "[2/5] Creating TP/SL topics..."
$KAFKA_HOME/bin/kafka-topics.sh --create --topic mark-price-update --partitions 16 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic mark-price-update already exists"
$KAFKA_HOME/bin/kafka-topics.sh --create --topic position-closed --partitions 64 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic position-closed already exists"
$KAFKA_HOME/bin/kafka-topics.sh --create --topic tp-sl-triggered --partitions 64 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic tp-sl-triggered already exists"
$KAFKA_HOME/bin/kafka-topics.sh --create --topic tp-sl-cancelled --partitions 64 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic tp-sl-cancelled already exists"

# 保证金模式模块
echo ""
echo "[3/5] Creating Margin Mode topics..."
$KAFKA_HOME/bin/kafka-topics.sh --create --topic margin-mode-changed --partitions 64 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic margin-mode-changed already exists"
$KAFKA_HOME/bin/kafka-topics.sh --create --topic leverage-changed --partitions 64 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic leverage-changed already exists"
$KAFKA_HOME/bin/kafka-topics.sh --create --topic margin-added --partitions 64 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic margin-added already exists"

# ADL模块
echo ""
echo "[4/5] Creating ADL topics..."
$KAFKA_HOME/bin/kafka-topics.sh --create --topic liquidation-completed --partitions 16 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic liquidation-completed already exists"
$KAFKA_HOME/bin/kafka-topics.sh --create --topic adl-triggered --partitions 16 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic adl-triggered already exists"
$KAFKA_HOME/bin/kafka-topics.sh --create --topic adl-executed --partitions 64 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic adl-executed already exists"
$KAFKA_HOME/bin/kafka-topics.sh --create --topic insurance-fund-changed --partitions 1 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic insurance-fund-changed already exists"

# 做市商模块
echo ""
echo "[5/6] Creating Market Maker topics..."
$KAFKA_HOME/bin/kafka-topics.sh --create --topic mm-batch-order --partitions 64 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic mm-batch-order already exists"
$KAFKA_HOME/bin/kafka-topics.sh --create --topic mm-fee-rebate --partitions 64 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic mm-fee-rebate already exists"
$KAFKA_HOME/bin/kafka-topics.sh --create --topic mm-performance-update --partitions 64 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic mm-performance-update already exists"

# CFD 模式模块
echo ""
echo "[6/6] Creating CFD topics..."
$KAFKA_HOME/bin/kafka-topics.sh --create --topic cfd-order-command-BTCUSDT --partitions 1 --replication-factor 1 --bootstrap-server $BROKER_LIST 2>/dev/null || echo "  Topic cfd-order-command-BTCUSDT already exists"

echo ""
echo "=============================================="
echo "All topics created successfully!"
echo "=============================================="

# 列出所有新创建的topic
echo ""
echo "New Topics List:"
$KAFKA_HOME/bin/kafka-topics.sh --list --bootstrap-server $BROKER_LIST | grep -E "funding|mark-price|tp-sl|margin|adl|insurance|mm-|cfd-order-command"
