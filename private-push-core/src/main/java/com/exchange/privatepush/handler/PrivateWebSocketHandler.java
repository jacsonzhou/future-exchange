package com.exchange.privatepush.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.privatepush.config.PrivatePushProperties;
import com.exchange.privatepush.model.SessionMetadata;
import com.exchange.privatepush.service.SessionManager;
import com.exchange.privatepush.service.SubscriptionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 私有推送 WebSocket 处理器
 * 
 * 🔥 核心职责：
 * 1. 处理 WebSocket 连接建立和关闭
 * 2. 用户认证 (JWT Token)
 * 3. 处理客户端消息 (订阅、ACK、心跳)
 * 4. 异常处理
 * 
 * 消息协议：
 * - SUBSCRIBE: 订阅频道
 * - UNSUBSCRIBE: 取消订阅
 * - ACK: 确认消息
 * - PING: 心跳
 * 
 * @author Exchange Team
 * @version 1.0.0
 */
@Slf4j
@Component
public class PrivateWebSocketHandler extends TextWebSocketHandler {
    
    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private SubscriptionManager subscriptionManager;

    @Autowired
    private PrivatePushProperties properties;

    @Autowired
    private com.exchange.privatepush.security.JwtTokenProvider jwtTokenProvider;
    
    /**
     * 连接建立
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        log.info("[WebSocket] Connection established, sessionId={}", sessionId);
        
        try {
            // 从 Header 获取 JWT Token
            String token = extractToken(session);
            if (token == null) {
                log.warn("[WebSocket] No token provided, sessionId={}", sessionId);
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            
            // 验证 Token 并获取 userId
            Long userId = validateToken(token);
            if (userId == null) {
                log.warn("[WebSocket] Invalid token, sessionId={}", sessionId);
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            
            // 创建会话
            String clientIp = extractClientIp(session);
            SessionMetadata metadata = new SessionMetadata(sessionId, session, userId, clientIp);
            
            // 注册会话
            if (!sessionManager.registerSession(metadata)) {
                log.warn("[WebSocket] Session limit exceeded, userId={}, sessionId={}", userId, sessionId);
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            
            // 发送连接确认
            sendConnectionAck(session, userId);
            
            log.info("[WebSocket] Session registered, userId={}, sessionId={}, ip={}", 
                    userId, sessionId, clientIp);
                    
        } catch (Exception e) {
            log.error("[WebSocket] Failed to establish connection, sessionId={}", sessionId, e);
            session.close(CloseStatus.SERVER_ERROR);
        }
    }
    
    /**
     * 处理文本消息
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = session.getId();
        String payload = message.getPayload();
        
        log.debug("[WebSocket] Received message, sessionId={}, payload={}", sessionId, payload);
        
        try {
            JSONObject json = JSON.parseObject(payload);
            String method = json.getString("method");
            
            if (method == null) {
                sendError(session, "Missing method field");
                return;
            }
            
            switch (method) {
                case "SUBSCRIBE":
                    handleSubscribe(session, json);
                    break;
                case "UNSUBSCRIBE":
                    handleUnsubscribe(session, json);
                    break;
                case "ACK":
                    handleAck(session, json);
                    break;
                case "PING":
                    handlePing(session, json);
                    break;
                case "LIST_SUBSCRIPTIONS":
                    handleListSubscriptions(session, json);
                    break;
                default:
                    sendError(session, "Unknown method: " + method);
            }
            
        } catch (Exception e) {
            log.error("[WebSocket] Failed to handle message, sessionId={}", sessionId, e);
            sendError(session, "Invalid message format");
        }
    }
    
    /**
     * 连接关闭
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        log.info("[WebSocket] Connection closed, sessionId={}, status={}", sessionId, status);
        
        sessionManager.unregisterSession(sessionId);
    }
    
    /**
     * 传输错误
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String sessionId = session.getId();
        log.error("[WebSocket] Transport error, sessionId={}", sessionId, exception);
        
        sessionManager.unregisterSession(sessionId);
        session.close(CloseStatus.SERVER_ERROR);
    }
    
    // ==================== 消息处理 ====================
    
    /**
     * 处理订阅请求
     */
    private void handleSubscribe(WebSocketSession session, JSONObject json) {
        String sessionId = session.getId();
        SessionMetadata metadata = sessionManager.getSession(sessionId);
        
        if (metadata == null) {
            sendError(session, "Session not found");
            return;
        }
        
        // 更新心跳
        metadata.updateHeartbeat();
        
        // 获取订阅频道
        var params = json.getJSONArray("params");
        if (params == null || params.isEmpty()) {
            sendError(session, "Missing params");
            return;
        }
        
        for (int i = 0; i < params.size(); i++) {
            String channel = params.getString(i);
            
            // 验证频道
            if (!isValidChannel(channel)) {
                sendError(session, "Invalid channel: " + channel);
                continue;
            }
            
            // 添加订阅
            if (subscriptionManager.subscribe(sessionId, channel)) {
                metadata.subscribe(channel);
                log.debug("[WebSocket] Subscribed, sessionId={}, channel={}", sessionId, channel);
            }
        }
        
        // 发送确认
        sendResponse(session, json.getInteger("id"), null);
    }
    
    /**
     * 处理取消订阅请求
     */
    private void handleUnsubscribe(WebSocketSession session, JSONObject json) {
        String sessionId = session.getId();
        SessionMetadata metadata = sessionManager.getSession(sessionId);
        
        if (metadata == null) {
            sendError(session, "Session not found");
            return;
        }
        
        // 更新心跳
        metadata.updateHeartbeat();
        
        var params = json.getJSONArray("params");
        if (params == null || params.isEmpty()) {
            sendError(session, "Missing params");
            return;
        }
        
        for (int i = 0; i < params.size(); i++) {
            String channel = params.getString(i);
            
            if (subscriptionManager.unsubscribe(sessionId, channel)) {
                metadata.unsubscribe(channel);
                log.debug("[WebSocket] Unsubscribed, sessionId={}, channel={}", sessionId, channel);
            }
        }
        
        sendResponse(session, json.getInteger("id"), null);
    }
    
    /**
     * 处理ACK确认
     */
    private void handleAck(WebSocketSession session, JSONObject json) {
        String sessionId = session.getId();
        SessionMetadata metadata = sessionManager.getSession(sessionId);
        
        if (metadata == null) {
            return;
        }
        
        Long seq = json.getLong("seq");
        if (seq != null) {
            metadata.updateAck(seq);
            log.debug("[WebSocket] ACK received, sessionId={}, seq={}", sessionId, seq);
        }
    }
    
    /**
     * 处理心跳
     */
    private void handlePing(WebSocketSession session, JSONObject json) {
        String sessionId = session.getId();
        SessionMetadata metadata = sessionManager.getSession(sessionId);
        
        if (metadata != null) {
            metadata.updateHeartbeat();
        }
        
        // 回复 PONG
        JSONObject pong = new JSONObject();
        pong.put("method", "PONG");
        pong.put("id", json.getInteger("id"));
        pong.put("E", System.currentTimeMillis());
        
        sendMessage(session, pong);
    }
    
    /**
     * 处理查询订阅列表
     */
    private void handleListSubscriptions(WebSocketSession session, JSONObject json) {
        String sessionId = session.getId();
        SessionMetadata metadata = sessionManager.getSession(sessionId);
        
        if (metadata == null) {
            sendError(session, "Session not found");
            return;
        }
        
        metadata.updateHeartbeat();
        
        JSONObject result = new JSONObject();
        result.put("subscriptions", metadata.getSubscriptions());
        
        sendResponse(session, json.getInteger("id"), result);
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 从 Session 提取 Token
     */
    private String extractToken(WebSocketSession session) {
        // 从 Header 提取
        var headers = session.getHandshakeHeaders();
        String auth = headers.getFirst("Authorization");
        String headerToken = normalizeToken(auth);
        if (headerToken != null) {
            return headerToken;
        }
        
        // 从 Query 参数提取
        String query = session.getUri().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    String queryToken = normalizeToken(param.substring(6));
                    if (queryToken != null) {
                        return queryToken;
                    }
                }
            }
        }
        
        return null;
    }

    private String normalizeToken(String token) {
        if (token == null) {
            return null;
        }

        String normalized = URLDecoder.decode(token, StandardCharsets.UTF_8).trim();

        if (normalized.startsWith("Bearer ")) {
            normalized = normalized.substring(7).trim();
        }

        if ((normalized.startsWith("\"") && normalized.endsWith("\"")) ||
                (normalized.startsWith("'") && normalized.endsWith("'"))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }

        if (normalized.isEmpty() ||
                "undefined".equalsIgnoreCase(normalized) ||
                "null".equalsIgnoreCase(normalized)) {
            return null;
        }

        return normalized;
    }
    
    /**
     * 验证 Token 获取 userId（使用JWT验证）
     *
     * @param token JWT Token
     * @return userId，验证失败返回null
     */
    private Long validateToken(String token) {
        return jwtTokenProvider.validateToken(token);
    }
    
    /**
     * 提取客户端IP
     */
    private String extractClientIp(WebSocketSession session) {
        var headers = session.getHandshakeHeaders();
        String ip = headers.getFirst("X-Forwarded-For");
        if (ip == null) {
            ip = headers.getFirst("X-Real-IP");
        }
        if (ip == null) {
            ip = session.getRemoteAddress() != null ? 
                    session.getRemoteAddress().getAddress().getHostAddress() : "unknown";
        }
        return ip.split(",")[0].trim();
    }
    
    /**
     * 验证频道
     */
    private boolean isValidChannel(String channel) {
        return channel.equals("executionReport") ||
               channel.equals("account") ||
               channel.equals("position") ||
               channel.equals("balance") ||
               channel.equals("fundingFee");
    }
    
    /**
     * 发送连接确认
     */
    private void sendConnectionAck(WebSocketSession session, Long userId) {
        JSONObject ack = new JSONObject();
        ack.put("e", "connectionAck");
        ack.put("E", System.currentTimeMillis());
        ack.put("userId", userId);
        ack.put("sessionId", session.getId());
        
        sendMessage(session, ack);
    }
    
    /**
     * 发送响应
     */
    private void sendResponse(WebSocketSession session, Integer id, Object result) {
        JSONObject response = new JSONObject();
        response.put("result", result);
        response.put("id", id);
        
        sendMessage(session, response);
    }
    
    /**
     * 发送错误
     */
    private void sendError(WebSocketSession session, String message) {
        JSONObject error = new JSONObject();
        error.put("error", message);
        
        sendMessage(session, error);
    }
    
    /**
     * 发送消息
     */
    private void sendMessage(WebSocketSession session, JSONObject message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message.toJSONString()));
            }
        } catch (Exception e) {
            log.error("[WebSocket] Failed to send message, sessionId={}", session.getId(), e);
        }
    }
}
