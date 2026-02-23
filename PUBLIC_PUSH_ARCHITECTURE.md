# 公有推送系统架构设计文档

> **版本**: v1.0.0  
> **状态**: Production Grade  
> **最后更新**: 2026-02-18  
> **作者**: Exchange Architecture Team

---

## 一、架构总览

### 1.1 分层架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              客户端层 (Clients)                              │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐            │
│  │ 量化程序 │  │ Web App │  │ Mobile  │  │ 第三方  │  │  其他   │            │
│  │         │  │         │  │   App   │  │ 行情商  │  │         │            │
│  └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘            │
│       │            │            │            │            │                 │
│       └────────────┴────────────┴────────────┴────────────┘                 │
│                              WSS (TLS)                                      │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        公有推送系统 (Public Push System)                      │
│                            端口: 8096                                       │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     WebSocket Gateway Layer                         │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐│   │
│  │  │   Node 1    │  │   Node 2    │  │   Node 3    │  │   Node N    ││   │
│  │  │  (无状态)    │  │  (无状态)    │  │  (无状态)    │  │  (无状态)    ││   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘│   │
│  │                              │                                      │   │
│  │                         负载均衡 (Nginx/HAProxy)                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                      │                                      │
│  ┌───────────────────────────────────┼───────────────────────────────────┐ │
│  │                         Kafka Consumer Layer                          │ │
│  │                                                                      │ │
│  │   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │ │
│  │   │ Trade Consumer│  │Depth Consumer│  │Kline Consumer│              │ │
│  │   │  (per symbol) │  │ (per symbol) │  │ (per symbol) │              │ │
│  │   └──────────────┘  └──────────────┘  └──────────────┘              │ │
│  │                                                                      │ │
│  │   Topics: market.trade.*, market.depth.*, market.kline.*            │ │
│  └─────────────────────────────────────────────────────────────────────┘ │
│                                      │                                      │
│  ┌───────────────────────────────────┼───────────────────────────────────┐ │
│  │                        Subscription Manager                           │ │
│  │                                                                      │ │
│  │   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │ │
│  │   │  Channel    │  │   Router    │  │   Rate      │                 │ │
│  │   │  Registry   │→ │             │→ │  Limiter    │                 │ │
│  │   └─────────────┘  └─────────────┘  └─────────────┘                 │ │
│  │                                                                      │ │
│  │   频道 → Session 映射表 (ConcurrentHashMap)                          │ │
│  └─────────────────────────────────────────────────────────────────────┘ │
│                                      │                                      │
│  ┌───────────────────────────────────┼───────────────────────────────────┐ │
│  │                         Batch & Push Engine                           │ │
│  │                                                                      │ │
│  │   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │ │
│  │   │   Buffer    │  │   Flusher   │  │   Sender    │                 │ │
│  │   │   Queue     │→ │ (10-100ms)  │→ │  (Netty)    │                 │ │
│  │   └─────────────┘  └─────────────┘  └─────────────┘                 │ │
│  └─────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
                                      ▲
                                      │
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Kafka (market-events)                               │
│                                                                             │
│   market.trade.BTCUSDT     market.depth.BTCUSDT     market.kline.1m.BTCUSDT │
│   market.trade.ETHUSDT     market.depth.ETHUSDT     market.kline.1m.ETHUSDT │
│   ...                      ...                      ...                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                      ▲
                                      │
┌─────────────────────────────────────────────────────────────────────────────┐
│                      行情生成服务 (Market Data Engine)                        │
│                            端口: 8095                                       │
│                                                                             │
│   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐       │
│   │  Trade      │  │  OrderBook  │  │   Kline     │  │   Ticker    │       │
│   │  Engine     │  │   Engine    │  │   Engine    │  │   Engine    │       │
│   └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘       │
│                                                                             │
│   Input: match-event-topic (from Match Engine)                              │
│   Output: market-events (to Kafka)                                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 核心设计原则

| 原则 | 说明 | 实现方式 |
|------|------|---------|
| **计算与推送分离** | 行情计算和推送必须是两个独立服务 | 独立进程，Kafka解耦 |
| **无状态设计** | 推送节点无状态，可任意扩容 | Session本地存储，不依赖共享状态 |
| **批量聚合** | 高频行情批量发送，降低网络开销 | 10-100ms批量窗口 |
| **顺序保证** | 每个symbol数据严格顺序 | Kafka单分区消费 |
| **故障隔离** | 单节点故障不影响其他节点 | 独立进程，快速重启 |

---

## 二、模块详细设计

### 2.1 WebSocket Gateway Layer

#### 2.1.1 连接管理

```java
/**
 * WebSocket连接管理器
 * 
 * 职责：
 * - 管理所有客户端连接
 * - 心跳检测与僵尸连接清理
 * - 连接统计与监控
 * 
 * 设计：
 * - 每个连接分配唯一sessionId
 * - 使用ConcurrentHashMap存储连接
 * - 定时任务清理超时连接
 */
@Component
public class ConnectionManager {
    
    // 所有活跃连接 sessionId -> WebSocketSession
    private final ConcurrentHashMap<String, WebSocketSession> connections;
    
    // 连接元数据 sessionId -> ConnectionMetadata
    private final ConcurrentHashMap<String, ConnectionMetadata> metadata;
    
    // IP -> 连接数计数器 (用于IP限流)
    private final ConcurrentHashMap<String, AtomicInteger> ipConnectionCounter;
    
    // 心跳配置
    private static final long HEARTBEAT_INTERVAL_MS = 30000;
    private static final long HEARTBEAT_TIMEOUT_MS = 5000;
    
    /**
     * 连接建立
     */
    public boolean onConnectionEstablished(WebSocketSession session) {
        String ip = getClientIp(session);
        
        // IP限流检查
        if (!checkIpLimit(ip)) {
            log.warn("[Connection] IP {} exceeded connection limit", ip);
            return false;
        }
        
        String sessionId = generateSessionId();
        session.getAttributes().put("sessionId", sessionId);
        session.getAttributes().put("connectTime", System.currentTimeMillis());
        session.getAttributes().put("clientIp", ip);
        
        connections.put(sessionId, session);
        ipConnectionCounter.get(ip).incrementAndGet();
        
        // 启动心跳检测
        scheduleHeartbeatCheck(sessionId);
        
        return true;
    }
    
    /**
     * 连接关闭
     */
    public void onConnectionClosed(String sessionId) {
        WebSocketSession session = connections.remove(sessionId);
        if (session != null) {
            String ip = (String) session.getAttributes().get("clientIp");
            ipConnectionCounter.get(ip).decrementAndGet();
            
            // 清理订阅
            subscriptionManager.unsubscribeAll(sessionId);
        }
    }
    
    /**
     * 心跳检测
     */
    public void onPing(String sessionId, long timestamp) {
        ConnectionMetadata meta = metadata.get(sessionId);
        if (meta != null) {
            meta.setLastPingTime(timestamp);
            meta.setLastPongTime(System.currentTimeMillis());
        }
    }
    
    /**
     * 僵尸连接清理（定时任务）
     */
    @Scheduled(fixedRate = 30000)
    public void cleanupZombieConnections() {
        long now = System.currentTimeMillis();
        
        metadata.forEach((sessionId, meta) -> {
            if (now - meta.getLastPongTime() > HEARTBEAT_INTERVAL_MS + HEARTBEAT_TIMEOUT_MS) {
                log.warn("[Connection] Zombie connection detected: {}", sessionId);
                closeConnection(sessionId, CloseStatus.SESSION_NOT_RELIABLE);
            }
        });
    }
}
```

#### 2.1.2 消息发送优化

```java
/**
 * 高性能消息发送器
 * 
 * 优化点：
 * 1. 批量合并（减少系统调用）
 * 2. 零拷贝（DirectBuffer）
 * 3. 背压控制（防止OOM）
 */
@Component
public class MessageSender {
    
    // 批量发送队列
    private final Map<String, List<TextMessage>> batchBuffers;
    
    // 批量发送间隔（ms）
    private static final long FLUSH_INTERVAL_MS = 50;
    
    // 单批次最大消息数
    private static final int BATCH_SIZE = 100;
    
    /**
     * 发送消息（批量优化）
     */
    public void sendMessage(String sessionId, Object data) {
        String json = JSON.toJSONString(data);
        TextMessage message = new TextMessage(json);
        
        // 高优先级消息（如心跳响应）立即发送
        if (isHighPriority(data)) {
            flushImmediately(sessionId, message);
            return;
        }
        
        // 普通消息加入批量队列
        batchBuffers.computeIfAbsent(sessionId, k -> new ArrayList<>())
                    .add(message);
        
        // 检查是否达到批量阈值
        if (batchBuffers.get(sessionId).size() >= BATCH_SIZE) {
            flushBatch(sessionId);
        }
    }
    
    /**
     * 定时刷新批量队列
     */
    @Scheduled(fixedRate = FLUSH_INTERVAL_MS)
    public void scheduledFlush() {
        batchBuffers.forEach((sessionId, buffer) -> {
            if (!buffer.isEmpty()) {
                flushBatch(sessionId);
            }
        });
    }
    
    /**
     * 广播消息（优化：只序列化一次）
     */
    public void broadcast(String channel, Object data) {
        // 预先序列化，避免每个session重复序列化
        String json = JSON.toJSONString(data);
        TextMessage message = new TextMessage(json);
        
        Set<String> sessionIds = subscriptionManager.getSubscribers(channel);
        
        for (String sessionId : sessionIds) {
            WebSocketSession session = connectionManager.getSession(sessionId);
            if (session != null && session.isOpen()) {
                try {
                    session.sendMessage(message);
                } catch (IOException e) {
                    log.error("[Sender] Failed to send to {}", sessionId);
                }
            }
        }
    }
}
```

### 2.2 Subscription Manager

#### 2.2.1 订阅管理

```java
/**
 * 订阅管理器
 * 
 * 核心数据结构：
 * 1. channel -> Set<sessionId>  (正向索引，用于广播)
 * 2. sessionId -> Set<channel>  (反向索引，用于快速取消订阅)
 * 
 * 并发策略：
 * - 使用ConcurrentHashMap + CopyOnWriteArraySet
 * - 读写分离，读多写少场景优化
 */
@Component
public class SubscriptionManager {
    
    // 频道 -> 订阅该频道的sessions (正向索引)
    private final ConcurrentHashMap<String, Set<String>> channelSubscribers;
    
    // session -> 该session订阅的频道 (反向索引)
    private final ConcurrentHashMap<String, Set<String>> sessionChannels;
    
    // 频道元数据 (限流配置等)
    private final ConcurrentHashMap<String, ChannelMetadata> channelMetadata;
    
    // 限制配置
    private static final int MAX_SUBSCRIPTIONS_PER_SESSION = 1024;
    
    /**
     * 订阅频道
     */
    public SubscribeResult subscribe(String sessionId, String channel) {
        // 检查session是否存在
        if (!connectionManager.exists(sessionId)) {
            return SubscribeResult.error("Session not found");
        }
        
        // 检查订阅数限制
        Set<String> currentSubs = sessionChannels.get(sessionId);
        if (currentSubs != null && currentSubs.size() >= MAX_SUBSCRIPTIONS_PER_SESSION) {
            return SubscribeResult.error("Subscription limit exceeded: " + MAX_SUBSCRIPTIONS_PER_SESSION);
        }
        
        // 解析频道类型
        ChannelType channelType = parseChannelType(channel);
        
        // 添加到索引
        channelSubscribers.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet())
                          .add(sessionId);
        sessionChannels.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
                       .add(channel);
        
        // 通知Kafka消费者启动该频道
        kafkaConsumerManager.ensureConsumerStarted(channel);
        
        log.info("[Subscription] {} subscribed to {}", sessionId, channel);
        
        return SubscribeResult.success();
    }
    
    /**
     * 取消订阅
     */
    public void unsubscribe(String sessionId, String channel) {
        Set<String> subs = channelSubscribers.get(channel);
        if (subs != null) {
            subs.remove(sessionId);
            
            // 如果没有订阅者，停止Kafka消费者
            if (subs.isEmpty()) {
                kafkaConsumerManager.stopConsumer(channel);
            }
        }
        
        Set<String> channels = sessionChannels.get(sessionId);
        if (channels != null) {
            channels.remove(channel);
        }
    }
    
    /**
     * 取消所有订阅（连接关闭时）
     */
    public void unsubscribeAll(String sessionId) {
        Set<String> channels = sessionChannels.remove(sessionId);
        if (channels != null) {
            for (String channel : channels) {
                Set<String> subs = channelSubscribers.get(channel);
                if (subs != null) {
                    subs.remove(sessionId);
                }
            }
        }
    }
    
    /**
     * 获取频道的所有订阅者
     */
    public Set<String> getSubscribers(String channel) {
        Set<String> subs = channelSubscribers.get(channel);
        return subs != null ? Set.copyOf(subs) : Set.of();
    }
}
```

### 2.3 Kafka Consumer Layer

#### 2.3.1 动态消费者管理

```java
/**
 * Kafka动态消费者管理器
 * 
 * 设计：
 * - 按需启动消费者（有订阅时才消费）
 * - 每个symbol独立消费者组
 * - 自动负载均衡
 */
@Component
public class KafkaConsumerManager {
    
    private final KafkaListenerEndpointRegistry registry;
    private final PublicPushProperties properties;
    
    // 运行中的消费者
    private final ConcurrentHashMap<String, MessageListenerContainer> activeConsumers;
    
    // 消费者工厂
    private final ConcurrentKafkaListenerContainerFactory<String, String> factory;
    
    /**
     * 确保频道消费者已启动
     */
    public synchronized void ensureConsumerStarted(String channel) {
        if (activeConsumers.containsKey(channel)) {
            return; // 已在运行
        }
        
        // 解析topic
        String topic = channelToTopic(channel);
        String groupId = buildGroupId(channel);
        
        // 创建消费者容器
        ContainerProperties containerProps = new ContainerProperties(topic);
        containerProps.setGroupId(groupId);
        containerProps.setMessageListener(new ChannelMessageListener(channel));
        
        MessageListenerContainer container = factory.createContainer(containerProps);
        container.start();
        
        activeConsumers.put(channel, container);
        
        log.info("[KafkaConsumer] Started consumer for channel: {} -> topic: {}", channel, topic);
    }
    
    /**
     * 停止频道消费者
     */
    public synchronized void stopConsumer(String channel) {
        MessageListenerContainer container = activeConsumers.remove(channel);
        if (container != null) {
            container.stop();
            log.info("[KafkaConsumer] Stopped consumer for channel: {}", channel);
        }
    }
    
    /**
     * 频道转Topic
     */
    private String channelToTopic(String channel) {
        // trade.BTCUSDT -> market.trade.BTCUSDT
        // depth.BTCUSDT@100ms -> market.depth.BTCUSDT
        // kline.1m.BTCUSDT -> market.kline.1m.BTCUSDT
        
        if (channel.startsWith("trade.")) {
            return "market." + channel;
        } else if (channel.startsWith("depth.")) {
            String symbol = channel.replace("depth.", "").replace("@100ms", "");
            return "market.depth." + symbol;
        } else if (channel.startsWith("kline.")) {
            return "market." + channel;
        } else if (channel.startsWith("ticker.")) {
            return "market." + channel;
        }
        return "market." + channel;
    }
}

/**
 * 频道消息监听器
 */
public class ChannelMessageListener implements AcknowledgingMessageListener<String, String> {
    
    private final String channel;
    private final SubscriptionManager subscriptionManager;
    private final MessageSender messageSender;
    
    @Override
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            // 解析消息
            JSONObject message = JSON.parseObject(record.value());
            
            // 包装成WebSocket消息格式
            JSONObject wrapper = new JSONObject();
            wrapper.put("stream", channel);
            wrapper.put("data", message);
            
            // 广播给所有订阅者
            Set<String> subscribers = subscriptionManager.getSubscribers(channel);
            
            for (String sessionId : subscribers) {
                // 限流检查
                if (rateLimiter.allow(sessionId)) {
                    messageSender.sendMessage(sessionId, wrapper);
                } else {
                    // 限流，丢弃消息或发送降级数据
                    metrics.incrementDroppedMessages(channel);
                }
            }
            
            ack.acknowledge();
            
        } catch (Exception e) {
            log.error("[Consumer] Failed to process message: {}", record.value(), e);
            // 不ack，让Kafka重试
        }
    }
}
```

### 2.4 Rate Limiter

#### 2.4.1 多层限流

```java
/**
 * 多层限流器
 * 
 * 限流层级：
 * 1. IP限流 - 防止单IP过多连接
 * 2. 连接限流 - 单连接消息频率
 * 3. 频道限流 - 热点频道保护
 */
@Component
public class MultiLevelRateLimiter {
    
    // IP限流器
    private final LoadingCache<String, RateLimiter> ipLimiters;
    
    // Session限流器
    private final LoadingCache<String, RateLimiter> sessionLimiters;
    
    // 频道限流器
    private final LoadingCache<String, RateLimiter> channelLimiters;
    
    // 限流配置
    private static final int IP_MAX_CONNECTIONS = 50;
    private static final int SESSION_MAX_MESSAGES_PER_SECOND = 1000;
    private static final int CHANNEL_MAX_MESSAGES_PER_SECOND = 10000;
    
    public MultiLevelRateLimiter() {
        // IP限流：每IP最多50连接，突发100
        this.ipLimiters = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build(ip -> RateLimiter.create(100.0, 50));
        
        // Session限流：每秒最多1000条消息
        this.sessionLimiters = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(session -> RateLimiter.create(1000.0));
        
        // 频道限流：每秒最多10000条消息
        this.channelLimiters = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(channel -> RateLimiter.create(10000.0));
    }
    
    /**
     * 检查是否允许发送消息
     */
    public boolean allow(String sessionId, String channel) {
        // 检查session限流
        RateLimiter sessionLimiter = sessionLimiters.get(sessionId);
        if (!sessionLimiter.tryAcquire()) {
            return false;
        }
        
        // 检查频道限流
        RateLimiter channelLimiter = channelLimiters.get(channel);
        if (!channelLimiter.tryAcquire()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 检查IP是否允许新建连接
     */
    public boolean allowNewConnection(String ip) {
        AtomicInteger counter = ipConnectionCounter.get(ip);
        return counter == null || counter.get() < IP_MAX_CONNECTIONS;
    }
}

/**
 * Token Bucket 限流器实现
 */
public class TokenBucketRateLimiter {
    
    private final long maxTokens;
    private final double tokensPerSecond;
    
    private double tokens;
    private long lastRefillTime;
    
    public TokenBucketRateLimiter(double tokensPerSecond, long maxTokens) {
        this.tokensPerSecond = tokensPerSecond;
        this.maxTokens = maxTokens;
        this.tokens = maxTokens;
        this.lastRefillTime = System.nanoTime();
    }
    
    public synchronized boolean tryAcquire() {
        refill();
        
        if (tokens >= 1) {
            tokens--;
            return true;
        }
        return false;
    }
    
    private void refill() {
        long now = System.nanoTime();
        double elapsed = (now - lastRefillTime) / 1_000_000_000.0;
        
        tokens = Math.min(maxTokens, tokens + elapsed * tokensPerSecond);
        lastRefillTime = now;
    }
}
```

---

## 三、数据流设计

### 3.1 快照+增量模式

```
客户端                                    公有推送系统
  │                                           │
  │ ─────────── 1. 建立WebSocket连接 ────────> │
  │                                           │
  │ <────────── 2. 连接成功确认 ───────────── │
  │                                           │
  │ ─────────── 3. 订阅 depth.BTCUSDT ──────> │
  │                                           │
  │ <────────── 4. 发送深度快照 ───────────── │  ◄── 从缓存获取最新快照
  │     {                                     │       包含 lastUpdateId
  │       "stream": "depth.BTCUSDT",          │
  │       "data": {                           │
  │         "lastUpdateId": 1000,             │
  │         "bids": [...],                    │
  │         "asks": [...],                    │
  │         "snapshot": true                  │
  │       }                                   │
  │     }                                     │
  │                                           │
  │ <────────── 5. 推送增量更新 ───────────── │  ◄── 从Kafka消费
  │     {                                     │       lastUpdateId > 1000
  │       "stream": "depth.BTCUSDT",          │
  │       "data": {                           │
  │         "U": 1001,                        │
  │         "u": 1002,                        │
  │         "pu": 1000,  ◄── 校验连续性       │
  │         "bids": [...],                    │
  │         "asks": [...]                     │
  │       }                                   │
  │     }                                     │
  │                                           │
  │ <────────── 6. 推送增量更新 ───────────── │
  │     ...                                   │
  │                                           │
  │ ─── 7. 检测到序号不连续（Gap）────────────> │
  │     客户端记录 lastUpdateId = 1000        │
  │     收到 u = 1005，但期望 u = 1003        │
  │                                           │
  │ ─────────── 8. 重新订阅 ────────────────> │
  │     UNSUBSCRIBE depth.BTCUSDT             │
  │     SUBSCRIBE depth.BTCUSDT               │
  │                                           │
  │ <────────── 9. 获取新快照 ─────────────── │  ◄── 重建本地状态
  │     lastUpdateId = 1004                   │
  │                                           │
  │ <────────── 10. 继续接收增量 ──────────── │
  │     从 u = 1005 开始                      │
```

### 3.2 批量聚合发送

```java
/**
 * 批量聚合发送器
 * 
 * 原理：
 * 1. 同一频道的高频消息在窗口期内合并
 * 2. 只发送最新状态（对于depth等场景）
 * 3. 减少网络包数量，提高吞吐
 */
@Component
public class BatchingMessageAggregator {
    
    // 聚合窗口 (ms)
    private static final long AGGREGATION_WINDOW_MS = 100;
    
    // 频道 -> 待发送消息队列
    private final ConcurrentHashMap<String, Queue<Message>> pendingMessages;
    
    // 频道 -> 上次发送时间
    private final ConcurrentHashMap<String, Long> lastFlushTime;
    
    /**
     * 接收消息并聚合
     */
    public void enqueue(String channel, Message message) {
        pendingMessages.computeIfAbsent(channel, k -> new ConcurrentLinkedQueue<>())
                       .offer(message);
        
        // 检查是否需要立即刷新
        long now = System.currentTimeMillis();
        Long lastFlush = lastFlushTime.get(channel);
        
        if (lastFlush == null || now - lastFlush >= AGGREGATION_WINDOW_MS) {
            flush(channel);
        }
    }
    
    /**
     * 刷新频道消息
     */
    public void flush(String channel) {
        Queue<Message> queue = pendingMessages.get(channel);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        
        // 对于深度数据，只取最新的
        List<Message> toSend = new ArrayList<>();
        Message latest = null;
        
        while ((latest = queue.poll()) != null) {
            // 可以在这里进行合并优化
        }
        
        if (latest != null) {
            toSend.add(latest);
        }
        
        // 批量发送
        for (Message msg : toSend) {
            messageSender.broadcast(channel, msg);
        }
        
        lastFlushTime.put(channel, System.currentTimeMillis());
    }
    
    /**
     * 定时刷新所有频道
     */
    @Scheduled(fixedRate = AGGREGATION_WINDOW_MS)
    public void scheduledFlush() {
        pendingMessages.keySet().forEach(this::flush);
    }
}
```

---

## 四、部署架构

### 4.1 生产部署拓扑

```
                              ┌─────────────────┐
                              │   CDN / WAF     │
                              │  (DDoS防护)     │
                              └────────┬────────┘
                                       │
                              ┌────────▼────────┐
                              │   Nginx/LVS     │
                              │  (负载均衡)      │
                              │  SSL终结        │
                              └────────┬────────┘
                                       │
                    ┌──────────────────┼──────────────────┐
                    │                  │                  │
           ┌────────▼────────┐ ┌───────▼────────┐ ┌──────▼────────┐
           │  Public Push    │ │  Public Push   │ │  Public Push  │
           │  Node 1         │ │  Node 2        │ │  Node N       │
           │                 │ │                │ │               │
           │  ┌───────────┐  │ │  ┌───────────┐ │ │  ┌───────────┐│
           │  │  WebSocket│  │ │  │  WebSocket│ │ │  │  WebSocket││
           │  │  Server   │  │ │  │  Server   │ │ │  │  Server   ││
           │  │  (Netty)  │  │ │  │  (Netty)  │ │ │  │  (Netty)  ││
           │  └─────┬─────┘  │ │  └─────┬─────┘ │ │  └─────┬─────┘│
           │        │        │ │        │       │ │        │      │
           │  ┌─────▼─────┐  │ │  ┌─────▼─────┐ │ │  ┌─────▼─────┐│
           │  │  Kafka    │  │ │  │  Kafka    │ │ │  │  Kafka    ││
           │  │ Consumer  │  │ │  │ Consumer  │ │ │  │ Consumer  ││
           │  └───────────┘  │ │  └───────────┘ │ │  └───────────┘│
           └─────────────────┘ └────────────────┘ └───────────────┘
                    │                  │                  │
                    └──────────────────┼──────────────────┘
                                       │
                         ┌─────────────┼─────────────┐
                         │             │             │
                  ┌──────▼──────┐ ┌────▼─────┐ ┌────▼──────┐
                  │  Kafka      │ │  Redis   │ │  Prometheus│
                  │  Cluster    │ │  Cluster │ │  + Grafana │
                  └─────────────┘ └──────────┘ └───────────┘
```

### 4.2 资源配置

| 组件 | 实例数 | CPU | 内存 | 网络 | 存储 |
|------|--------|-----|------|------|------|
| Nginx | 2 | 8核 | 16GB | 10Gbps | 100GB SSD |
| Public Push Node | 6+ | 32核 | 64GB | 10Gbps | 200GB SSD |
| Kafka Broker | 3 | 16核 | 32GB | 10Gbps | 1TB SSD |
| Redis | 3主3从 | 8核 | 32GB | 10Gbps | 200GB SSD |
| Prometheus | 2 | 8核 | 16GB | 1Gbps | 500GB SSD |

### 4.3 扩缩容策略

```yaml
# HPA (Horizontal Pod Autoscaler) 配置
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: public-push-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: public-push-service
  minReplicas: 6
  maxReplicas: 50
  metrics:
    - type: Pods
      pods:
        metric:
          name: websocket_connections_active
        target:
          type: AverageValue
          averageValue: "80000"  # 每节点8万连接
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

---

## 五、监控与告警

### 5.1 核心监控指标

```java
/**
 * 监控指标定义
 */
@Component
public class PublicPushMetrics {
    
    private final MeterRegistry meterRegistry;
    
    // 连接指标
    private final Gauge connectionsActive;
    private final Gauge connectionsTotal;
    private final Counter connectionsOpened;
    private final Counter connectionsClosed;
    
    // 消息指标
    private final Counter messagesSent;
    private final Counter messagesDropped;
    private final Counter messagesFailed;
    private final DistributionSummary messageSize;
    
    // 延迟指标
    private final Timer pushLatency;
    
    // 订阅指标
    private final Gauge subscriptionsPerSession;
    private final Counter subscriptionsAdded;
    private final Counter subscriptionsRemoved;
    
    public void recordMessageSent(String channel, int size) {
        messagesSent.increment();
        messageSize.record(size);
    }
    
    public void recordPushLatency(long latencyMs) {
        pushLatency.record(latencyMs, TimeUnit.MILLISECONDS);
    }
    
    public void updateActiveConnections(int count) {
        connectionsActive.measure(() -> (double) count);
    }
}
```

### 5.2 告警规则

```yaml
# Prometheus Alert Rules
groups:
  - name: public-push-alerts
    rules:
      - alert: HighConnectionCount
        expr: websocket_connections_active > 80000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High WebSocket connection count"
          description: "Instance {{ $labels.instance }} has {{ $value }} active connections"
      
      - alert: HighPushLatency
        expr: histogram_quantile(0.99, websocket_push_latency_bucket) > 100
        for: 3m
        labels:
          severity: critical
        annotations:
          summary: "High push latency detected"
          description: "P99 push latency is {{ $value }}ms"
      
      - alert: MessageDropRateHigh
        expr: rate(websocket_messages_dropped_total[5m]) > 0.01
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High message drop rate"
          description: "Message drop rate is {{ $value }} msg/s"
      
      - alert: ServiceDown
        expr: up{job="public-push-service"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Public Push Service is down"
          description: "Instance {{ $labels.instance }} is down"
```

---

## 六、故障处理

### 6.1 故障场景与处理

| 故障场景 | 检测方式 | 自动恢复 | 手动干预 |
|---------|---------|---------|---------|
| **节点崩溃** | K8s健康检查 | 自动重启/迁移 | 检查崩溃原因 |
| **Kafka消费延迟** | Consumer Lag监控 | 自动扩容消费者 | 检查Kafka状态 |
| **网络分区** | 心跳超时 | 关闭僵尸连接 | 检查网络配置 |
| **内存溢出** | JVM GC监控 | 自动重启 | 分析内存dump |
| **CPU打满** | CPU使用率 | 限流降级 | 扩容节点 |
| **Redis故障** | Redis延迟监控 | 降级到本地缓存 | 修复Redis |

### 6.2 降级策略

```java
/**
 * 降级管理器
 */
@Component
public class DegradationManager {
    
    private final AtomicBoolean degradationEnabled = new AtomicBoolean(false);
    
    /**
     * 检查是否需要降级
     */
    public boolean shouldDegrade(String channel) {
        if (!degradationEnabled.get()) {
            return false;
        }
        
        // 非关键频道降级
        return !isCriticalChannel(channel);
    }
    
    /**
     * 降级发送策略
     */
    public void sendWithDegradation(String sessionId, String channel, Object data) {
        if (shouldDegrade(channel)) {
            // 降低发送频率
            if (shouldSkipNonCritical()) {
                return;
            }
            
            // 或发送简化数据
            Object simplified = simplifyData(data);
            messageSender.sendMessage(sessionId, simplified);
        } else {
            messageSender.sendMessage(sessionId, data);
        }
    }
    
    private boolean isCriticalChannel(String channel) {
        return channel.startsWith("trade.") || channel.startsWith("ticker.");
    }
}
```

---

## 七、性能测试方案

### 7.1 压测场景

| 场景 | 并发连接 | 订阅频道 | 消息频率 | 目标 |
|------|---------|---------|---------|------|
| 连接容量 | 10万/节点 | 1/连接 | - | 验证最大连接数 |
| 消息吞吐 | 1万 | 10/连接 | 100msg/s | 验证消息处理能力 |
| 混合负载 | 5万 | 5/连接 | 50msg/s | 验证综合性能 |
| 热点频道 | 10万 | 相同频道 | 1000msg/s | 验证广播性能 |
| 长尾频道 | 10万 | 不同频道 | 1msg/s | 验证内存使用 |

### 7.2 压测工具

```java
/**
 * WebSocket压测客户端
 */
public class WebSocketLoadTester {
    
    public static void main(String[] args) {
        int connections = 10000;
        String url = "ws://localhost:8096/ws/market";
        
        // 创建连接池
        for (int i = 0; i < connections; i++) {
            createConnection(url, i);
        }
        
        // 发送订阅请求
        broadcastSubscribe("trade.BTCUSDT");
        
        // 监控指标
        startMetricsReporter();
    }
    
    private static void createConnection(String url, int id) {
        WebSocketClient client = new WebSocketClient();
        client.connect(url, new WebSocketListener() {
            @Override
            public void onMessage(String message) {
                // 记录延迟
                long latency = System.currentTimeMillis() - extractTimestamp(message);
                metrics.recordLatency(latency);
            }
        });
    }
}
```

---

## 八、接口文档

### 8.1 WebSocket API

#### 连接
```
URL: wss://api.exchange.com/ws/v1/market
Protocol: RFC 6455
```

#### 订阅
```json
{
  "method": "SUBSCRIBE",
  "params": ["trade.BTCUSDT", "depth.BTCUSDT@100ms"],
  "id": 1
}
```

#### 取消订阅
```json
{
  "method": "UNSUBSCRIBE",
  "params": ["depth.BTCUSDT@100ms"],
  "id": 2
}
```

#### 心跳
```json
// 客户端发送
{"ping": 1704067200123}

// 服务端响应
{"pong": 1704067200123}
```

### 8.2 REST API（快照获取）

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/v1/depth` | GET | 获取深度快照 |
| `/api/v1/kline` | GET | 获取K线数据 |
| `/api/v1/ticker/24hr` | GET | 获取24h统计 |
| `/api/v1/trades` | GET | 获取最新成交 |

---

## 九、版本历史

| 版本 | 日期 | 作者 | 变更说明 |
|------|------|------|---------|
| v1.0.0 | 2026-02-18 | Architecture Team | 初始版本 |

---

**文档结束**
