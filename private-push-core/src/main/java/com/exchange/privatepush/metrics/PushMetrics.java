package com.exchange.privatepush.metrics;

import com.exchange.privatepush.service.MessageDispatcher;
import com.exchange.privatepush.service.SessionManager;
import com.exchange.privatepush.service.SubscriptionManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 私有推送监控指标
 *
 * 🔥 核心职责：
 * 1. 暴露 Prometheus metrics
 * 2. 记录关键性能指标
 * 3. 提供可观测性
 *
 * 监控指标：
 * - 连接数、在线用户数、IP数
 * - 消息发送速率、ACK速率、丢弃速率
 * - 消息延迟P50/P99/P999
 * - 限流触发频率
 * - 待确认消息堆积数
 *
 * @author Exchange Team
 * @version 1.0.0
 */
@Slf4j
@Component
public class PushMetrics {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private MessageDispatcher messageDispatcher;

    @Autowired
    private SubscriptionManager subscriptionManager;

    // ==================== 计数器 ====================

    private Counter messagesSentCounter;
    private Counter messagesAckedCounter;
    private Counter messagesDroppedCounter;
    private Counter messagesBatchedCounter;
    private Counter connectionsCounter;
    private Counter disconnectionsCounter;
    private Counter rateLimitCounter;

    // ==================== 计时器 ====================

    private Timer messageSendTimer;
    private Timer messageAckTimer;

    // ==================== 自定义指标 ====================

    private final AtomicLong currentConnections = new AtomicLong(0);
    private final AtomicLong currentUsers = new AtomicLong(0);
    private final AtomicLong currentIps = new AtomicLong(0);
    private final AtomicLong pendingMessages = new AtomicLong(0);

    @PostConstruct
    public void init() {
        // 注册计数器
        messagesSentCounter = Counter.builder("private_push.messages.sent")
                .description("Total messages sent")
                .tag("type", "private")
                .register(meterRegistry);

        messagesAckedCounter = Counter.builder("private_push.messages.acked")
                .description("Total messages acknowledged")
                .tag("type", "private")
                .register(meterRegistry);

        messagesDroppedCounter = Counter.builder("private_push.messages.dropped")
                .description("Total messages dropped")
                .tag("type", "private")
                .register(meterRegistry);

        messagesBatchedCounter = Counter.builder("private_push.messages.batched")
                .description("Total batched sends")
                .tag("type", "private")
                .register(meterRegistry);

        connectionsCounter = Counter.builder("private_push.connections.total")
                .description("Total connections")
                .tag("type", "private")
                .register(meterRegistry);

        disconnectionsCounter = Counter.builder("private_push.disconnections.total")
                .description("Total disconnections")
                .tag("type", "private")
                .register(meterRegistry);

        rateLimitCounter = Counter.builder("private_push.rate_limit.total")
                .description("Total rate limit triggers")
                .tag("type", "private")
                .register(meterRegistry);

        // 注册计时器
        messageSendTimer = Timer.builder("private_push.message.send.latency")
                .description("Message send latency")
                .tag("type", "private")
                .register(meterRegistry);

        messageAckTimer = Timer.builder("private_push.message.ack.latency")
                .description("Message ACK latency")
                .tag("type", "private")
                .register(meterRegistry);

        // 注册Gauge
        Gauge.builder("private_push.connections.current", currentConnections, AtomicLong::get)
                .description("Current active connections")
                .tag("type", "private")
                .register(meterRegistry);

        Gauge.builder("private_push.users.current", currentUsers, AtomicLong::get)
                .description("Current online users")
                .tag("type", "private")
                .register(meterRegistry);

        Gauge.builder("private_push.ips.current", currentIps, AtomicLong::get)
                .description("Current unique IPs")
                .tag("type", "private")
                .register(meterRegistry);

        Gauge.builder("private_push.messages.pending", pendingMessages, AtomicLong::get)
                .description("Pending messages waiting for ACK")
                .tag("type", "private")
                .register(meterRegistry);

        // 注册系统状态Gauge
        Gauge.builder("private_push.stats.sessions", sessionManager, sm -> sm.getStats().getTotalSessions())
                .description("Total sessions")
                .tag("type", "private")
                .register(meterRegistry);

        Gauge.builder("private_push.stats.subscriptions", subscriptionManager, sm -> sm.getStats().getTotalSubscriptions())
                .description("Total subscriptions")
                .tag("type", "private")
                .register(meterRegistry);

        log.info("[PushMetrics] Initialized");
    }

    // ==================== 记录方法 ====================

    /**
     * 记录消息发送
     */
    public void recordMessageSent() {
        messagesSentCounter.increment();
    }

    /**
     * 记录消息ACK
     */
    public void recordMessageAcked() {
        messagesAckedCounter.increment();
    }

    /**
     * 记录消息丢弃
     */
    public void recordMessageDropped() {
        messagesDroppedCounter.increment();
    }

    /**
     * 记录批量发送
     */
    public void recordMessageBatched() {
        messagesBatchedCounter.increment();
    }

    /**
     * 记录连接建立
     */
    public void recordConnection() {
        connectionsCounter.increment();
        currentConnections.incrementAndGet();
    }

    /**
     * 记录连接断开
     */
    public void recordDisconnection() {
        disconnectionsCounter.increment();
        currentConnections.decrementAndGet();
    }

    /**
     * 记录限流
     */
    public void recordRateLimit() {
        rateLimitCounter.increment();
    }

    /**
     * 记录消息发送延迟
     */
    public void recordSendLatency(long startTime) {
        long latency = System.currentTimeMillis() - startTime;
        messageSendTimer.record(latency, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录消息ACK延迟
     */
    public void recordAckLatency(long startTime) {
        long latency = System.currentTimeMillis() - startTime;
        messageAckTimer.record(latency, TimeUnit.MILLISECONDS);
    }

    /**
     * 更新当前在线用户数
     */
    public void updateCurrentUsers(long count) {
        currentUsers.set(count);
    }

    /**
     * 更新当前IP数
     */
    public void updateCurrentIps(long count) {
        currentIps.set(count);
    }

    /**
     * 更新待确认消息数
     */
    public void updatePendingMessages(long count) {
        pendingMessages.set(count);
    }

    /**
     * 获取当前连接数
     */
    public long getCurrentConnections() {
        return currentConnections.get();
    }

    /**
     * 获取当前用户数
     */
    public long getCurrentUsers() {
        return currentUsers.get();
    }

    /**
     * 获取当前IP数
     */
    public long getCurrentIps() {
        return currentIps.get();
    }

    /**
     * 获取待确认消息数
     */
    public long getPendingMessages() {
        return pendingMessages.get();
    }
}
