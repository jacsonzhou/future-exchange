package com.exchange.push.service;

import com.exchange.push.config.PublicPushProperties;
import com.exchange.push.model.ConnectionMetadata;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket 连接管理器（WebFlux / Reactor Netty 版本）
 *
 * 职责：
 * - 管理所有客户端连接（通过 Sinks.Many 进行异步推送）
 * - IP 限流与连接数控制
 * - 心跳检测与僵尸连接清理
 * - 连接统计与监控
 *
 * 架构变更：
 * - 不再存储 WebSocketSession 对象（WebFlux session 由框架管理生命周期）
 * - 使用 Sinks.Many<String> 作为每个 session 的出站消息队列
 * - 外部线程（Kafka Consumer / 定时任务）通过 sink 推送消息
 */
@Slf4j
@Service
public class ConnectionManager {
    private static final String HEARTBEAT_TIMEOUT_REASON = "Heartbeat timeout";

    @Autowired
    private PublicPushProperties properties;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    @Lazy
    private SubscriptionManager subscriptionManager;

    // sessionId -> SessionContext
    @Getter
    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();

    // 连接元数据 sessionId -> ConnectionMetadata
    private final Map<String, ConnectionMetadata> metadataMap = new ConcurrentHashMap<>();

    // IP -> 连接数计数器
    private final Map<String, AtomicInteger> ipConnectionCounts = new ConcurrentHashMap<>();

    // IP -> 连接建立时间（用于速率限制）
    private final Map<String, Long> ipLastConnectionTime = new ConcurrentHashMap<>();

    /**
     * Session 上下文：包含该连接的所有运行时状态
     */
    @Data
    public static class SessionContext {
        private final String sessionId;
        private final String clientIp;
        private final Sinks.Many<String> sink;
        private final ConnectionMetadata metadata;
    }

    @PostConstruct
    public void init() {
        Gauge.builder("websocket.connections.active", sessions, Map::size)
            .description("Number of active WebSocket connections")
            .register(meterRegistry);

        Gauge.builder("websocket.connections.total", this,
                cm -> cm.metadataMap.values().stream()
                    .mapToLong(ConnectionMetadata::getConnectTime)
                    .count())
            .description("Total number of WebSocket connections")
            .register(meterRegistry);
    }

    /**
     * 注册新连接，返回 sessionId；若限流未通过则返回 null
     */
    public String registerConnection(String clientIp) {
        if (!checkIpConnectionLimit(clientIp)) {
            log.warn("[Connection] IP {} exceeded connection limit", clientIp);
            return null;
        }

        if (!checkConnectionRateLimit(clientIp)) {
            log.warn("[Connection] IP {} exceeded connection rate limit", clientIp);
            return null;
        }

        String sessionId = generateSessionId();
        int queueSize = properties.getPush().getBatchQueueSize();
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        ConnectionMetadata metadata = new ConnectionMetadata(sessionId, clientIp);
        SessionContext ctx = new SessionContext(sessionId, clientIp, sink, metadata);

        sessions.put(sessionId, ctx);
        metadataMap.put(sessionId, metadata);
        ipConnectionCounts.computeIfAbsent(clientIp, k -> new AtomicInteger(0)).incrementAndGet();

        log.info("[Connection] New connection established: {} from {}, total: {}",
                sessionId, clientIp, sessions.size());

        return sessionId;
    }

    /**
     * 移除连接（关闭 sink、清理索引、取消订阅）
     */
    public void removeConnection(String sessionId) {
        SessionContext ctx = sessions.remove(sessionId);
        ConnectionMetadata metadata = metadataMap.remove(sessionId);

        if (ctx != null) {
            // 优雅关闭 sink，触发 outbound flux complete
            ctx.getSink().tryEmitComplete();

            AtomicInteger count = ipConnectionCounts.get(ctx.getClientIp());
            if (count != null) {
                count.decrementAndGet();
            }
        }

        if (metadata != null) {
            subscriptionManager.unsubscribeAll(sessionId);

            log.info("[Connection] Connection removed: {} from {}, total: {}",
                    sessionId, metadata.getClientIp(), sessions.size());
        }
    }

    public SessionContext getSessionContext(String sessionId) {
        return sessions.get(sessionId);
    }

    public Sinks.Many<String> getSink(String sessionId) {
        SessionContext ctx = sessions.get(sessionId);
        return ctx != null ? ctx.getSink() : null;
    }

    public ConnectionMetadata getMetadata(String sessionId) {
        return metadataMap.get(sessionId);
    }

    public boolean exists(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    public void updatePing(String sessionId, long timestamp) {
        ConnectionMetadata metadata = metadataMap.get(sessionId);
        if (metadata != null) {
            metadata.updatePing(timestamp);
        }
    }

    public void updatePong(String sessionId) {
        ConnectionMetadata metadata = metadataMap.get(sessionId);
        if (metadata != null) {
            metadata.updatePong();
        }
    }

    public void markActivity(String sessionId) {
        ConnectionMetadata metadata = metadataMap.get(sessionId);
        if (metadata != null) {
            metadata.markActivity();
        }
    }

    private boolean checkIpConnectionLimit(String clientIp) {
        if (!properties.getRateLimit().getIpConnection().isEnabled()) {
            return true;
        }

        int maxConnections = properties.getRateLimit().getIpConnection().getMaxConnections();
        AtomicInteger count = ipConnectionCounts.get(clientIp);

        return count == null || count.get() < maxConnections;
    }

    private boolean checkConnectionRateLimit(String clientIp) {
        if (!properties.getRateLimit().getIpConnection().isEnabled()) {
            return true;
        }

        if (isLoopbackIp(clientIp)) {
            return true;
        }

        int rateLimit = properties.getRateLimit().getIpConnection().getRatePerMinute();
        long now = System.currentTimeMillis();
        Long lastTime = ipLastConnectionTime.get(clientIp);

        if (lastTime != null && now - lastTime < 60000 / rateLimit) {
            return false;
        }

        ipLastConnectionTime.put(clientIp, now);
        return true;
    }

    private boolean isLoopbackIp(String clientIp) {
        return "127.0.0.1".equals(clientIp)
            || "::1".equals(clientIp)
            || "0:0:0:0:0:0:0:1".equals(clientIp);
    }

    private String generateSessionId() {
        return "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @Scheduled(fixedRate = 30000)
    public void cleanupZombieConnections() {
        long timeoutMs = properties.getWebsocket().getHeartbeatIntervalMs()
                       + properties.getWebsocket().getHeartbeatTimeoutMs();

        Set<String> zombieSessions = ConcurrentHashMap.newKeySet();

        metadataMap.forEach((sessionId, metadata) -> {
            if (metadata.isZombie(timeoutMs)) {
                long now = System.currentTimeMillis();
                log.warn("[Connection] Zombie connection detected: {}, lastPong: {}ms ago, lastActivity: {}ms ago",
                        sessionId,
                        now - metadata.getLastPongTime(),
                        now - metadata.getLastActivityTime());
                zombieSessions.add(sessionId);
            }
        });

        zombieSessions.forEach(sessionId -> {
            SessionContext ctx = sessions.get(sessionId);
            if (ctx != null) {
                ctx.getSink().tryEmitComplete();
            }
            removeConnection(sessionId);
        });

        if (!zombieSessions.isEmpty()) {
            log.info("[Connection] Cleaned up {} zombie connections", zombieSessions.size());
        }
    }

    @Scheduled(fixedRate = 1000)
    public void resetSecondCounters() {
        metadataMap.values().forEach(ConnectionMetadata::resetSecondCounter);
    }

    @Scheduled(fixedRate = 60000)
    public void resetMinuteCounters() {
        metadataMap.values().forEach(ConnectionMetadata::resetMinuteCounter);
    }

    public Map<String, Object> getConnectionStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("activeConnections", sessions.size());
        stats.put("uniqueIps", ipConnectionCounts.size());

        long totalMessagesSent = metadataMap.values().stream()
                .mapToLong(m -> m.getMessagesSent().get())
                .sum();
        stats.put("totalMessagesSent", totalMessagesSent);

        return stats;
    }
}
