package com.exchange.push.service;

import com.exchange.push.config.PublicPushProperties;
import com.exchange.push.model.ConnectionMetadata;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket连接管理器
 * 
 * 职责：
 * - 管理所有客户端连接
 * - IP限流与连接数控制
 * - 心跳检测与僵尸连接清理
 * - 连接统计与监控
 */
@Slf4j
@Service
public class ConnectionManager {

    @Autowired
    private PublicPushProperties properties;
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    @Autowired
    @Lazy
    private SubscriptionManager subscriptionManager;

    // 所有活跃连接 sessionId -> WebSocketSession
    @Getter
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    
    // 连接元数据 sessionId -> ConnectionMetadata
    private final Map<String, ConnectionMetadata> metadataMap = new ConcurrentHashMap<>();
    
    // IP -> 连接数计数器
    private final Map<String, AtomicInteger> ipConnectionCounts = new ConcurrentHashMap<>();
    
    // IP -> 连接建立时间（用于速率限制）
    private final Map<String, Long> ipLastConnectionTime = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 注册监控指标
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
     * 注册新连接
     */
    public boolean registerConnection(WebSocketSession session) {
        String clientIp = getClientIp(session);
        
        // IP限流检查
        if (!checkIpConnectionLimit(clientIp)) {
            log.warn("[Connection] IP {} exceeded connection limit", clientIp);
            return false;
        }
        
        // 速率限制检查
        if (!checkConnectionRateLimit(clientIp)) {
            log.warn("[Connection] IP {} exceeded connection rate limit", clientIp);
            return false;
        }
        
        // 生成sessionId
        String sessionId = generateSessionId();
        session.getAttributes().put("sessionId", sessionId);
        session.getAttributes().put("clientIp", clientIp);
        
        // 存储连接
        sessions.put(sessionId, session);
        metadataMap.put(sessionId, new ConnectionMetadata(sessionId, clientIp));
        ipConnectionCounts.computeIfAbsent(clientIp, k -> new AtomicInteger(0)).incrementAndGet();
        
        log.info("[Connection] New connection established: {} from {}, total: {}", 
                sessionId, clientIp, sessions.size());
        
        return true;
    }

    /**
     * 关闭连接
     */
    public void closeConnection(String sessionId, CloseStatus status) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            try {
                session.close(status);
            } catch (IOException e) {
                log.error("[Connection] Failed to close session {}: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * 移除连接
     */
    public void removeConnection(String sessionId) {
        WebSocketSession session = sessions.remove(sessionId);
        ConnectionMetadata metadata = metadataMap.remove(sessionId);
        
        if (metadata != null) {
            // 减少IP计数
            AtomicInteger count = ipConnectionCounts.get(metadata.getClientIp());
            if (count != null) {
                count.decrementAndGet();
            }
            
            // 取消所有订阅
            subscriptionManager.unsubscribeAll(sessionId);
            
            log.info("[Connection] Connection removed: {} from {}, total: {}", 
                    sessionId, metadata.getClientIp(), sessions.size());
        }
    }

    /**
     * 获取连接元数据
     */
    public ConnectionMetadata getMetadata(String sessionId) {
        return metadataMap.get(sessionId);
    }

    /**
     * 获取session
     */
    public WebSocketSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 检查session是否存在
     */
    public boolean exists(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * 更新心跳
     */
    public void updatePing(String sessionId, long timestamp) {
        ConnectionMetadata metadata = metadataMap.get(sessionId);
        if (metadata != null) {
            metadata.updatePing(timestamp);
        }
    }

    /**
     * 更新Pong
     */
    public void updatePong(String sessionId) {
        ConnectionMetadata metadata = metadataMap.get(sessionId);
        if (metadata != null) {
            metadata.updatePong();
        }
    }

    /**
     * 获取客户端IP
     */
    private String getClientIp(WebSocketSession session) {
        // 尝试从header获取X-Forwarded-For
        Object ip = session.getAttributes().get("clientIp");
        if (ip != null) {
            return ip.toString();
        }
        
        // 从session获取远程地址
        if (session.getRemoteAddress() != null) {
            return session.getRemoteAddress().getAddress().getHostAddress();
        }
        
        return "unknown";
    }

    /**
     * 检查IP连接数限制
     */
    private boolean checkIpConnectionLimit(String clientIp) {
        if (!properties.getRateLimit().getIpConnection().isEnabled()) {
            return true;
        }
        
        int maxConnections = properties.getRateLimit().getIpConnection().getMaxConnections();
        AtomicInteger count = ipConnectionCounts.get(clientIp);
        
        return count == null || count.get() < maxConnections;
    }

    /**
     * 检查连接速率限制
     */
    private boolean checkConnectionRateLimit(String clientIp) {
        if (!properties.getRateLimit().getIpConnection().isEnabled()) {
            return true;
        }
        
        int rateLimit = properties.getRateLimit().getIpConnection().getRatePerMinute();
        long now = System.currentTimeMillis();
        Long lastTime = ipLastConnectionTime.get(clientIp);
        
        // 简单的速率限制：每分钟最多N次
        if (lastTime != null && now - lastTime < 60000 / rateLimit) {
            return false;
        }
        
        ipLastConnectionTime.put(clientIp, now);
        return true;
    }

    /**
     * 生成sessionId
     */
    private String generateSessionId() {
        return "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 清理僵尸连接（定时任务）
     */
    @Scheduled(fixedRate = 30000)
    public void cleanupZombieConnections() {
        long timeoutMs = properties.getWebsocket().getHeartbeatIntervalMs() 
                       + properties.getWebsocket().getHeartbeatTimeoutMs();
        
        Set<String> zombieSessions = ConcurrentHashMap.newKeySet();
        
        metadataMap.forEach((sessionId, metadata) -> {
            if (metadata.isZombie(timeoutMs)) {
                log.warn("[Connection] Zombie connection detected: {}, lastPong: {}ms ago", 
                        sessionId, System.currentTimeMillis() - metadata.getLastPongTime());
                zombieSessions.add(sessionId);
            }
        });
        
        zombieSessions.forEach(sessionId -> {
            closeConnection(sessionId, CloseStatus.SESSION_NOT_RELIABLE);
            removeConnection(sessionId);
        });
        
        if (!zombieSessions.isEmpty()) {
            log.info("[Connection] Cleaned up {} zombie connections", zombieSessions.size());
        }
    }

    /**
     * 重置限流计数器（每秒）
     */
    @Scheduled(fixedRate = 1000)
    public void resetSecondCounters() {
        metadataMap.values().forEach(ConnectionMetadata::resetSecondCounter);
    }

    /**
     * 重置限流计数器（每分钟）
     */
    @Scheduled(fixedRate = 60000)
    public void resetMinuteCounters() {
        metadataMap.values().forEach(ConnectionMetadata::resetMinuteCounter);
    }

    /**
     * 获取连接统计
     */
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
