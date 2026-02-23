package com.exchange.push.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.push.model.SubscribeResult;
import com.exchange.push.service.ConnectionManager;
import com.exchange.push.service.SubscriptionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * 公有推送WebSocket处理器
 * 
 * 协议：
 * - 订阅: {"method": "SUBSCRIBE", "params": ["trade.BTCUSDT", "depth.BTCUSDT@100ms"], "id": 1}
 * - 取消订阅: {"method": "UNSUBSCRIBE", "params": ["depth.BTCUSDT@100ms"], "id": 2}
 * - 心跳: {"ping": 1704067200123}
 * - 心跳响应: {"pong": 1704067200123}
 */
@Slf4j
@Component
public class PublicWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private ConnectionManager connectionManager;
    
    @Autowired
    private SubscriptionManager subscriptionManager;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 注册连接
        boolean registered = connectionManager.registerConnection(session);
        
        if (!registered) {
            // 限流拒绝
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        
        // 发送连接成功确认
        JSONObject response = new JSONObject();
        response.put("result", "connected");
        response.put("serverTime", System.currentTimeMillis());
        sendMessage(session, response);
        
        log.info("[WebSocket] Connection established: {}, total connections: {}", 
                getSessionId(session), connectionManager.getSessions().size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = getSessionId(session);
        
        // 移除连接
        connectionManager.removeConnection(sessionId);
        
        log.info("[WebSocket] Connection closed: {}, status: {}, total connections: {}", 
                sessionId, status, connectionManager.getSessions().size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        String sessionId = getSessionId(session);
        
        log.debug("[WebSocket] Received message from {}: {}", sessionId, payload);
        
        try {
            JSONObject json = JSON.parseObject(payload);
            
            // 处理心跳
            if (json.containsKey("ping")) {
                handlePing(session, json);
                return;
            }
            
            // 处理方法调用
            String method = json.getString("method");
            int id = json.getIntValue("id", 0);
            
            if ("SUBSCRIBE".equals(method)) {
                handleSubscribe(session, json, id);
            } else if ("UNSUBSCRIBE".equals(method)) {
                handleUnsubscribe(session, json, id);
            } else if ("LIST_SUBSCRIPTIONS".equals(method)) {
                handleListSubscriptions(session, id);
            } else {
                sendError(session, id, "Unknown method: " + method);
            }
            
        } catch (Exception e) {
            log.error("[WebSocket] Failed to handle message from {}: {}", sessionId, e.getMessage());
            sendError(session, 0, "Invalid message format: " + e.getMessage());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String sessionId = getSessionId(session);
        log.error("[WebSocket] Transport error for {}: {}", sessionId, exception.getMessage());
        connectionManager.removeConnection(sessionId);
    }

    /**
     * 处理心跳
     */
    private void handlePing(WebSocketSession session, JSONObject json) {
        long pingTime = json.getLongValue("ping");
        String sessionId = getSessionId(session);
        
        // 更新心跳时间
        connectionManager.updatePing(sessionId, pingTime);
        connectionManager.updatePong(sessionId);
        
        // 响应pong
        JSONObject pong = new JSONObject();
        pong.put("pong", pingTime);
        sendMessage(session, pong);
    }

    /**
     * 处理订阅
     */
    private void handleSubscribe(WebSocketSession session, JSONObject json, int id) {
        String sessionId = getSessionId(session);
        JSONArray params = json.getJSONArray("params");
        
        if (params == null || params.isEmpty()) {
            sendError(session, id, "Missing params");
            return;
        }
        
        Set<String> subscribedChannels = new HashSet<>();
        Set<String> failedChannels = new HashSet<>();
        
        for (int i = 0; i < params.size(); i++) {
            String channel = params.getString(i);
            SubscribeResult result = subscriptionManager.subscribe(sessionId, channel);
            
            if (result.isSuccess()) {
                subscribedChannels.add(channel);
            } else {
                failedChannels.add(channel);
            }
        }
        
        // 发送响应
        JSONObject response = new JSONObject();
        response.put("result", "subscribed");
        response.put("id", id);
        
        JSONObject data = new JSONObject();
        data.put("subscribed", subscribedChannels);
        if (!failedChannels.isEmpty()) {
            data.put("failed", failedChannels);
        }
        response.put("data", data);
        
        sendMessage(session, response);
        
        log.debug("[WebSocket] {} subscribed to {}, failed: {}", 
                sessionId, subscribedChannels, failedChannels);
    }

    /**
     * 处理取消订阅
     */
    private void handleUnsubscribe(WebSocketSession session, JSONObject json, int id) {
        String sessionId = getSessionId(session);
        JSONArray params = json.getJSONArray("params");
        
        if (params == null || params.isEmpty()) {
            sendError(session, id, "Missing params");
            return;
        }
        
        Set<String> unsubscribedChannels = new HashSet<>();
        
        for (int i = 0; i < params.size(); i++) {
            String channel = params.getString(i);
            subscriptionManager.unsubscribe(sessionId, channel);
            unsubscribedChannels.add(channel);
        }
        
        // 发送响应
        JSONObject response = new JSONObject();
        response.put("result", "unsubscribed");
        response.put("id", id);
        response.put("data", unsubscribedChannels);
        
        sendMessage(session, response);
        
        log.debug("[WebSocket] {} unsubscribed from {}", sessionId, unsubscribedChannels);
    }

    /**
     * 处理列订阅请求
     */
    private void handleListSubscriptions(WebSocketSession session, int id) {
        String sessionId = getSessionId(session);
        Set<String> channels = subscriptionManager.getChannels(sessionId);
        
        JSONObject response = new JSONObject();
        response.put("result", "subscriptions");
        response.put("id", id);
        response.put("data", channels);
        
        sendMessage(session, response);
    }

    /**
     * 发送错误响应
     */
    private void sendError(WebSocketSession session, int id, String error) {
        JSONObject response = new JSONObject();
        response.put("error", error);
        response.put("id", id);
        sendMessage(session, response);
    }

    /**
     * 发送消息
     */
    private void sendMessage(WebSocketSession session, Object message) {
        try {
            if (session.isOpen()) {
                String json = message instanceof String ? (String) message : JSON.toJSONString(message);
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.error("[WebSocket] Failed to send message: {}", e.getMessage());
        }
    }

    /**
     * 获取sessionId
     */
    private String getSessionId(WebSocketSession session) {
        Object sessionId = session.getAttributes().get("sessionId");
        return sessionId != null ? sessionId.toString() : session.getId();
    }
}
