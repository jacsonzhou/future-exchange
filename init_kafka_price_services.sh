#!/bin/bash
# Kafka Topic 初始化脚本 - 价格服务相关

KAFKA_HOME=${KAFKA_HOME:-/usr/local/kafka}
BROKER_LIST=${KAFKA_BROKER:-localhost:9092}

echo "Creating Kafka topics for Price Services..."

# 价格服务相关 Topics
echo "Creating price service topics..."

# index-price-update: 指数价格更新
$KAFKA_HOME/bin/kafka-topics.sh --create \
    --topic index-price-update \
    --partitions 16 \
    --replication-factor 1 \
    --bootstrap-server $BROKER_LIST 2>/dev/null || true

# mark-price-update: 标记价格更新
$KAFKA_HOME/bin/kafka-topics.sh --create \
    --topic mark-price-update \
    --partitions 16 \
    --replication-factor 1 \
    --bootstrap-server $BROKER_LIST 2>/dev/null || true

# 行情服务相关 Topics
echo "Creating market data topics..."

# kline-topic: K线数据
$KAFKA_HOME/bin/kafka-topics.sh --create \
    --topic kline-topic \
    --partitions 16 \
    --replication-factor 1 \
    --bootstrap-server $BROKER_LIST 2>/dev/null || true

# ticker-topic: 24小时统计
$KAFKA_HOME/bin/kafka-topics.sh --create \
    --topic ticker-topic \
    --partitions 16 \
    --replication-factor 1 \
    --bootstrap-server $BROKER_LIST 2>/dev/null || true

# orderbook-snapshot-topic: OrderBook深度
$KAFKA_HOME/bin/kafka-topics.sh --create \
    --topic orderbook-snapshot-topic \
    --partitions 16 \
    --replication-factor 1 \
    --bootstrap-server $BROKER_LIST 2>/dev/null || true

# WebSocket 推送相关 Topics
echo "Creating WebSocket topics..."

# ws-public-topic: 公有推送数据
$KAFKA_HOME/bin/kafka-topics.sh --create \
    --topic ws-public-topic \
    --partitions 16 \
    --replication-factor 1 \
    --bootstrap-server $BROKER_LIST 2>/dev/null || true

# ws-private-topic: 私有推送数据
$KAFKA_HOME/bin/kafka-topics.sh --create \
    --topic ws-private-topic \
    --partitions 64 \
    --replication-factor 1 \
    --bootstrap-server $BROKER_LIST 2>/dev/null || true

# notification-topic: 通知消息
$KAFKA_HOME/bin/kafka-topics.sh --create \
    --topic notification-topic \
    --partitions 32 \
    --replication-factor 1 \
    --bootstrap-server $BROKER_LIST 2>/dev/null || true

echo "All topics created successfully!"
echo ""
echo "Topic list:"
$KAFKA_HOME/bin/kafka-topics.sh --list --bootstrap-server $BROKER_LIST | grep -E "(index-price|mark-price|kline|ticker|orderbook|ws-|notification)"
