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

echo "创建 cfd-order-command-BTCUSDT (CFD Dealer 指令通道)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic cfd-order-command-BTCUSDT \
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

echo "创建 trade-event-BTCUSDT (按symbol成交事件)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic trade-event-BTCUSDT \
    --partitions 3 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo ""

# Ledger 分录事件（Ledger → Snapshot/Position）
echo "创建 trade-entry-BTCUSDT (Ledger分录-交易)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic trade-entry-BTCUSDT \
    --partitions 1 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo "创建 trade-entry-ETHUSDT..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic trade-entry-ETHUSDT \
    --partitions 1 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo "创建 account-entry-SYSTEM (Ledger分录-SYSTEM账务)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic account-entry-SYSTEM \
    --partitions 1 \
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

# 风险/强平/ADL Topics
echo "=================================================="
echo "创建 风险/强平/ADL Topics..."
echo "=================================================="
echo ""

echo "创建 liquidation-trigger-topic (保证金服务 -> 强平服务)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic liquidation-trigger-topic \
    --partitions 3 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo "创建 liquidation-completed-topic (强平服务 -> ADL)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic liquidation-completed-topic \
    --partitions 3 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo "创建 adl-trigger-topic (ADL触发事件)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic adl-trigger-topic \
    --partitions 3 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo "创建 adl-executed-topic (ADL执行结果)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic adl-executed-topic \
    --partitions 3 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo "创建 index-price-update (指数价格更新)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic index-price-update \
    --partitions 3 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo "创建 mark-price-update (标记价格更新)..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic mark-price-update \
    --partitions 3 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
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

# 外部行情通道 Topics（Binance Data Source -> Public Push / Market Price）
echo "=================================================="
echo "创建 外部行情通道 Topics (market.ext.binance.*)..."
echo "=================================================="
echo ""

EXT_SYMBOLS=(BTCUSDT ETHUSDT BNBUSDT SOLUSDT XRPUSDT)
EXT_INTERVALS=(1m 3m 5m 15m 30m 1h 2h 4h 6h 8h 12h 1d 3d 1w 1M)

for symbol in "${EXT_SYMBOLS[@]}"; do
  echo "创建 market.ext.binance.depth.${symbol} ..."
  kafka-topics --create \
      --bootstrap-server localhost:9092 \
      --topic "market.ext.binance.depth.${symbol}" \
      --partitions 1 \
      --replication-factor 1 \
      --config retention.ms=3600000 \
      --if-not-exists

  echo "创建 market.ext.binance.trade.${symbol} ..."
  kafka-topics --create \
      --bootstrap-server localhost:9092 \
      --topic "market.ext.binance.trade.${symbol}" \
      --partitions 1 \
      --replication-factor 1 \
      --config retention.ms=3600000 \
      --if-not-exists

  echo "创建 market.ext.binance.ticker.${symbol} ..."
  kafka-topics --create \
      --bootstrap-server localhost:9092 \
      --topic "market.ext.binance.ticker.${symbol}" \
      --partitions 1 \
      --replication-factor 1 \
      --config retention.ms=3600000 \
      --if-not-exists

  for interval in "${EXT_INTERVALS[@]}"; do
    kafka-topics --create \
        --bootstrap-server localhost:9092 \
        --topic "market.ext.binance.kline.${symbol}.${interval}" \
        --partitions 1 \
        --replication-factor 1 \
        --config retention.ms=86400000 \
        --if-not-exists
  done
done

echo ""

# Agent Training Topics（训练域：与真实下单链路隔离）
echo "=================================================="
echo "创建 Agent Training Topics..."
echo "=================================================="
echo ""

# 全局Topic
echo "创建 agent.decision.created ..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic agent.decision.created \
    --partitions 6 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo "创建 agent.decision.validated ..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic agent.decision.validated \
    --partitions 6 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo "创建 agent.replay.generated ..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic agent.replay.generated \
    --partitions 6 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo "创建 agent.score.updated ..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic agent.score.updated \
    --partitions 12 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo "创建 agent.growth.task.updated ..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic agent.growth.task.updated \
    --partitions 12 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo "创建 agent.arena.session.event ..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic agent.arena.session.event \
    --partitions 6 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo "创建 agent.skill.version.event ..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic agent.skill.version.event \
    --partitions 6 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

# 按symbol隔离的模拟执行Topic
TRAINING_SYMBOLS=(BTCUSDT ETHUSDT BNBUSDT SOLUSDT XRPUSDT)

for symbol in "${TRAINING_SYMBOLS[@]}"; do
  echo "创建 agent.paper.order.command.${symbol} ..."
  kafka-topics --create \
      --bootstrap-server localhost:9092 \
      --topic "agent.paper.order.command.${symbol}" \
      --partitions 1 \
      --replication-factor 1 \
      --config retention.ms=259200000 \
      --if-not-exists

  echo "创建 agent.paper.order.state.${symbol} ..."
  kafka-topics --create \
      --bootstrap-server localhost:9092 \
      --topic "agent.paper.order.state.${symbol}" \
      --partitions 1 \
      --replication-factor 1 \
      --config retention.ms=259200000 \
      --if-not-exists

  echo "创建 agent.paper.fill.${symbol} ..."
  kafka-topics --create \
      --bootstrap-server localhost:9092 \
      --topic "agent.paper.fill.${symbol}" \
      --partitions 1 \
      --replication-factor 1 \
      --config retention.ms=259200000 \
      --if-not-exists
done

echo ""

# DLQ Topics（消费者异常兜底）
echo "=================================================="
echo "创建 DLQ Topics..."
echo "=================================================="
echo ""

echo "创建 mark-price-update-dlq..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic mark-price-update-dlq \
    --partitions 3 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo "创建 index-price-update-dlq..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic index-price-update-dlq \
    --partitions 3 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo "创建 private-position-change-dlq..."
kafka-topics --create \
    --bootstrap-server localhost:9092 \
    --topic private-position-change-dlq \
    --partitions 12 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --if-not-exists

echo ""

# 3. 列出所有Topic
echo ""
echo "Step 3: 列出所有Topics..."
kafka-topics --list --bootstrap-server localhost:9092 | grep -E "(order-event|cfd-order-command|order-state|trade-event|trade-entry|account-entry|agent\\.)"

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
echo "trade-entry-BTCUSDT:"
kafka-topics --describe \
    --bootstrap-server localhost:9092 \
    --topic trade-entry-BTCUSDT

echo ""
echo "account-entry-SYSTEM:"
kafka-topics --describe \
    --bootstrap-server localhost:9092 \
    --topic account-entry-SYSTEM

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
echo "    - cfd-order-command-BTCUSDT (CFD Dealer 指令)"
echo ""
echo "  通道2 (Match Engine → OMS)："
echo "    - order-state-BTCUSDT (单分区)"
echo "    - order-state-ETHUSDT (单分区)"
echo "    - order-state-XRPUSDT (单分区)"
echo ""
echo "  成交事件 (Match Engine → 全系统)："
echo "    - trade-event (3分区)"
echo ""
echo "  Ledger分录事件 (Ledger → Snapshot/Position)："
echo "    - trade-entry-BTCUSDT (交易分录)"
echo "    - trade-entry-ETHUSDT (交易分录)"
echo "    - account-entry-SYSTEM (SYSTEM账务分录)"
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
echo "  Agent Training (训练域)："
echo "    - agent.decision.created / agent.decision.validated"
echo "    - agent.paper.order.command.{symbol}"
echo "    - agent.paper.order.state.{symbol}"
echo "    - agent.paper.fill.{symbol}"
echo "    - agent.replay.generated / agent.score.updated"
echo "    - agent.growth.task.updated / agent.arena.session.event"
echo "    - agent.skill.version.event"
echo ""
echo "下一步："
echo "  1. 启动OMS: cd oms-core && mvn spring-boot:run"
echo "  2. 启动Match Engine: cd match-engine-core && mvn spring-boot:run"
echo "  3. 启动Private Push: cd private-push-core && mvn spring-boot:run"
echo "  4. 测试下单流程"
echo ""
