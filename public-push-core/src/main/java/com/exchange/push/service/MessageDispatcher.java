package com.exchange.push.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.push.config.PublicPushProperties;
import com.exchange.push.model.ChannelType;
import com.exchange.push.model.ConnectionMetadata;
import com.exchange.push.service.ConnectionManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
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
    @Lazy
    private ConnectionManager connectionManager;
    
    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    @Lazy
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
        WebSocketSession session = connectionManager.getSession(sessionId);
        if (session == null || !session.isOpen()) {
            log.warn("[WS-LINK] Session not found or closed: {}", sessionId);
            return;
        }
        
        // 限流检查
        if (!rateLimiter.allowSessionMessage(sessionId)) {
            messagesDropped.increment();
            log.warn("[WS-LINK] Message dropped due to rate limit, session: {}", sessionId);
            return;
        }
        
        String json = JSON.toJSONString(data);
        log.info("[WS-LINK] >>> WebSocket sending, sessionId={}, size={}bytes", sessionId, json.length());
        
        // 高优先级消息立即发送
        if (isHighPriority(data)) {
            flushImmediately(sessionId, json);
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
        
        // 对于深度消息，校验序列号连续性
        ChannelType channelType = ChannelType.fromChannel(channel);
        if (channelType == ChannelType.DEPTH) {
            log.info("[WS-LINK] Processing depth message, channel={}, U={}, u={}", 
                channel, data.getLongValue("U"), data.getLongValue("u"));
            
            // 校验序列号连续性
            if (!validateDepthSequence(channel, data)) {
                // 序列号不连续，触发快照重建
                log.warn("[WS-LINK] Sequence gap detected for channel: {}, triggering snapshot rebuild", channel);
                triggerSnapshotRebuild(channel);
                return;
            }
            
            // 深度数据使用队列聚合
            channelQueues.computeIfAbsent(channel, k -> new ConcurrentLinkedQueue<>()).offer(data);
            log.info("[WS-LINK] Depth message queued for aggregation, channel={}", channel);
            return;
        }
        
        // 包装消息
        JSONObject wrapper = new JSONObject();
        wrapper.put("stream", channel);
        wrapper.put("data", data);
        
        // 预先序列化
        String json = wrapper.toJSONString();
        messageSize.record(json.getBytes().length);
        
        // 获取订阅者并发送
        Set<String> subscribers = getSubscribersForChannel(channel);
        
        if (subscribers.isEmpty()) {
            log.debug("[Dispatcher] No subscribers for channel: {}", channel);
            return;
        }
        
        log.debug("[Dispatcher] Broadcasting to {} subscribers for channel: {}", subscribers.size(), channel);
        
        for (String sessionId : subscribers) {
            if (rateLimiter.allowChannelMessage(channel)) {
                sendMessage(sessionId, wrapper);
                log.trace("[Dispatcher] Sent message to session: {}, channel: {}", sessionId, channel);
            } else {
                messagesDropped.increment();
                log.warn("[Dispatcher] Message dropped due to rate limit, session: {}, channel: {}", sessionId, channel);
            }
        }
        
        // 记录延迟
        long latency = System.currentTimeMillis() - startTime;
        pushLatency.record(latency, TimeUnit.MILLISECONDS);
        messagesSent.increment(subscribers.size());
    }

    /**
     * 校验深度消息序列号连续性
     * 
     * 规则：
     * - 首次消息：U <= lastUpdateId + 1 && u >= lastUpdateId + 1
     * - 后续消息：u == lastUpdateId + 1
     */
    private boolean validateDepthSequence(String channel, JSONObject data) {
        if (!data.containsKey("U") || !data.containsKey("u")) {
            return false;
        }
        
        long firstUpdateId = data.getLongValue("U");
        long lastUpdateId = data.getLongValue("u");
        
        // 获取所有订阅者
        Set<String> subscribers = getSubscribersForChannel(channel);
        
        // 检查每个订阅者的序列号连续性
        boolean allValid = true;
        for (String sessionId : subscribers) {
            ConnectionMetadata metadata = connectionManager.getMetadata(sessionId);
            if (metadata == null) {
                continue;
            }
            
            long lastSeq = metadata.getLastSequence(channel);
            
            // 首次消息或快照后的第一条消息
            if (lastSeq == 0) {
                // 对首次增量不强制要求从 1 开始，直接接受并对齐到当前 u。
                if (lastUpdateId <= 0 || firstUpdateId <= 0) {
                    allValid = false;
                    log.warn("[Dispatcher] Invalid first depth message for {}: U={}, u={}, expected positive update ids",
                            sessionId, firstUpdateId, lastUpdateId);
                } else {
                    // 更新序列号
                    metadata.updateSequence(channel, lastUpdateId);
                }
            } else {
                // 后续消息：
                // - 允许重叠区间（U <= lastSeq+1 <= u）
                // - 只在出现真正缺口（U > lastSeq+1）时判定 gap
                long expected = lastSeq + 1;
                if (firstUpdateId > expected) {
                    allValid = false;
                    log.warn("[Dispatcher] Sequence gap for {}: lastSeq={}, U={}, u={}",
                            sessionId, lastSeq, firstUpdateId, lastUpdateId);
                } else if (lastUpdateId >= expected) {
                    metadata.updateSequence(channel, lastUpdateId);
                } else {
                    // 过期/重复消息，忽略但不视为错误
                    log.debug("[Dispatcher] Ignore stale depth update for {}: lastSeq={}, U={}, u={}",
                            sessionId, lastSeq, firstUpdateId, lastUpdateId);
                }
            }
        }
        
        return allValid;
    }

    /**
     * 触发快照重建
     */
    private void triggerSnapshotRebuild(String channel) {
        Set<String> subscribers = getSubscribersForChannel(channel);
        for (String sessionId : subscribers) {
            // 发送新的快照
            sendSnapshot(sessionId, channel);
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
                if (metadata != null) {
                    long seq = 0L;
                    if (snapshot.containsKey("lastUpdateId")) {
                        seq = snapshot.getLongValue("lastUpdateId");
                    } else if (snapshot.containsKey("u")) {
                        seq = snapshot.getLongValue("u");
                    }
                    if (seq > 0) {
                        metadata.updateSequence(channel, seq);
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("[Dispatcher] Failed to send snapshot for {}: {}", channel, e.getMessage());
        }
    }

    /**
     * 立即发送消息
     */
    private void flushImmediately(String sessionId, String json) {
        WebSocketSession session = connectionManager.getSession(sessionId);
        if (session == null || !session.isOpen()) {
            log.warn("[WS-LINK] Session closed, cannot send, sessionId={}", sessionId);
            return;
        }
        
        try {
            session.sendMessage(new TextMessage(json));
            log.info("[WS-LINK] >>> WebSocket sent (immediate), sessionId={}, size={}bytes", sessionId, json.length());
            
            // 更新统计
            ConnectionMetadata metadata = connectionManager.getMetadata(sessionId);
            if (metadata != null) {
                metadata.incrementMessagesSent(json.getBytes().length);
            }
            messagesSent.increment();
            
        } catch (IOException e) {
            log.error("[Dispatcher] Failed to send message to {}: {}", sessionId, e.getMessage());
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
        
        WebSocketSession session = connectionManager.getSession(sessionId);
        if (session == null || !session.isOpen()) {
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
        
        // 构建批量消息
        JSONObject batchWrapper = new JSONObject();
        batchWrapper.put("batch", true);
        batchWrapper.put("messages", messages);
        
        try {
            String json = batchWrapper.toJSONString();
            session.sendMessage(new TextMessage(json));
            
            // 更新统计
            ConnectionMetadata metadata = connectionManager.getMetadata(sessionId);
            if (metadata != null) {
                metadata.incrementMessagesSent(json.getBytes().length);
            }
            messagesSent.increment(messages.size());
            
        } catch (IOException e) {
            log.error("[Dispatcher] Failed to send batch to {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 定时刷新所有队列
     */
    private void scheduledFlush() {
        // 刷新session队列
        batchQueues.keySet().forEach(this::flushBatch);
        
        // 刷新频道队列（深度数据聚合）- 直接发送，不再调用broadcast避免重复验证
        channelQueues.forEach((channel, queue) -> {
            if (!queue.isEmpty()) {
                // 只取最新的深度数据
                JSONObject latest = null;
                while (!queue.isEmpty()) {
                    latest = queue.poll();
                }
                
                if (latest != null) {
                    // 直接发送，绕过broadcast的序列号验证
                    broadcastDirect(channel, latest);
                }
            }
        });
    }
    
    /**
     * 直接广播（不验证序列号，用于聚合后的深度数据）
     */
    private void broadcastDirect(String channel, JSONObject data) {
        JSONObject wrapper = new JSONObject();
        wrapper.put("stream", channel);
        wrapper.put("data", data);
        
        String json = wrapper.toJSONString();
        
        // 获取订阅者并发送
        Set<String> subscribers = getSubscribersForChannel(channel);
        if (subscribers.isEmpty()) {
            return;
        }
        
        log.info("[WS-LINK] >>> Broadcasting aggregated depth, channel={}, subscribers={}", channel, subscribers.size());
        
        for (String sessionId : subscribers) {
            WebSocketSession session = connectionManager.getSession(sessionId);
            if (session != null && session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(json));
                    messagesSent.increment();
                } catch (IOException e) {
                    log.error("[WS-LINK] Failed to send: {}", sessionId);
                }
            }
        }
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
        return data != null ? JSON.parseObject(data.toString()) : null;
    }

    private JSONObject fetchTickerSnapshot(String channel) {
        String symbol = extractSymbol(channel);
        String key = "market:snapshot:ticker:" + symbol;
        Object data = redisTemplate.opsForValue().get(key);
        return data != null ? JSON.parseObject(data.toString()) : null;
    }

    private JSONObject fetchTradeSnapshot(String channel) {
        String symbol = extractSymbol(channel);
        String key = "market:snapshot:trade:" + symbol;
        Object data = redisTemplate.opsForValue().get(key);
        return data != null ? JSON.parseObject(data.toString()) : null;
    }

    private JSONObject fetchKlineSnapshot(String channel) {
        // 兼容两种格式：
        // 1) kline.{symbol}.{interval}（当前前端使用）
        // 2) kline.{interval}.{symbol}（历史格式）
        String[] parts = channel.split("\\.");
        if (parts.length >= 3) {
            String first = parts[1];
            String second = parts[2];
            String symbol;
            String interval;

            if (isKlineIntervalToken(first) && !isKlineIntervalToken(second)) {
                interval = first;
                symbol = second;
            } else {
                symbol = first;
                interval = second;
            }

            String key = "market:snapshot:kline:" + symbol + ":" + interval;
            Object data = redisTemplate.opsForValue().get(key);
            return data != null ? JSON.parseObject(data.toString()) : null;
        }
        return null;
    }

    private boolean isKlineIntervalToken(String token) {
        if (token == null || token.length() < 2) {
            return false;
        }
        char unit = token.charAt(token.length() - 1);
        if ("smhdwM".indexOf(unit) < 0) {
            return false;
        }
        for (int i = 0; i < token.length() - 1; i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
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
