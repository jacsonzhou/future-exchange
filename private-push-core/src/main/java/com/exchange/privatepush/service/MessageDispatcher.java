package com.exchange.privatepush.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.privatepush.config.PrivatePushProperties;
import com.exchange.privatepush.model.SessionMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 私有消息分发器
 * 
 * 🔥 核心职责：
 * 1. 将消息分发到指定用户的所有会话
 * 2. ACK 确认与重发机制
 * 3. 批量聚合发送
 * 4. 限流控制
 * 
 * 消息流程:
 * Kafka Consumer → parse → add seq → send to user sessions → wait ACK
 *                                          ↓
 *                                    (timeout) → retry (max 3)
 * 
 * @author Exchange Team
 * @version 1.0.0
 */
@Slf4j
@Service
public class MessageDispatcher {
    
    @Autowired
    private SessionManager sessionManager;
    
    @Autowired
    private SubscriptionManager subscriptionManager;
    
    @Autowired
    private PrivatePushProperties properties;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private MessagePersistenceService messagePersistenceService;
    
    // 待确认消息: sessionId -> (seq -> message)
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, PendingMessage>> pendingMessages = 
            new ConcurrentHashMap<>();
    
    // 重发调度器
    private ScheduledExecutorService retryExecutor;
    
    // 批量发送队列: sessionId -> 消息队列
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<BatchMessage>> batchQueues = new ConcurrentHashMap<>();

    // 批量发送调度器
    private ScheduledExecutorService batchExecutor;

    // 统计
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesAcked = new AtomicLong(0);
    private final AtomicLong messagesDropped = new AtomicLong(0);
    private final AtomicLong messagesBatched = new AtomicLong(0);
    
    @PostConstruct
    public void init() {
        // 启动重发定时任务
        retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "message-retry");
            t.setDaemon(true);
            return t;
        });

        retryExecutor.scheduleAtFixedRate(
            this::checkPendingMessages,
            1,
            1,
            TimeUnit.SECONDS
        );

        // 启动批量发送定时任务（每10ms或100条消息触发一次）
        batchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "message-batch");
            t.setDaemon(true);
            return t;
        });

        batchExecutor.scheduleAtFixedRate(
            this::flushBatchQueues,
            10,
            10,
            TimeUnit.MILLISECONDS
        );

        log.info("[MessageDispatcher] Initialized with batch sending");
    }
    
    @PreDestroy
    public void destroy() {
        if (retryExecutor != null) {
            retryExecutor.shutdown();
        }
        if (batchExecutor != null) {
            // 先刷新所有待发送消息
            flushBatchQueues();
            batchExecutor.shutdown();
        }
        log.info("[MessageDispatcher] Destroyed");
    }
    
    /**
     * 发送消息给指定用户（优化版：批量聚合）
     *
     * @param userId 用户ID
     * @param channel 频道
     * @param data 消息数据
     */
    public void sendToUser(Long userId, String channel, JSONObject data) {
        // 限流检查（用户级别）
        if (!rateLimiter.allowUser(userId)) {
            log.warn("[MessageDispatcher] Rate limit exceeded for user, userId={}, channel={}",
                    userId, channel);
            messagesDropped.incrementAndGet();
            return;
        }

        // 快速路径：直接从用户映射获取会话ID
        Set<String> sessionIds = sessionManager.getUserSessionIds(userId);

        if (sessionIds.isEmpty()) {
            // 用户不在线，可考虑缓存到 Redis
            log.debug("[MessageDispatcher] User not online, userId={}, channel={}", userId, channel);
            return;
        }

        // 为每个会话发送消息（避免创建Map对象）
        for (String sessionId : sessionIds) {
            SessionMetadata metadata = sessionManager.getSession(sessionId);
            if (metadata == null) {
                continue;
            }

            // 检查是否订阅了该频道（避免重复检查）
            if (!metadata.isSubscribed(channel)) {
                continue;
            }

            // 限流检查（IP级别）- 优化：批量检查
            if (!rateLimiter.allowIp(metadata.getClientIp())) {
                log.warn("[MessageDispatcher] Rate limit exceeded for IP, userId={}, ip={}, channel={}",
                        userId, metadata.getClientIp(), channel);
                messagesDropped.incrementAndGet();
                continue;
            }

            sendToSession(metadata, channel, data);
        }
    }
    
    /**
     * 发送消息到指定会话（优化版：减少对象创建）
     */
    private void sendToSession(SessionMetadata metadata, String channel, JSONObject data) {
        String sessionId = metadata.getSessionId();
        WebSocketSession session = metadata.getSession();

        if (session == null || !session.isOpen()) {
            return;
        }

        try {
            // 生成序列号（优化：使用位运算代替乘法取模）
            long seq = metadata.nextSequenceOptimized();

            // 包装消息（优化：减少put调用）
            JSONObject message = new JSONObject(4);
            message.put("stream", channel);
            message.put("data", data);
            message.put("seq", seq);
            message.put("E", System.currentTimeMillis());

            String json = message.toJSONString();

            // 如果需要ACK，加入待确认队列
            if (properties.getAck().isEnabled()) {
                addPendingMessage(sessionId, seq, json);
            }

            // 发送消息
            session.sendMessage(new TextMessage(json));

            // 持久化消息到Redis（异步）
            messagePersistenceService.saveMessage(metadata.getUserId(), seq, json);

            // 更新统计（优化：减少方法调用）
            int byteLength = json.length() << 1; // UTF-8近似2倍字节
            metadata.incrementMessagesSent(byteLength);
            messagesSent.incrementAndGet();

            log.debug("[MessageDispatcher] Sent to session, sessionId={}, channel={}, seq={}",
                    sessionId, channel, seq);

        } catch (IOException e) {
            log.error("[MessageDispatcher] Failed to send message, sessionId={}", sessionId, e);
            messagesDropped.incrementAndGet();
        }
    }
    
    /**
     * 广播消息到频道
     */
    public void broadcast(String channel, JSONObject data) {
        Set<String> subscribers = subscriptionManager.getSubscribers(channel);
        
        for (String sessionId : subscribers) {
            SessionMetadata metadata = sessionManager.getSession(sessionId);
            if (metadata != null) {
                sendToSession(metadata, channel, data);
            }
        }
        
        log.debug("[MessageDispatcher] Broadcast to channel={}, subscribers={}", 
                channel, subscribers.size());
    }
    
    /**
     * 处理 ACK 确认
     * 
     * @param sessionId 会话ID
     * @param seq 序列号
     */
    public void handleAck(String sessionId, long seq) {
        ConcurrentHashMap<Long, PendingMessage> sessionPending = pendingMessages.get(sessionId);
        
        if (sessionPending != null) {
            PendingMessage removed = sessionPending.remove(seq);
            if (removed != null) {
                messagesAcked.incrementAndGet();
                log.debug("[MessageDispatcher] ACK received, sessionId={}, seq={}", sessionId, seq);
            }
        }
        
        // 更新会话的 ACK 序列号
        SessionMetadata metadata = sessionManager.getSession(sessionId);
        if (metadata != null) {
            metadata.updateAck(seq);
        }
    }
    
    /**
     * 添加待确认消息
     */
    private void addPendingMessage(String sessionId, long seq, String message) {
        ConcurrentHashMap<Long, PendingMessage> sessionPending = 
                pendingMessages.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        
        sessionPending.put(seq, new PendingMessage(seq, message, System.currentTimeMillis(), 0));
    }
    
    /**
     * 检查待确认消息并重发（优化版：并行处理 + 早期退出）
     */
    private void checkPendingMessages() {
        long now = System.currentTimeMillis();
        long ackTimeoutMs = properties.getAck().getTimeoutMs();
        int maxRetry = properties.getAck().getMaxRetry();

        // 优化：空检查早期退出
        if (pendingMessages.isEmpty()) {
            return;
        }

        // 优化：并行处理（使用ForkJoinPool）
        pendingMessages.entrySet().parallelStream().forEach(entry -> {
            String sessionId = entry.getKey();
            ConcurrentHashMap<Long, PendingMessage> sessionPending = entry.getValue();

            SessionMetadata metadata = sessionManager.getSession(sessionId);
            if (metadata == null) {
                // 会话已关闭，清理待确认消息
                pendingMessages.remove(sessionId);
                return;
            }

            WebSocketSession session = metadata.getSession();
            if (session == null || !session.isOpen()) {
                pendingMessages.remove(sessionId);
                return;
            }

            // 优化：批量处理超时消息
            sessionPending.values().removeIf(pending -> {
                if (now - pending.sendTime <= ackTimeoutMs) {
                    return false; // 未超时，保留
                }

                if (pending.retryCount >= maxRetry) {
                    // 超过重试次数，移除消息并记录
                    log.warn("[MessageDispatcher] Message failed after max retries, sessionId={}, seq={}",
                            sessionId, pending.seq);
                    messagesDropped.incrementAndGet();

                    // 考虑断开连接，让客户端重连
                    try {
                        session.close();
                    } catch (Exception e) {
                        log.error("[MessageDispatcher] Error closing session: {}", sessionId);
                    }
                    return true; // 移除
                } else {
                    // 重发
                    try {
                        session.sendMessage(new TextMessage(pending.message));
                        pending.sendTime = now;
                        pending.retryCount++;

                        log.debug("[MessageDispatcher] Retrying message, sessionId={}, seq={}, retry={}",
                                sessionId, pending.seq, pending.retryCount);
                        return false; // 保留

                    } catch (IOException e) {
                        log.error("[MessageDispatcher] Retry failed, sessionId={}, seq={}",
                                sessionId, pending.seq);
                        return true; // 移除
                    }
                }
            });

            // 如果该会话没有待确认消息，清理
            if (sessionPending.isEmpty()) {
                pendingMessages.remove(sessionId);
            }
        });
    }
    
    /**
     * 获取分发统计
     */
    public DispatchStats getStats() {
        return DispatchStats.builder()
                .messagesSent(messagesSent.get())
                .messagesAcked(messagesAcked.get())
                .messagesDropped(messagesDropped.get())
                .pendingMessages(
                    pendingMessages.values().stream()
                            .mapToInt(Map::size)
                            .sum()
                )
                .build();
    }
    
    /**
     * 待确认消息
     */
    private static class PendingMessage {
        final long seq;
        final String message;
        volatile long sendTime;
        volatile int retryCount;
        
        PendingMessage(long seq, String message, long sendTime, int retryCount) {
            this.seq = seq;
            this.message = message;
            this.sendTime = sendTime;
            this.retryCount = retryCount;
        }
    }
    
    /**
     * 刷新批量发送队列
     */
    private void flushBatchQueues() {
        if (batchQueues.isEmpty()) {
            return;
        }

        batchQueues.forEach((sessionId, queue) -> {
            if (queue.isEmpty()) {
                return;
            }

            SessionMetadata metadata = sessionManager.getSession(sessionId);
            if (metadata == null) {
                batchQueues.remove(sessionId);
                return;
            }

            WebSocketSession session = metadata.getSession();
            if (session == null || !session.isOpen()) {
                batchQueues.remove(sessionId);
                return;
            }

            // 批量发送（最多100条）
            int batchSize = 0;
            StringBuilder batchMessage = new StringBuilder("[");

            BatchMessage msg;
            while ((msg = queue.poll()) != null && batchSize < 100) {
                if (batchSize > 0) {
                    batchMessage.append(",");
                }
                batchMessage.append(msg.json);
                batchSize++;

                // 如果需要ACK，加入待确认队列
                if (properties.getAck().isEnabled()) {
                    addPendingMessage(sessionId, msg.seq, msg.json);
                }
            }

            batchMessage.append("]");

            if (batchSize > 0) {
                try {
                    session.sendMessage(new TextMessage(batchMessage.toString()));
                    messagesSent.addAndGet(batchSize);
                    messagesBatched.incrementAndGet();

                    log.debug("[MessageDispatcher] Batched {} messages to session {}", batchSize, sessionId);

                } catch (IOException e) {
                    log.error("[MessageDispatcher] Failed to send batch message, sessionId={}", sessionId, e);
                    messagesDropped.addAndGet(batchSize);
                }
            }

            // 清空队列
            if (queue.isEmpty()) {
                batchQueues.remove(sessionId);
            }
        });
    }

    /**
     * 批量消息
     */
    private static class BatchMessage {
        final long seq;
        final String json;

        BatchMessage(long seq, String json) {
            this.seq = seq;
            this.json = json;
        }
    }

    /**
     * 分发统计
     */
    @lombok.Builder
    @lombok.Data
    public static class DispatchStats {
        private long messagesSent;
        private long messagesAcked;
        private long messagesDropped;
        private long messagesBatched;
        private int pendingMessages;
    }
}
