# 深度数据推送日志分析

## 日志检查结果

### ✅ 正常环节

1. **OMS 订单事件发布**
   - 状态：✅ 正常
   - 日志：`[OMS] Order event sent to match engine, orderId=xxx`

2. **Match Engine 消费订单**
   - 状态：✅ 正常
   - 日志：`[OrderEventConsumer] ⬇️ Receive order event`
   - 日志：`[OrderEventConsumer] Parse order command, orderId=xxx`

3. **订单进入 OrderBook**
   - 状态：✅ 正常
   - 日志：`[OrderBook] Add order, orderId=xxx`
   - 日志：`[MatchingProcessor] Order added to OrderBook, publish depth immediately`

### ❌ 问题环节

4. **Match Engine 发布深度数据**
   - 状态：❌ **问题所在**
   - 预期日志：`[DepthPublisher] ✅ Depth published`
   - 实际：**没有看到此日志**
   - 说明：虽然代码调用了 `publishDepth()`，但没有成功发布

5. **Market Price Core 消费深度**
   - 状态：❌ 没有数据
   - 原因：Match Engine 没有发布数据

6. **Public Push Core 推送**
   - 状态：⚠️ 序列号不连续警告
   - 日志：`[Dispatcher] Sequence gap detected`

## 问题分析

### 可能原因

1. **DepthPublisher.publishDepth() 执行失败**
   - 可能异常被捕获但没有记录
   - 可能 `orderBook.getDepthData()` 返回空数据
   - 可能 Kafka 发送失败

2. **日志级别问题**
   - 原日志是 `log.debug()`，可能被过滤
   - 已改为 `log.info()` 并添加详细日志

3. **Kafka Topic 问题**
   - Topic 可能不存在或配置错误
   - Kafka 连接可能有问题

## 修复措施

### 1. 增强日志

已修改 `DepthPublisher.java`，添加详细日志：
- `[DepthPublisher] 🔥 Start publish depth` - 开始发布
- `[DepthPublisher] Depth data retrieved` - 获取深度数据
- `[DepthPublisher] Message serialized` - 序列化完成
- `[DepthPublisher] Sending to Kafka` - 发送到 Kafka
- `[DepthPublisher] ✅ Depth published successfully` - 发布成功

### 2. 空数据检查

添加了空数据检查，如果深度数据为空会记录警告。

### 3. 异常处理

确保所有异常都被记录。

## 测试步骤

### 1. 重启 Match Engine

```bash
cd match-engine-core
mvn spring-boot:run -DskipTests
```

### 2. 创建新订单

在 `trading-test.html` 中创建一个新订单。

### 3. 监控日志

```bash
# 监控深度发布
tail -f match-engine-core/logs/match-engine.log | grep -E "DepthPublisher|publishDepth|Depth published"

# 监控订单处理
tail -f match-engine-core/logs/match-engine.log | grep -E "OrderEventConsumer|MatchingProcessor"

# 监控 Market Price Core
tail -f market-price-core/logs/market-price.log | grep -E "Process depth"

# 监控 Public Push Core
tail -f public-push-core/logs/public-push.log | grep -E "broadcast.*depth"
```

### 4. 检查 Kafka Topic

```bash
# 检查是否有深度数据
kafka-console-consumer --bootstrap-server localhost:9092 --topic market.depth.BTCUSDT --from-beginning --max-messages 5
```

## 预期日志流程

创建订单后，应该看到：

```
1. [OrderEventConsumer] ⬇️ Receive order event
2. [OrderEventConsumer] Parse order command
3. [MatchingProcessor] Process event
4. [OrderBook] Add order
5. [MatchingProcessor] Order added to OrderBook, publish depth immediately
6. [DepthPublisher] 🔥 Start publish depth
7. [DepthPublisher] Depth data retrieved, bids=X, asks=Y
8. [DepthPublisher] Message serialized
9. [DepthPublisher] Sending to Kafka
10. [DepthPublisher] ✅ Depth published successfully
```

如果缺少第 6-10 步，说明 `publishDepth()` 没有被调用或执行失败。

---

*最后更新: 2026-02-22*

