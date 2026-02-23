# Kafka Topic 配置说明 - 新增模块

## 资金费率结算模块 Topics

### funding-rate-calc
- **用途**: 资金费率计算完成事件
- **生产者**: funding-rate-core
- **消费者**: oms-core, position-snapshot-core
- **分区**: 按 symbol 分区
- **保留**: 7天

### funding-settlement
- **用途**: 资金费用结算事件
- **生产者**: funding-rate-core
- **消费者**: ledger-core, snapshot-account-core
- **分区**: 按 user_id % 64 分区
- **保留**: 30天

### index-price-update
- **用途**: 指数价格更新
- **生产者**: index-price-core
- **消费者**: funding-rate-core, tp-sl-core, hard-risk-core
- **分区**: 按 symbol 分区
- **保留**: 1天

---

## 止盈止损模块 Topics

### mark-price-update
- **用途**: 标记价格更新（用于TP/SL触发判断）
- **生产者**: mark-price-service
- **消费者**: tp-sl-core
- **分区**: 按 symbol 分区
- **保留**: 1天

### position-closed
- **用途**: 持仓平仓事件
- **生产者**: ledger-core
- **消费者**: tp-sl-core
- **分区**: 按 user_id % 64 分区
- **保留**: 7天

### tp-sl-triggered
- **用途**: TP/SL触发事件
- **生产者**: tp-sl-core
- **消费者**: oms-core
- **分区**: 按 user_id % 64 分区
- **保留**: 7天

### tp-sl-cancelled
- **用途**: TP/SL撤销事件
- **生产者**: tp-sl-core
- **消费者**: 各模块
- **分区**: 按 user_id % 64 分区
- **保留**: 7天

---

## 保证金模式模块 Topics

### margin-mode-changed
- **用途**: 保证金模式切换事件
- **生产者**: margin-mode-core
- **消费者**: hard-risk-core, position-snapshot-core
- **分区**: 按 user_id % 64 分区
- **保留**: 7天

### leverage-changed
- **用途**: 杠杆倍数修改事件
- **生产者**: margin-mode-core
- **消费者**: hard-risk-core
- **分区**: 按 user_id % 64 分区
- **保留**: 7天

### margin-added
- **用途**: 追加保证金事件
- **生产者**: margin-mode-core
- **消费者**: ledger-core, snapshot-account-core
- **分区**: 按 user_id % 64 分区
- **保留**: 30天

---

## ADL自动减仓模块 Topics

### liquidation-completed
- **用途**: 强平完成事件
- **生产者**: liquidation-core
- **消费者**: adl-core
- **分区**: 按 symbol 分区
- **保留**: 30天

### adl-triggered
- **用途**: ADL触发事件
- **生产者**: adl-core
- **消费者**: oms-core, ledger-core, notification-core
- **分区**: 按 symbol 分区
- **保留**: 30天

### adl-executed
- **用途**: ADL执行完成事件
- **生产者**: adl-core
- **消费者**: ledger-core, position-snapshot-core
- **分区**: 按 user_id % 64 分区
- **保留**: 30天

### insurance-fund-changed
- **用途**: 保险基金变动事件
- **生产者**: adl-core
- **消费者**: 风控监控
- **分区**: 单分区
- **保留**: 90天

---

## 做市商模块 Topics

### mm-batch-order
- **用途**: 做市商批量订单
- **生产者**: market-maker-core
- **消费者**: oms-core
- **分区**: 按 user_id % 64 分区
- **保留**: 7天

### mm-fee-rebate
- **用途**: 做市商费率返佣事件
- **生产者**: ledger-core
- **消费者**: market-maker-core
- **分区**: 按 user_id % 64 分区
- **保留**: 30天

### mm-performance-update
- **用途**: 做市商考核指标更新
- **生产者**: market-maker-core
- **消费者**: 监控系统
- **分区**: 按 user_id % 64 分区
- **保留**: 30天

---

## Topic 创建脚本

```bash
#!/bin/bash

KAFKA_HOME=/usr/local/kafka
BROKER_LIST=localhost:9092

# 资金费率模块
echo "Creating funding rate topics..."
$KAFKA_HOME/bin/kafka-topics.sh --create --topic funding-rate-calc --partitions 16 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true
$KAFKA_HOME/bin/kafka-topics.sh --create --topic funding-settlement --partitions 64 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true
$KAFKA_HOME/bin/kafka-topics.sh --create --topic index-price-update --partitions 16 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true

# 止盈止损模块
echo "Creating TP/SL topics..."
$KAFKA_HOME/bin/kafka-topics.sh --create --topic mark-price-update --partitions 16 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true
$KAFKA_HOME/bin/kafka-topics.sh --create --topic position-closed --partitions 64 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true
$KAFKA_HOME/bin/kafka-topics.sh --create --topic tp-sl-triggered --partitions 64 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true
$KAFKA_HOME/bin/kafka-topics.sh --create --topic tp-sl-cancelled --partitions 64 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true

# 保证金模式模块
echo "Creating margin mode topics..."
$KAFKA_HOME/bin/kafka-topics.sh --create --topic margin-mode-changed --partitions 64 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true
$KAFKA_HOME/bin/kafka-topics.sh --create --topic leverage-changed --partitions 64 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true
$KAFKA_HOME/bin/kafka-topics.sh --create --topic margin-added --partitions 64 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true

# ADL模块
echo "Creating ADL topics..."
$KAFKA_HOME/bin/kafka-topics.sh --create --topic liquidation-completed --partitions 16 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true
$KAFKA_HOME/bin/kafka-topics.sh --create --topic adl-triggered --partitions 16 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true
$KAFKA_HOME/bin/kafka-topics.sh --create --topic adl-executed --partitions 64 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true
$KAFKA_HOME/bin/kafka-topics.sh --create --topic insurance-fund-changed --partitions 1 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true

# 做市商模块
echo "Creating market maker topics..."
$KAFKA_HOME/bin/kafka-topics.sh --create --topic mm-batch-order --partitions 64 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true
$KAFKA_HOME/bin/kafka-topics.sh --create --topic mm-fee-rebate --partitions 64 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true
$KAFKA_HOME/bin/kafka-topics.sh --create --topic mm-performance-update --partitions 64 --replication-factor 3 --bootstrap-server $BROKER_LIST 2>/dev/null || true

echo "All topics created successfully!"
```
