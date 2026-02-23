package com.exchange.privatepush.service;

import com.exchange.privatepush.config.PrivatePushProperties;
import com.exchange.privatepush.model.SessionMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket 会话管理器
 * 
 * 🔥 核心职责：
 * 1. 管理所有用户会话 (SessionId -> SessionMetadata)
 * 2. 管理用户与会话的映射 (UserId -> Set<SessionId>)
 * 3. IP连接数限流
 * 4. 心跳检测与超时清理
 * 5. 会话统计
 * 
 * 线程安全：所有操作使用 ConcurrentHashMap
 * 
 * @author Exchange Team
 * @version 1.0.0
 */
@Slf4j
@Service
public class SessionManager {
    
    @Autowired
    private PrivatePushProperties properties;
    
    // SessionId -> SessionMetadata
    private final ConcurrentHashMap<String, SessionMetadata> sessions = new ConcurrentHashMap<>();
    
    // UserId -> Set<SessionId>
    private final ConcurrentHashMap<Long, Set<String>> userSessions = new ConcurrentHashMap<>();
    
    // IP -> 连接数
    private final ConcurrentHashMap<String, AtomicInteger> ipConnectionCounts = new ConcurrentHashMap<>();
    
    // 心跳检测定时任务
    private ScheduledExecutorService heartbeatExecutor;
    
    @PostConstruct
    public void init() {
        // 启动心跳检测定时任务
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-checker");
            t.setDaemon(true);
            return t;
        });

        long heartbeatTimeoutMs = properties.getConnection().getHeartbeatTimeout() * 1000L;

        heartbeatExecutor.scheduleAtFixedRate(
            () -> checkHeartbeatTimeout(heartbeatTimeoutMs),
            30,
            30,
            TimeUnit.SECONDS
        );

        // 启动内存监控定时任务
        heartbeatExecutor.scheduleAtFixedRate(
            this::monitorMemoryUsage,
            60,
            60,
            TimeUnit.SECONDS
        );

        log.info("[SessionManager] Initialized, heartbeatTimeout={}ms", heartbeatTimeoutMs);
    }

    /**
     * 监控内存使用情况
     */
    private void monitorMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        double usagePercent = (double) usedMemory / maxMemory * 100;

        log.info("[SessionManager] Memory usage: {}MB / {}MB ({:.2f}%), sessions={}, users={}, ips={}",
                usedMemory / 1024 / 1024,
                maxMemory / 1024 / 1024,
                usagePercent,
                sessions.size(),
                userSessions.size(),
                ipConnectionCounts.size());

        // 内存使用超过80%时告警
        if (usagePercent > 80) {
            log.warn("[SessionManager] High memory usage: {:.2f}%, consider scaling up", usagePercent);
        }
    }
    
    @PreDestroy
    public void destroy() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
        }
        
        // 关闭所有会话
        sessions.values().forEach(metadata -> {
            try {
                metadata.getSession().close();
            } catch (Exception e) {
                log.warn("[SessionManager] Error closing session: {}", metadata.getSessionId());
            }
        });
        
        log.info("[SessionManager] Destroyed");
    }
    
    /**
     * 注册会话
     * 
     * @param metadata 会话元数据
     * @return 是否注册成功 (false = 超限)
     */
    public boolean registerSession(SessionMetadata metadata) {
        String sessionId = metadata.getSessionId();
        Long userId = metadata.getUserId();
        String clientIp = metadata.getClientIp();
        
        // 检查用户连接数限制
        int maxPerUser = properties.getConnection().getMaxPerUser();
        Set<String> userSessionSet = userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet());
        
        synchronized (userSessionSet) {
            if (userSessionSet.size() >= maxPerUser) {
                log.warn("[SessionManager] User session limit exceeded, userId={}, limit={}", 
                        userId, maxPerUser);
                return false;
            }
            userSessionSet.add(sessionId);
        }
        
        // 检查IP连接数限制
        int maxPerIp = properties.getConnection().getMaxPerIp();
        AtomicInteger ipCount = ipConnectionCounts.computeIfAbsent(clientIp, k -> new AtomicInteger(0));
        
        if (ipCount.incrementAndGet() > maxPerIp) {
            ipCount.decrementAndGet();
            log.warn("[SessionManager] IP connection limit exceeded, ip={}, limit={}", 
                    clientIp, maxPerIp);
            // 回滚用户会话
            userSessionSet.remove(sessionId);
            return false;
        }
        
        // 保存会话
        sessions.put(sessionId, metadata);
        
        log.info("[SessionManager] Session registered, sessionId={}, userId={}, ip={}, totalSessions={}",
                sessionId, userId, clientIp, sessions.size());
        
        return true;
    }
    
    /**
     * 注销会话
     */
    public void unregisterSession(String sessionId) {
        SessionMetadata metadata = sessions.remove(sessionId);
        if (metadata == null) {
            return;
        }
        
        Long userId = metadata.getUserId();
        String clientIp = metadata.getClientIp();
        
        // 从用户会话集合移除
        Set<String> userSessionSet = userSessions.get(userId);
        if (userSessionSet != null) {
            userSessionSet.remove(sessionId);
            if (userSessionSet.isEmpty()) {
                userSessions.remove(userId);
            }
        }
        
        // 减少IP连接计数
        AtomicInteger ipCount = ipConnectionCounts.get(clientIp);
        if (ipCount != null) {
            if (ipCount.decrementAndGet() <= 0) {
                ipConnectionCounts.remove(clientIp);
            }
        }
        
        // 关闭会话
        try {
            WebSocketSession session = metadata.getSession();
            if (session.isOpen()) {
                session.close();
            }
        } catch (Exception e) {
            log.warn("[SessionManager] Error closing session: {}", sessionId);
        }
        
        log.info("[SessionManager] Session unregistered, sessionId={}, userId={}, totalSessions={}",
                sessionId, userId, sessions.size());
    }
    
    /**
     * 根据 SessionId 获取会话
     */
    public SessionMetadata getSession(String sessionId) {
        return sessions.get(sessionId);
    }
    
    /**
     * 根据 UserId 获取所有会话ID
     */
    public Set<String> getUserSessionIds(Long userId) {
        return userSessions.getOrDefault(userId, ConcurrentHashMap.newKeySet());
    }
    
    /**
     * 获取用户的所有会话
     */
    public Map<String, SessionMetadata> getUserSessions(Long userId) {
        Map<String, SessionMetadata> result = new ConcurrentHashMap<>();
        Set<String> sessionIds = userSessions.get(userId);
        
        if (sessionIds != null) {
            for (String sessionId : sessionIds) {
                SessionMetadata metadata = sessions.get(sessionId);
                if (metadata != null) {
                    result.put(sessionId, metadata);
                }
            }
        }
        
        return result;
    }
    
    /**
     * 检查用户是否有活跃会话
     */
    public boolean hasActiveSession(Long userId) {
        Set<String> sessionIds = userSessions.get(userId);
        return sessionIds != null && !sessionIds.isEmpty();
    }
    
    /**
     * 获取会话统计
     */
    public SessionStats getStats() {
        return SessionStats.builder()
                .totalSessions(sessions.size())
                .totalUsers(userSessions.size())
                .totalIps(ipConnectionCounts.size())
                .build();
    }
    
    /**
     * 检查心跳超时
     */
    private void checkHeartbeatTimeout(long timeoutMs) {
        long now = System.currentTimeMillis();
        int closedCount = 0;
        
        for (SessionMetadata metadata : sessions.values()) {
            if (metadata.isTimeout(timeoutMs)) {
                log.warn("[SessionManager] Session heartbeat timeout, sessionId={}, userId={}",
                        metadata.getSessionId(), metadata.getUserId());
                unregisterSession(metadata.getSessionId());
                closedCount++;
            }
        }
        
        if (closedCount > 0) {
            log.info("[SessionManager] Closed {} timeout sessions", closedCount);
        }
    }
    
    /**
     * 会话统计信息
     */
    @lombok.Builder
    @lombok.Data
    public static class SessionStats {
        private int totalSessions;
        private int totalUsers;
        private int totalIps;
    }
}
