package com.exchange.binance.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.binance.config.BinanceDataSourceConfig;
import com.exchange.binance.handler.BinanceMessageHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 币安WebSocket客户端
 * 
 * 职责：
 * - 管理与币安WebSocket的连接
 * - 自动重连机制（指数退避）
 * - 心跳检测
 * - 多交易对聚合订阅
 * 
 * 币安WebSocket限制：
 * - 单连接最多订阅100个streams
 * - 每IP最多300连接
 * - 每5分钟最多150订阅消息
 */
@Slf4j
@Component
public class BinanceWebSocketClient {

    @Autowired
    private BinanceDataSourceConfig config;

    @Autowired
    private BinanceMessageHandler messageHandler;

    private HttpClient httpClient;
    private WebSocket webSocket;
    private ScheduledExecutorService heartbeatScheduler;
    private ScheduledExecutorService reconnectScheduler;

    @Getter
    private volatile boolean connected = false;

    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicLong lastPongTime = new AtomicLong(0);
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong connectTime = new AtomicLong(0);

    private static final int MAX_STREAMS_PER_CONNECTION = 100;
    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "binance-heartbeat");
            t.setDaemon(true);
            return t;
        });

        reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "binance-reconnect");
            t.setDaemon(true);
            return t;
        });

        // 延迟启动连接，等待Spring上下文完全初始化
        reconnectScheduler.schedule(this::connect, 2, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void destroy() {
        log.info("[BinanceWS] Shutting down...");
        disconnect();
        
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
        if (reconnectScheduler != null) {
            reconnectScheduler.shutdownNow();
        }
    }

    /**
     * 建立WebSocket连接
     */
    public synchronized void connect() {
        if (connected || reconnecting.get()) {
            return;
        }

        List<String> symbols = config.getSymbols();
        if (symbols.isEmpty()) {
            log.warn("[BinanceWS] No symbols configured, skipping connection");
            return;
        }

        try {
            String wsUrl = buildWebSocketUrl(symbols);
            log.info("[BinanceWS] Connecting to {} with {} symbols", wsUrl, symbols.size());

            webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(wsUrl), new BinanceWebSocketListener())
                    .join();

            connected = true;
            connectTime.set(System.currentTimeMillis());
            reconnectAttempts.set(0);
            lastPongTime.set(System.currentTimeMillis());

            log.info("[BinanceWS] Connected successfully");

            // 启动心跳检测
            if (config.getHeartbeat().isEnabled()) {
                startHeartbeat();
            }

            // 发送订阅消息（如果是/combined接口需要）
            if (!wsUrl.contains("/stream?streams=")) {
                sendSubscribeMessages(symbols);
            }

        } catch (Exception e) {
            log.error("[BinanceWS] Connection failed: {}", e.getMessage());
            scheduleReconnect();
        }
    }

    /**
     * 断开连接
     */
    public synchronized void disconnect() {
        connected = false;
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Service shutdown");
            } catch (Exception e) {
                // ignore
            }
            webSocket = null;
        }
        stopHeartbeat();
        log.info("[BinanceWS] Disconnected");
    }

    /**
     * 构建WebSocket URL
     * 
     * 策略：
     * - 如果交易对数量 <= 20，使用combined stream (/stream?streams=...)
     * - 如果交易对数量 > 20，使用单连接多订阅模式
     */
    private String buildWebSocketUrl(List<String> symbols) {
        List<String> streams = symbols.stream()
                .flatMap(symbol -> buildStreamsForSymbol(symbol.toLowerCase()).stream())
                .collect(Collectors.toList());

        String baseUrl = normalizeWsBaseUrl(config.getWsUrl());

        if (streams.size() <= MAX_STREAMS_PER_CONNECTION) {
            // 使用combined stream（更高效）: {base}/stream?streams=...
            String streamParam = String.join("/", streams);
            String streamBase = baseUrl.endsWith("/ws")
                    ? baseUrl.substring(0, baseUrl.length() - 3)
                    : baseUrl;
            return streamBase + "/stream?streams=" + streamParam;
        } else {
            // 使用单连接，通过消息订阅: {base}/ws
            if (baseUrl.endsWith("/ws")) {
                return baseUrl;
            }
            if (baseUrl.contains("/stream")) {
                int idx = baseUrl.indexOf("/stream");
                baseUrl = baseUrl.substring(0, idx);
            }
            return baseUrl + "/ws";
        }
    }

    private String normalizeWsBaseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "wss://fstream.binance.com";
        }
        String url = rawUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * 为单个交易对构建stream列表
     */
    private List<String> buildStreamsForSymbol(String symbol) {
        List<String> streams = new java.util.ArrayList<>();
        
        // 深度数据
        streams.add(symbol + "@depth@" + config.getDepthSpeed());
        
        // 成交数据
        if (config.isTradeEnabled()) {
            streams.add(symbol + "@trade");
        }
        
        // 聚合成交
        if (config.isAggTradeEnabled()) {
            streams.add(symbol + "@aggTrade");
        }
        
        // Ticker
        if (config.isTickerEnabled()) {
            streams.add(symbol + "@ticker");
        }

        // Kline
        if (config.isKlineEnabled()) {
            for (String interval : config.getKlineIntervals()) {
                if (interval == null || interval.isBlank()) {
                    continue;
                }
                streams.add(symbol + "@kline_" + interval.toLowerCase(Locale.ROOT));
            }
        }
        
        return streams;
    }

    /**
     * 发送订阅消息
     */
    private void sendSubscribeMessages(List<String> symbols) {
        List<String> streams = symbols.stream()
                .flatMap(symbol -> buildStreamsForSymbol(symbol.toLowerCase()).stream())
                .collect(Collectors.toList());

        // 币安限制：每5分钟最多150次订阅
        // 分批发送，每批不超过100个
        for (int i = 0; i < streams.size(); i += MAX_STREAMS_PER_CONNECTION) {
            List<String> batch = streams.subList(i, Math.min(i + MAX_STREAMS_PER_CONNECTION, streams.size()));
            
            JSONObject subscribeMsg = new JSONObject();
            subscribeMsg.put("method", "SUBSCRIBE");
            subscribeMsg.put("params", batch);
            subscribeMsg.put("id", i / MAX_STREAMS_PER_CONNECTION + 1);
            
            sendMessage(subscribeMsg.toJSONString());
            
            // 短暂延迟，避免触发限流
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 发送消息
     */
    public void sendMessage(String message) {
        if (webSocket != null && connected) {
            webSocket.sendText(message, true);
        }
    }

    /**
     * 发送ping消息
     */
    private void sendPing() {
        if (webSocket != null && connected) {
            webSocket.sendPing(ByteBuffer.wrap("hb".getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * 启动心跳检测
     */
    private void startHeartbeat() {
        int interval = config.getHeartbeat().getIntervalSec();
        long timeoutMs = Math.max(1000L, config.getHeartbeat().getTimeoutSec() * 1000L);
        
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (!connected) {
                return;
            }

            // 检查上次pong时间
            long lastPong = lastPongTime.get();
            long now = System.currentTimeMillis();
            
            if (now - lastPong > timeoutMs) {
                log.warn("[BinanceWS] Heartbeat timeout ({}ms), reconnecting...", timeoutMs);
                disconnect();
                scheduleReconnect();
                return;
            }

            // 发送ping
            sendPing();
            
        }, interval, interval, TimeUnit.SECONDS);
        
        log.info("[BinanceWS] Heartbeat started, interval={}s, timeout={}ms", interval, timeoutMs);
    }

    /**
     * 停止心跳
     */
    private void stopHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "binance-heartbeat");
                t.setDaemon(true);
                return t;
            });
        }
    }

    /**
     * 调度重连（指数退避）
     */
    private void scheduleReconnect() {
        if (reconnecting.compareAndSet(false, true)) {
            int attempts = reconnectAttempts.incrementAndGet();
            
            if (attempts > config.getReconnect().getMaxAttempts()) {
                log.error("[BinanceWS] Max reconnect attempts reached, giving up");
                reconnecting.set(false);
                return;
            }

            long delayMs = calculateBackoffDelay(attempts);
            log.info("[BinanceWS] Reconnect scheduled in {}ms (attempt {}/{})", 
                    delayMs, attempts, config.getReconnect().getMaxAttempts());

            reconnectScheduler.schedule(() -> {
                reconnecting.set(false);
                connect();
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 计算退避延迟
     */
    private long calculateBackoffDelay(int attempt) {
        long baseDelay = config.getReconnect().getBaseDelayMs();
        double multiplier = config.getReconnect().getMultiplier();
        long maxDelay = config.getReconnect().getMaxDelayMs();

        long delay = (long) (baseDelay * Math.pow(multiplier, attempt - 1));
        return Math.min(delay, maxDelay);
    }

    /**
     * WebSocket监听器
     */
    private class BinanceWebSocketListener implements WebSocket.Listener {

        private final StringBuilder messageBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("[BinanceWS] Connection opened");
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);
            
            if (last) {
                String message = messageBuffer.toString();
                messageBuffer.setLength(0);
                
                messagesReceived.incrementAndGet();
                lastPongTime.set(System.currentTimeMillis());
                
                try {
                    handleMessage(message);
                } catch (Exception e) {
                    log.error("[BinanceWS] Error handling message: {}", e.getMessage());
                }
            }
            
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            lastPongTime.set(System.currentTimeMillis());
            return WebSocket.Listener.super.onPong(webSocket, message);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("[BinanceWS] Connection closed: status={}, reason={}", statusCode, reason);
            connected = false;
            
            if (statusCode != WebSocket.NORMAL_CLOSURE) {
                scheduleReconnect();
            }
            
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("[BinanceWS] WebSocket error: {}", error.getMessage());
            connected = false;
            scheduleReconnect();
        }

        /**
         * 处理收到的消息
         */
        private void handleMessage(String message) {
            try {
                JSONObject json = JSON.parseObject(message);
                
                // 处理pong响应
                if (json.containsKey("pong")) {
                    lastPongTime.set(System.currentTimeMillis());
                    return;
                }
                
                // 处理订阅响应
                if (json.containsKey("result") && json.containsKey("id")) {
                    log.debug("[BinanceWS] Subscribe response: {}", message);
                    return;
                }
                
                // 处理错误
                if (json.containsKey("error")) {
                    log.error("[BinanceWS] Error from server: {}", message);
                    return;
                }
                
                // 处理数据消息
                messageHandler.handleMessage(json);
                
            } catch (Exception e) {
                log.error("[BinanceWS] Failed to parse message: {}", message, e);
            }
        }
    }

    // ========== 监控指标获取 ==========

    public long getMessagesReceived() {
        return messagesReceived.get();
    }

    public long getConnectTime() {
        return connectTime.get();
    }

    public int getReconnectCount() {
        return reconnectAttempts.get();
    }

    public long getUptimeSeconds() {
        if (connectTime.get() == 0) {
            return 0;
        }
        return (System.currentTimeMillis() - connectTime.get()) / 1000;
    }
}
