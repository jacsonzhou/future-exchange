package com.exchange.push.model;

import lombok.Data;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket连接元数据
 */
@Data
public class ConnectionMetadata {
    
    private final String sessionId;
    private final String clientIp;
    private final long connectTime;
    
    // 心跳相关
    private volatile long lastPingTime;
    private volatile long lastPongTime;
    private volatile long lastActivityTime;
    
    // 订阅频道
    private final Set<String> subscribedChannels = ConcurrentHashMap.newKeySet();
    
    // 消息统计
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesDropped = new AtomicLong(0);
    private final AtomicLong bytesSent = new AtomicLong(0);
    
    // 限流统计
    private final AtomicInteger messageCountInSecond = new AtomicInteger(0);
    private final AtomicInteger messageCountInMinute = new AtomicInteger(0);
    
    // 最后序列号（用于快照+增量校验）
    private final ConcurrentHashMap<String, Long> lastSequenceMap = new ConcurrentHashMap<>();
    
    public ConnectionMetadata(String sessionId, String clientIp) {
        this.sessionId = sessionId;
        this.clientIp = clientIp;
        this.connectTime = System.currentTimeMillis();
        this.lastPingTime = System.currentTimeMillis();
        this.lastPongTime = System.currentTimeMillis();
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * 更新心跳时间
     */
    public void updatePing(long timestamp) {
        this.lastPingTime = timestamp;
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * 更新Pong时间
     */
    public void updatePong() {
        this.lastPongTime = System.currentTimeMillis();
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * 更新活动
     */
    public void markActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * 获取最后序列号
     */
    public long getLastSequence(String channel) {
        return lastSequenceMap.getOrDefault(channel, 0L);
    }
    
    /**
     * 更新序列号
     */
    public void updateSequence(String channel, long sequence) {
        lastSequenceMap.put(channel, sequence);
    }
    
    /**
     * 统计发送消息
     */
    public void incrementMessagesSent(int bytes) {
        messagesSent.incrementAndGet();
        bytesSent.addAndGet(bytes);
        messageCountInSecond.incrementAndGet();
        messageCountInMinute.incrementAndGet();
        markActivity();
    }
    
    /**
     * 重置秒级计数器
     */
    public void resetSecondCounter() {
        messageCountInSecond.set(0);
    }
    
    /**
     * 重置分钟级计数器
     */
    public void resetMinuteCounter() {
        messageCountInMinute.set(0);
    }
    
    /**
     * 检查是否僵尸连接
     */
    public boolean isZombie(long timeoutMs) {
        return System.currentTimeMillis() - lastPongTime > timeoutMs;
    }
}
