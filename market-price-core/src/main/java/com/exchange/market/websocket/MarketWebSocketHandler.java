package com.exchange.market.websocket;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.market.cache.MarketDataCache;
import com.exchange.market.engine.OrderBook;
import com.exchange.market.service.MarketDataEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket行情推送处理器
 * 
 * 职责：
 * - 处理客户端WebSocket连接
 * - 管理订阅关系
 * - 从Redis Pub/Sub接收行情并推送给客户端
 * 
 * 设计：
 * - 每个session独立管理订阅
 * - 使用Redis Pub/Sub做消息广播
 * - 支持快照+增量推送
 * 
 * 对标：Binance WebSocket Market Stream
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketWebSocketHandler extends TextWebSocketHandler {

    private final RedisMessageListenerContainer redisContainer;
    private final MarketDataCache cache;
    private final MarketDataEngineService engineService;

    // 所有WebSocket会话
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    
    // 频道 -> 订阅该频道的sessions
    private final Map<String, Set<WebSocketSession>> channelSubscriptions = new ConcurrentHashMap<>();
    
    // Redis消息监听器
    private final Map<String, MessageListener> listeners = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("[WebSocketHandler] Initialized");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("[WebSocket] Connection established: {}, total sessions: {}", 
                session.getId(), sessions.size() + 1);
        sessions.add(session);
        
        // 发送连接成功确认
        sendMessage(session, createResponse("connected", null));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("[WebSocket] Connection closed: {}, status: {}", session.getId(), status);
        
        // 移除该session的所有订阅
        unsubscribeAll(session);
        sessions.remove(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("[WebSocket] Received message from {}: {}", session.getId(), payload);
        
        try {
            JSONObject json = JSON.parseObject(payload);
            String method = json.getString("method");
            
            switch (method) {
                case "SUBSCRIBE" -> handleSubscribe(session, json);
                case "UNSUBSCRIBE" -> handleUnsubscribe(session, json);
                case "LIST_SUBSCRIPTIONS" -> handleListSubscriptions(session);
                case "ping" -> handlePing(session, json);
                default -> sendError(session, "Unknown method: " + method);
            }
        } catch (Exception e) {
            log.error("[WebSocket] Failed to handle message: {}", e.getMessage());
            sendError(session, "Invalid message format");
        }
    }

    /**
     * 处理订阅请求
     */
    private void handleSubscribe(WebSocketSession session, JSONObject json) {
        var params = json.getJSONArray("params");
        if (params == null || params.isEmpty()) {
            sendError(session, "Missing params");
            return;
        }
        
        for (int i = 0; i < params.size(); i++) {
            String channel = params.getString(i);
            subscribeChannel(session, channel);
        }
        
        sendMessage(session, createResponse("subscribed", params));
    }

    /**
     * 处理取消订阅请求
     */
    private void handleUnsubscribe(WebSocketSession session, JSONObject json) {
        var params = json.getJSONArray("params");
        if (params == null || params.isEmpty()) {
            sendError(session, "Missing params");
            return;
        }
        
        for (int i = 0; i < params.size(); i++) {
            String channel = params.getString(i);
            unsubscribeChannel(session, channel);
        }
        
        sendMessage(session, createResponse("unsubscribed", params));
    }

    /**
     * 处理列订阅请求
     */
    private void handleListSubscriptions(WebSocketSession session) {
        Set<String> channels = getSessionChannels(session);
        sendMessage(session, createResponse("subscriptions", channels));
    }

    /**
     * 处理心跳
     */
    private void handlePing(WebSocketSession session, JSONObject json) {
        long pingTime = json.getLongValue("ping");
        JSONObject pong = new JSONObject();
        pong.put("pong", pingTime);
        sendMessage(session, pong);
    }

    /**
     * 订阅频道
     */
    private void subscribeChannel(WebSocketSession session, String channel) {
        // 添加到订阅映射
        channelSubscriptions.computeIfAbsent(channel, k -> new CopyOnWriteArraySet<>()).add(session);
        
        // 如果是首次订阅该频道，创建Redis监听器
        if (channelSubscriptions.get(channel).size() == 1) {
            createChannelListener(channel);
        }
        
        // 发送快照
        sendSnapshot(session, channel);
        
        log.debug("[WebSocket] {} subscribed to {}", session.getId(), channel);
    }

    /**
     * 取消订阅频道
     */
    private void unsubscribeChannel(WebSocketSession session, String channel) {
        Set<WebSocketSession> subs = channelSubscriptions.get(channel);
        if (subs != null) {
            subs.remove(session);
            
            // 如果没有订阅者了，移除监听器
            if (subs.isEmpty()) {
                removeChannelListener(channel);
            }
        }
        
        log.debug("[WebSocket] {} unsubscribed from {}", session.getId(), channel);
    }

    /**
     * 取消所有订阅
     */
    private void unsubscribeAll(WebSocketSession session) {
        channelSubscriptions.forEach((channel, subs) -> subs.remove(session));
        channelSubscriptions.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /**
     * 获取session订阅的所有频道
     */
    private Set<String> getSessionChannels(WebSocketSession session) {
        Set<String> channels = new java.util.HashSet<>();
        channelSubscriptions.forEach((channel, subs) -> {
            if (subs.contains(session)) {
                channels.add(channel);
            }
        });
        return channels;
    }

    /**
     * 创建Redis频道监听器
     */
    private void createChannelListener(String channel) {
        String redisChannel = convertToRedisChannel(channel);
        
        MessageListener listener = (message, pattern) -> {
            // 广播给所有订阅者
            broadcastToChannel(channel, message);
        };
        
        listeners.put(channel, listener);
        redisContainer.addMessageListener(listener, new ChannelTopic(redisChannel));
        
        log.debug("[WebSocket] Created listener for channel: {}", channel);
    }

    /**
     * 移除Redis频道监听器
     */
    private void removeChannelListener(String channel) {
        MessageListener listener = listeners.remove(channel);
        if (listener != null) {
            redisContainer.removeMessageListener(listener);
            log.debug("[WebSocket] Removed listener for channel: {}", channel);
        }
    }

    /**
     * 广播消息到频道订阅者
     */
    private void broadcastToChannel(String channel, Message message) {
        Set<WebSocketSession> subs = channelSubscriptions.get(channel);
        if (subs == null || subs.isEmpty()) {
            return;
        }
        
        String payload = new String(message.getBody());
        JSONObject data;
        try {
            data = JSON.parseObject(payload);
        } catch (Exception e) {
            data = new JSONObject();
            data.put("data", payload);
        }
        
        JSONObject wrapper = new JSONObject();
        wrapper.put("stream", channel);
        wrapper.put("data", data);
        
        String messageStr = wrapper.toJSONString();
        
        // 发送给所有订阅者
        for (WebSocketSession session : subs) {
            if (session.isOpen()) {
                sendMessage(session, wrapper);
            }
        }
    }

    /**
     * 发送快照
     */
    private void sendSnapshot(WebSocketSession session, String channel) {
        try {
            if (channel.startsWith("depth@")) {
                // 深度快照
                String symbol = channel.substring(6); // 去除 "depth@"
                OrderBook.DepthSnapshot snapshot = engineService.getOrderBook(symbol).getSnapshot(20);
                
                JSONObject data = new JSONObject();
                data.put("lastUpdateId", snapshot.getLastUpdateId());
                data.put("bids", snapshot.getBids());
                data.put("asks", snapshot.getAsks());
                
                JSONObject wrapper = new JSONObject();
                wrapper.put("stream", channel);
                wrapper.put("data", data);
                
                sendMessage(session, wrapper);
            }
            // 其他频道的快照逻辑...
        } catch (Exception e) {
            log.error("[WebSocket] Failed to send snapshot for {}: {}", channel, e.getMessage());
        }
    }

    /**
     * 转换频道名到Redis频道名
     */
    private String convertToRedisChannel(String channel) {
        // depth@BTCUSDT -> market:depth:BTCUSDT
        if (channel.startsWith("depth@")) {
            return "market:depth:" + channel.substring(6);
        }
        if (channel.startsWith("trade@")) {
            return "market:trade:" + channel.substring(6);
        }
        if (channel.startsWith("kline@")) {
            String[] parts = channel.split("@");
            if (parts.length >= 3) {
                return "market:kline:" + parts[1] + ":" + parts[2];
            }
        }
        if (channel.startsWith("ticker@")) {
            return "market:ticker:" + channel.substring(7);
        }
        return channel;
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
     * 发送错误消息
     */
    private void sendError(WebSocketSession session, String error) {
        JSONObject json = new JSONObject();
        json.put("error", error);
        sendMessage(session, json);
    }

    /**
     * 创建响应消息
     */
    private JSONObject createResponse(String result, Object data) {
        JSONObject json = new JSONObject();
        json.put("result", result);
        if (data != null) {
            json.put("data", data);
        }
        return json;
    }
}
