package com.exchange.push.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.push.config.PublicPushProperties;
import com.exchange.push.model.ChannelType;
import com.exchange.push.model.ConnectionMetadata;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;

/**
 * 消息分发器
 * 
 * 职责：
 * - 批量聚合发送
 * - 消息限流
 * - 压缩与优化
 * - 延迟监控
 */
@Slf4j
@Service
public class MessageDispatcher {

    @Autowired
    private PublicPushProperties properties;
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    @Autowired
    private ConnectionManager connectionManager;
    
    @Autowired
    private RateLimiter rateLimiter;
    
    @Autowired
    private SubscriptionManager subscriptionManager;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // 批量发送队列 sessionId -> 消息队列
    private final Map<String, Queue<String>> batchQueues = new ConcurrentHashMap<>();
    
    // 频道 -> 消息队列（用于聚合）
    private final Map<String, Queue<JSONObject>> channelQueues = new ConcurrentHashMap<>();
    
    // 定时任务执行器
    private final ScheduledExecutorService flushExecutor = Executors.newScheduledThreadPool(4);
    
    // 监控指标
    private Counter messagesSent;
    private Counter messagesDropped;
    private DistributionSummary messageSize;
    private Timer pushLatency;

    @PostConstruct
    public void init() {
        // 注册监控指标
        messagesSent = Counter.builder("websocket.messages.sent")
            .description("Total messages sent")
            .register(meterRegistry);
        
        messagesDropped = Counter.builder("websocket.messages.dropped")
            .description("Total messages dropped due to rate limiting")
            .register(meterRegistry);
        
        messageSize = DistributionSummary.builder("websocket.message.size")
            .description("Message size in bytes")
            .baseUnit("bytes")
            .register(meterRegistry);
        
        pushLatency = Timer.builder("websocket.push.latency")
            .description("Message push latency")
            .register(meterRegistry);
        
        // 启动定时刷新任务
        long flushInterval = properties.getPush().getBatchIntervalMs();
        flushExecutor.scheduleAtFixedRate(this::scheduledFlush, flushInterval, flushInterval, TimeUnit.MILLISECONDS);
        
        log.info("[Dispatcher] Initialized with flush interval: {}ms", flushInterval);
    }

    /**
     * 发送消息到指定session
     */
    public void sendMessage(String sessionId, Object data) {
        ConnectionManager.SessionContext ctx = connectionManager.getSessionContext(sessionId);
        if (ctx == null) {
            return;
        }
        
        // 限流检查
        if (!rateLimiter.allowSessionMessage(sessionId)) {
            messagesDropped.increment();
            return;
        }
        
        String json = JSON.toJSONString(data);
        boolean highPriority = isHighPriority(data);
        
        sendMessageInternal(sessionId, json, highPriority);
    }

    /**
     * 内部发送方法（已序列化，避免对 N 个订阅者重复序列化）
     */
    private void sendMessageInternal(String sessionId, String json, boolean highPriority) {
        ConnectionManager.SessionContext ctx = connectionManager.getSessionContext(sessionId);
        if (ctx == null) {
            return;
        }
        
        // 限流检查
        if (!rateLimiter.allowSessionMessage(sessionId)) {
            messagesDropped.increment();
            return;
        }
        
        if (highPriority) {
            emitMessage(ctx, json);
        } else {
            // 普通消息加入批量队列
            batchQueues.computeIfAbsent(sessionId, k -> new ConcurrentLinkedQueue<>()).offer(json);
            
            // 检查是否达到批量阈值
            Queue<String> queue = batchQueues.get(sessionId);
            if (queue != null && queue.size() >= properties.getPush().getBatchSize()) {
                flushBatch(sessionId);
            }
        }
    }

    /**
     * 广播消息到频道
     */
    public void broadcast(String channel, JSONObject data) {
        long startTime = System.currentTimeMillis();
        
        ChannelType channelType = ChannelType.fromChannel(channel);
        if (channelType == ChannelType.DEPTH) {
            // 深度消息：按 session 独立校验序列号，然后入队聚合
            validateAndUpdateDepthSequence(channel, data);
            channelQueues.computeIfAbsent(channel, k -> new ConcurrentLinkedQueue<>()).offer(data);
            return;
        }
        
        // 包装消息并预先序列化（避免对 N 个订阅者重复序列化）
        JSONObject wrapper = new JSONObject();
        wrapper.put("stream", channel);
        wrapper.put("data", data);
        String json = wrapper.toJSONString();
        messageSize.record(json.getBytes().length);
        
        // 获取订阅者并发送
        Set<String> subscribers = getSubscribersForChannel(channel);
        
        for (String sessionId : subscribers) {
            if (rateLimiter.allowChannelMessage(channel)) {
                sendMessageInternal(sessionId, json, false);
            } else {
                messagesDropped.increment();
            }
        }
        
        // 记录延迟
        long latency = System.currentTimeMillis() - startTime;
        pushLatency.record(latency, TimeUnit.MILLISECONDS);
        messagesSent.increment(subscribers.size());
    }

    /**
     * 按 session 独立校验深度序列号连续性，发现断档仅对受影响 session 补发快照
     */
    private void validateAndUpdateDepthSequence(String channel, JSONObject data) {
        if (!data.containsKey("U") || !data.containsKey("u")) {
            return;
        }
        
        long firstUpdateId = data.getLongValue("U");
        long lastUpdateId = data.getLongValue("u");
        
        Set<String> subscribers = getSubscribersForChannel(channel);
        
        for (String sessionId : subscribers) {
            ConnectionMetadata metadata = connectionManager.getMetadata(sessionId);
            if (metadata == null) {
                continue;
            }
            
            long lastSeq = metadata.getLastSequence(channel);
            
            if (lastSeq == 0) {
                // 首次消息或快照后的第一条消息
                if (firstUpdateId > 1 || lastUpdateId < 1) {
                    log.warn("[Dispatcher] Invalid first depth message for {}: U={}, u={}", 
                            sessionId, firstUpdateId, lastUpdateId);
                    sendSnapshot(sessionId, channel);
                    continue;
                }
            } else {
                // 后续消息：必须连续
                if (firstUpdateId != lastSeq + 1 && lastUpdateId != lastSeq + 1) {
                    if (firstUpdateId > lastSeq + 1 || lastUpdateId < lastSeq + 1) {
                        log.warn("[Dispatcher] Sequence gap for {}: lastSeq={}, U={}, u={}", 
                                sessionId, lastSeq, firstUpdateId, lastUpdateId);
                        sendSnapshot(sessionId, channel);
                        continue;
                    }
                }
            }
            
            metadata.updateSequence(channel, lastUpdateId);
        }
    }

    /**
     * 发送快照
     */
    public void sendSnapshot(String sessionId, String channel) {
        ChannelType channelType = ChannelType.fromChannel(channel);
        
        try {
            JSONObject snapshot = null;
            
            switch (channelType) {
                case DEPTH:
                    snapshot = fetchDepthSnapshot(channel);
                    break;
                case TICKER:
                    snapshot = fetchTickerSnapshot(channel);
                    break;
                case TRADE:
                    snapshot = fetchTradeSnapshot(channel);
                    break;
                case KLINE:
                    snapshot = fetchKlineSnapshot(channel);
                    break;
                default:
                    log.debug("[Dispatcher] No snapshot support for channel: {}", channel);
                    return;
            }
            
            if (snapshot != null) {
                JSONObject wrapper = new JSONObject();
                wrapper.put("stream", channel);
                wrapper.put("data", snapshot);
                wrapper.put("snapshot", true);
                
                sendMessage(sessionId, wrapper);
                
                // 更新序列号
                ConnectionMetadata metadata = connectionManager.getMetadata(sessionId);
                if (metadata != null && snapshot.containsKey("lastUpdateId")) {
                    metadata.updateSequence(channel, snapshot.getLongValue("lastUpdateId"));
                }
            }
            
        } catch (Exception e) {
            log.error("[Dispatcher] Failed to send snapshot for {}: {}", channel, e.getMessage());
        }
    }

    /**
     * 通过 Sink 发送单条消息（WebFlux 非阻塞模型）
     */
    private void emitMessage(ConnectionManager.SessionContext ctx, String json) {
        Sinks.Many<String> sink = ctx.getSink();
        if (sink == null) {
            return;
        }
        
        Sinks.EmitResult result = sink.tryEmitNext(json);
        if (result.isSuccess()) {
            ConnectionMetadata metadata = ctx.getMetadata();
            if (metadata != null) {
                metadata.incrementMessagesSent(json.getBytes().length);
            }
            messagesSent.increment();
        } else if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
            log.warn("[Dispatcher] Sink overflow for {}, dropping message", ctx.getSessionId());
            messagesDropped.increment();
        } else if (result.isFailure()) {
            log.warn("[Dispatcher] Failed to emit message for {}: {}", ctx.getSessionId(), result);
        }
    }

    /**
     * 刷新批量队列
     */
    private void flushBatch(String sessionId) {
        Queue<String> queue = batchQueues.get(sessionId);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        
        ConnectionManager.SessionContext ctx = connectionManager.getSessionContext(sessionId);
        if (ctx == null) {
            queue.clear();
            return;
        }
        
        // 合并消息
        List<String> messages = new ArrayList<>();
        String msg;
        while ((msg = queue.poll()) != null && messages.size() < properties.getPush().getBatchSize()) {
            messages.add(msg);
        }
        
        if (messages.isEmpty()) {
            return;
        }
        
        if (messages.size() == 1) {
            emitMessage(ctx, messages.get(0));
            return;
        }
        
        // 构建批量消息
        JSONObject batchWrapper = new JSONObject();
        batchWrapper.put("batch", true);
        batchWrapper.put("messages", messages);
        
        String json = batchWrapper.toJSONString();
        emitMessage(ctx, json);
    }

    /**
     * 定时刷新所有队列
     */
    private void scheduledFlush() {
        // 刷新session队列，同时清理无效session的队列
        batchQueues.entrySet().removeIf(entry -> {
            String sessionId = entry.getKey();
            if (!connectionManager.exists(sessionId)) {
                return true; // 移除无效队列
            }
            flushBatch(sessionId);
            return false;
        });
        
        // 刷新频道队列（深度数据聚合）
        channelQueues.entrySet().removeIf(entry -> {
            String channel = entry.getKey();
            Queue<JSONObject> queue = entry.getValue();
            
            // 清理无订阅者的频道队列
            if (subscriptionManager.getSubscriberCount(channel) == 0) {
                return true;
            }
            
            if (!queue.isEmpty()) {
                // 只取最新的深度数据
                JSONObject latest = null;
                while (!queue.isEmpty()) {
                    latest = queue.poll();
                }
                
                if (latest != null) {
                    // 直接发送，不走 broadcast 的 depth 分支（避免死循环）
                    JSONObject wrapper = new JSONObject();
                    wrapper.put("stream", channel);
                    wrapper.put("data", latest);
                    String json = wrapper.toJSONString();
                    messageSize.record(json.getBytes().length);
                    
                    Set<String> subscribers = getSubscribersForChannel(channel);
                    for (String sessionId : subscribers) {
                        if (rateLimiter.allowChannelMessage(channel)) {
                            sendMessageInternal(sessionId, json, false);
                        } else {
                            messagesDropped.increment();
                        }
                    }
                    messagesSent.increment(subscribers.size());
                }
            }
            return false;
        });
    }

    /**
     * 获取频道的订阅者
     */
    private Set<String> getSubscribersForChannel(String channel) {
        return subscriptionManager.getSubscribers(channel);
    }

    /**
     * 判断是否高优先级消息
     */
    private boolean isHighPriority(Object data) {
        if (data instanceof JSONObject) {
            JSONObject json = (JSONObject) data;
            // ping/pong响应优先
            return json.containsKey("pong");
        }
        return false;
    }

    // ========== 快照获取方法 ==========

    private JSONObject fetchDepthSnapshot(String channel) {
        String symbol = extractSymbol(channel);
        String key = "market:snapshot:depth:" + symbol;
        Object data = redisTemplate.opsForValue().get(key);

        if (data == null) {
            log.warn("[Dispatcher] Depth snapshot not found for channel: {}", channel);
            return null;
        }

        // 确保data是String类型
        String jsonStr = (data instanceof String) ? (String) data : data.toString();
        JSONObject snapshot = null;
        try {
            snapshot = JSON.parseObject(jsonStr);
        } catch (Exception e) {
            log.error("[Dispatcher] Failed to parse depth snapshot for channel: {}, jsonLength: {}, error: {}",
                    channel, jsonStr != null ? jsonStr.length() : 0, e.getMessage());
            return null;
        }

        // 新鲜度检查（深度数据应在3秒内）
        if (!isSnapshotFresh(snapshot, 3000)) {
            log.warn("[Dispatcher] Stale depth snapshot for channel: {}, age: {}ms",
                    channel, getSnapshotAge(snapshot));
            // 仍然返回，避免客户端完全没数据，但记录告警
        }

        return snapshot;
    }

    private JSONObject fetchTickerSnapshot(String channel) {
        String symbol = extractSymbol(channel);
        String key = "market:snapshot:ticker:" + symbol;
        Object data = redisTemplate.opsForValue().get(key);

        if (data == null) {
            log.warn("[Dispatcher] Ticker snapshot not found for channel: {}", channel);
            return null;
        }

        JSONObject snapshot = JSON.parseObject(data.toString());

        // 新鲜度检查（Ticker数据应在10秒内）
        if (!isSnapshotFresh(snapshot, 10000)) {
            log.warn("[Dispatcher] Stale ticker snapshot for channel: {}, age: {}ms",
                    channel, getSnapshotAge(snapshot));
        }

        return snapshot;
    }

    private JSONObject fetchTradeSnapshot(String channel) {
        String symbol = extractSymbol(channel);

        // 优先从标准快照读取最新一条
        String singleKey = "market:snapshot:trade:" + symbol;
        Object latestData = redisTemplate.opsForValue().get(singleKey);

        // 从List读取最近100条（binance-data-source存储的）
        String listKey = "binance:trade:" + symbol;
        List<Object> tradeList = redisTemplate.opsForList().range(listKey, 0, 99);

        JSONObject snapshot = new JSONObject();
        snapshot.put("e", "tradeSnapshot");
        snapshot.put("s", symbol);
        snapshot.put("E", System.currentTimeMillis());

        if (tradeList != null && !tradeList.isEmpty()) {
            // 返回最近100条成交记录
            snapshot.put("trades", tradeList);
            snapshot.put("count", tradeList.size());
            log.debug("[Dispatcher] Fetched {} trades for snapshot: {}", tradeList.size(), channel);
        } else if (latestData != null) {
            // 降级：只有最新一条
            JSONObject latestTrade = JSON.parseObject(latestData.toString());
            snapshot.put("trades", Collections.singletonList(latestTrade));
            snapshot.put("count", 1);
            log.debug("[Dispatcher] Fetched 1 trade (fallback) for snapshot: {}", channel);
        } else {
            log.warn("[Dispatcher] Trade snapshot not found for channel: {}", channel);
            return null;
        }

        return snapshot;
    }

    private JSONObject fetchKlineSnapshot(String channel) {
        // kline.{symbol}.{interval} 或 kline.{interval}.{symbol}
        String[] parts = channel.split("\\.");
        String symbol = null;
        String interval = null;

        if (parts.length >= 3) {
            // 尝试两种格式
            if (parts[1].matches("[A-Z]+")) {
                // kline.BTCUSDT.1m
                symbol = parts[1];
                interval = parts[2];
            } else {
                // kline.1m.BTCUSDT
                interval = parts[1];
                symbol = parts[2];
            }
        }

        if (symbol == null || interval == null) {
            log.warn("[Dispatcher] Invalid kline channel format: {}", channel);
            return null;
        }

        // 1. 获取当前正在形成的K线（最新）
        String currentKey = "market:snapshot:kline:" + symbol + ":" + interval;
        Object currentData = redisTemplate.opsForValue().get(currentKey);

        // 2. 获取历史K线（最近50根）
        String historyKey = "market:snapshot:kline:history:" + symbol + ":" + interval;
        List<Object> historyListReversed = redisTemplate.opsForList().range(historyKey, 0, 49);

        // 反转列表（Redis中是最新的在前，客户端期望从旧到新）
        List<Object> historyList = new ArrayList<>();
        if (historyListReversed != null && !historyListReversed.isEmpty()) {
            for (int i = historyListReversed.size() - 1; i >= 0; i--) {
                historyList.add(historyListReversed.get(i));
            }
        }

        JSONObject snapshot = new JSONObject();
        snapshot.put("e", "klineSnapshot");
        snapshot.put("s", symbol);
        snapshot.put("i", interval);
        snapshot.put("E", System.currentTimeMillis());

        if (currentData != null) {
            JSONObject currentKline = JSON.parseObject(currentData.toString());
            snapshot.put("current", currentKline);

            // 新鲜度检查（K线数据应在对应周期的2倍时间内）
            long maxAge = getKlineIntervalMs(interval) * 2;
            if (!isSnapshotFresh(currentKline, maxAge)) {
                log.warn("[Dispatcher] Stale kline snapshot for channel: {}, age: {}ms, maxAge: {}ms",
                        channel, getSnapshotAge(currentKline), maxAge);
            }
        }

        if (!historyList.isEmpty()) {
            snapshot.put("history", historyList);
            snapshot.put("historyCount", historyList.size());
            log.debug("[Dispatcher] Fetched {} historical klines (reversed to chronological order) for snapshot: {}",
                    historyList.size(), channel);
        } else {
            // 没有历史数据，只返回当前K线
            if (currentData == null) {
                log.warn("[Dispatcher] No kline data found for channel: {}", channel);
                return null;
            }
            snapshot.put("history", Collections.emptyList());
            snapshot.put("historyCount", 0);
            log.debug("[Dispatcher] No historical klines, only current kline for: {}", channel);
        }

        return snapshot;
    }

    /**
     * 检查快照是否新鲜
     *
     * @param snapshot 快照数据
     * @param maxAgeMs 最大允许年龄（毫秒）
     * @return true 如果新鲜
     */
    private boolean isSnapshotFresh(JSONObject snapshot, long maxAgeMs) {
        if (snapshot == null) {
            return false;
        }

        long eventTime = snapshot.getLongValue("E");
        if (eventTime <= 0) {
            // 尝试其他时间字段
            eventTime = snapshot.getLongValue("T");
        }

        if (eventTime <= 0) {
            return true; // 没有时间戳，假设新鲜
        }

        long age = System.currentTimeMillis() - eventTime;
        return age <= maxAgeMs;
    }

    /**
     * 获取快照年龄（毫秒）
     */
    private long getSnapshotAge(JSONObject snapshot) {
        if (snapshot == null) {
            return Long.MAX_VALUE;
        }

        long eventTime = snapshot.getLongValue("E");
        if (eventTime <= 0) {
            eventTime = snapshot.getLongValue("T");
        }

        if (eventTime <= 0) {
            return 0;
        }

        return System.currentTimeMillis() - eventTime;
    }

    /**
     * 获取K线周期对应的毫秒数
     */
    private long getKlineIntervalMs(String interval) {
        if (interval == null || interval.isEmpty()) {
            return 60000; // 默认1分钟
        }

        // 解析间隔（如 "1m", "5m", "1h", "1d"）
        try {
            char unit = interval.charAt(interval.length() - 1);
            int value = Integer.parseInt(interval.substring(0, interval.length() - 1));

            return switch (unit) {
                case 's' -> value * 1000L;
                case 'm' -> value * 60000L;
                case 'h' -> value * 3600000L;
                case 'd' -> value * 86400000L;
                case 'w' -> value * 604800000L;
                default -> 60000L; // 默认1分钟
            };
        } catch (Exception e) {
            log.warn("[Dispatcher] Failed to parse interval: {}", interval);
            return 60000L; // 默认1分钟
        }
    }

    private String extractSymbol(String channel) {
        // depth.BTCUSDT@100ms -> BTCUSDT
        // ticker.BTCUSDT -> BTCUSDT
        int dotIndex = channel.indexOf('.');
        if (dotIndex > 0) {
            String rest = channel.substring(dotIndex + 1);
            int atIndex = rest.indexOf('@');
            return atIndex > 0 ? rest.substring(0, atIndex) : rest;
        }
        return channel;
    }

    /**
     * 关闭服务
     */
    public void shutdown() {
        flushExecutor.shutdown();
    }
}
