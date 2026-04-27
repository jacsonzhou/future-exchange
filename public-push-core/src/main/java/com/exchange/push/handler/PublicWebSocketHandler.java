package com.exchange.push.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.push.service.ConnectionManager;
import com.exchange.push.service.SubscriptionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.InetSocketAddress;

/**
 * 公有推送 WebFlux WebSocket 处理器
 *
 * 协议：
 * - 订阅: {"method": "SUBSCRIBE", "params": ["trade.BTCUSDT", "depth.BTCUSDT@100ms"], "id": 1}
 * - 外部行情订阅: {"method": "SUBSCRIBE", "params": ["depth.ext.binance.BTCUSDT", "kline.ext.binance.BTCUSDT.1m"], "id": 2}
 * - 取消订阅: {"method": "UNSUBSCRIBE", "params": ["depth.BTCUSDT@100ms"], "id": 2}
 * - 心跳: {"ping": 1704067200123}
 * - 心跳响应: {"pong": 1704067200123}
 */
@Slf4j
@Component
public class PublicWebSocketHandler implements WebSocketHandler {

    @Autowired
    private ConnectionManager connectionManager;

    @Autowired
    private SubscriptionManager subscriptionManager;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // 提取客户端IP（支持 X-Forwarded-For / X-Real-IP 等代理头）
        String clientIp = extractClientIp(session);

        // 注册连接
        String sessionId = connectionManager.registerConnection(clientIp);
        if (sessionId == null) {
            return session.close(CloseStatus.POLICY_VIOLATION);
        }

        Sinks.Many<String> sink = connectionManager.getSink(sessionId);

        // 发送连接成功确认
        JSONObject response = new JSONObject();
        response.put("result", "connected");
        response.put("serverTime", System.currentTimeMillis());
        sink.tryEmitNext(response.toJSONString());

        log.info("[WebSocket] Connection established: {}, total connections: {}",
                sessionId, connectionManager.getSessions().size());

        // 入站消息处理
        Mono<Void> inbound = session.receive()
            .map(WebSocketMessage::getPayloadAsText)
            .flatMap(payload -> handleInboundMessage(sessionId, payload))
            .onErrorResume(e -> {
                log.error("[WebSocket] Inbound error for {}: {}", sessionId, e.getMessage());
                return Mono.empty();
            })
            .then();

        // 出站消息：从 sink 读取并发送给客户端
        Mono<Void> outbound = session.send(
            sink.asFlux()
                .map(session::textMessage)
                .onErrorResume(e -> {
                    log.error("[WebSocket] Outbound error for {}: {}", sessionId, e.getMessage());
                    return Mono.empty();
                })
        );

        // 入站或出站任一结束即触发清理
        return Mono.zip(inbound, outbound)
            .then()
            .doFinally(signal -> {
                connectionManager.removeConnection(sessionId);
                log.info("[WebSocket] Connection closed: {}, total connections: {}",
                        sessionId, connectionManager.getSessions().size());
            })
            .onErrorResume(e -> {
                log.error("[WebSocket] Session error for {}: {}", sessionId, e.getMessage());
                connectionManager.removeConnection(sessionId);
                return Mono.empty();
            });
    }

    private Mono<Void> handleInboundMessage(String sessionId, String payload) {
        connectionManager.markActivity(sessionId);

        log.debug("[WebSocket] Received message from {}: {}", sessionId, payload);

        try {
            JSONObject json = JSON.parseObject(payload);

            // 处理心跳
            if (json.containsKey("ping")) {
                return handlePing(sessionId, json);
            }
            if (json.containsKey("pong")) {
                connectionManager.updatePong(sessionId);
                return Mono.empty();
            }

            // 处理方法调用
            String method = json.getString("method");
            int id = json.getIntValue("id", 0);

            return switch (method) {
                case "SUBSCRIBE" -> handleSubscribe(sessionId, json, id);
                case "UNSUBSCRIBE" -> handleUnsubscribe(sessionId, json, id);
                case "LIST_SUBSCRIPTIONS" -> handleListSubscriptions(sessionId, id);
                default -> {
                    sendError(sessionId, id, "Unknown method: " + method);
                    yield Mono.empty();
                }
            };

        } catch (Exception e) {
            log.error("[WebSocket] Failed to handle message from {}: {}", sessionId, e.getMessage());
            sendError(sessionId, 0, "Invalid message format: " + e.getMessage());
            return Mono.empty();
        }
    }

    private Mono<Void> handlePing(String sessionId, JSONObject json) {
        long pingTime = json.getLongValue("ping");

        connectionManager.updatePing(sessionId, pingTime);
        connectionManager.updatePong(sessionId);

        JSONObject pong = new JSONObject();
        pong.put("pong", pingTime);
        sendMessage(sessionId, pong);

        return Mono.empty();
    }

    private Mono<Void> handleSubscribe(String sessionId, JSONObject json, int id) {
        JSONArray params = json.getJSONArray("params");

        if (params == null || params.isEmpty()) {
            sendError(sessionId, id, "Missing params");
            return Mono.empty();
        }

        for (int i = 0; i < params.size(); i++) {
            String channel = params.getString(i);
            subscriptionManager.subscribe(sessionId, channel);
        }

        JSONObject response = new JSONObject();
        response.put("result", "subscribed");
        response.put("id", id);
        sendMessage(sessionId, response);

        return Mono.empty();
    }

    private Mono<Void> handleUnsubscribe(String sessionId, JSONObject json, int id) {
        JSONArray params = json.getJSONArray("params");

        if (params == null || params.isEmpty()) {
            sendError(sessionId, id, "Missing params");
            return Mono.empty();
        }

        for (int i = 0; i < params.size(); i++) {
            String channel = params.getString(i);
            subscriptionManager.unsubscribe(sessionId, channel);
        }

        JSONObject response = new JSONObject();
        response.put("result", "unsubscribed");
        response.put("id", id);
        sendMessage(sessionId, response);

        return Mono.empty();
    }

    private Mono<Void> handleListSubscriptions(String sessionId, int id) {
        JSONObject response = new JSONObject();
        response.put("result", "subscriptions");
        response.put("id", id);
        response.put("data", subscriptionManager.getChannels(sessionId));
        sendMessage(sessionId, response);
        return Mono.empty();
    }

    private void sendError(String sessionId, int id, String error) {
        JSONObject response = new JSONObject();
        response.put("error", error);
        response.put("id", id);
        sendMessage(sessionId, response);
    }

    private void sendMessage(String sessionId, Object message) {
        Sinks.Many<String> sink = connectionManager.getSink(sessionId);
        if (sink != null) {
            String json = message instanceof String ? (String) message : JSON.toJSONString(message);
            sink.tryEmitNext(json);
        }
    }

    /**
     * 从 HandshakeInfo 中提取客户端真实 IP
     * 支持 X-Forwarded-For、X-Real-IP 等常见代理头
     */
    private String extractClientIp(WebSocketSession session) {
        HandshakeInfo info = session.getHandshakeInfo();
        HttpHeaders headers = info.getHeaders();

        // X-Forwarded-For: client, proxy1, proxy2
        String forwardedFor = headers.getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String firstIp = forwardedFor.split(",")[0].trim();
            if (!firstIp.isBlank()) {
                return firstIp;
            }
        }

        String realIp = headers.getFirst("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }

        InetSocketAddress remoteAddress = info.getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        }

        return "unknown";
    }
}
