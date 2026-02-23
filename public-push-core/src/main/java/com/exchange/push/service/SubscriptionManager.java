package com.exchange.push.service;

import com.exchange.push.config.PublicPushProperties;
import com.exchange.push.model.ChannelType;
import com.exchange.push.model.SubscribeResult;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 订阅管理器
 * 
 * 职责：
 * - 管理频道订阅关系
 * - 订阅数量限制
 * - 动态Kafka消费者管理
 * 
 * 核心数据结构：
 * 1. channel -> Set<sessionId> (正向索引，用于广播)
 * 2. sessionId -> Set<channel> (反向索引，用于快速取消订阅)
 */
@Slf4j
@Service
public class SubscriptionManager {

    @Autowired
    private PublicPushProperties properties;
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    @Autowired
    @Lazy
    private ConnectionManager connectionManager;
    
    @Autowired
    @Lazy
    private KafkaConsumerManager kafkaConsumerManager;

    @Autowired
    @Lazy
    private MessageDispatcher messageDispatcher;

    // 频道 -> 订阅该频道的sessions (正向索引)
    private final Map<String, Set<String>> channelSubscribers = new ConcurrentHashMap<>();
    
    // session -> 该session订阅的频道 (反向索引)
    private final Map<String, Set<String>> sessionChannels = new ConcurrentHashMap<>();
    
    // 订阅计数器 (用于监控)
    private final AtomicInteger totalSubscriptions = new AtomicInteger(0);
    
    // 频道类型订阅计数
    private final Map<ChannelType, AtomicInteger> channelTypeCounts = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 注册监控指标
        Gauge.builder("websocket.subscriptions.total", totalSubscriptions, AtomicInteger::get)
            .description("Total number of channel subscriptions")
            .register(meterRegistry);
        
        for (ChannelType type : ChannelType.values()) {
            channelTypeCounts.put(type, new AtomicInteger(0));
            Gauge.builder("websocket.subscriptions.type", 
                    channelTypeCounts.get(type), AtomicInteger::get)
                .tag("type", type.name())
                .description("Number of subscriptions by channel type")
                .register(meterRegistry);
        }
    }

    /**
     * 订阅频道
     */
    public SubscribeResult subscribe(String sessionId, String channel) {
        // 检查session是否存在
        if (!connectionManager.exists(sessionId)) {
            return SubscribeResult.error(channel, "Session not found");
        }
        
        // 检查订阅数限制
        Set<String> currentSubs = sessionChannels.get(sessionId);
        int maxSubs = properties.getSubscription().getMaxSubscriptionsPerSession();
        if (currentSubs != null && currentSubs.size() >= maxSubs) {
            return SubscribeResult.error(channel, 
                    "Subscription limit exceeded: " + maxSubs);
        }
        
        // 解析频道类型
        ChannelType channelType = ChannelType.fromChannel(channel);
        if (channelType == ChannelType.UNKNOWN) {
            return SubscribeResult.error(channel, "Unknown channel type");
        }
        
        // 添加到索引
        channelSubscribers.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet())
                          .add(sessionId);
        sessionChannels.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
                       .add(channel);
        
        // 更新计数器
        totalSubscriptions.incrementAndGet();
        channelTypeCounts.get(channelType).incrementAndGet();
        
        // 确保Kafka消费者已启动
        kafkaConsumerManager.ensureConsumerStarted(channel);
        
        // 发送快照
        messageDispatcher.sendSnapshot(sessionId, channel);
        
        log.info("[Subscription] {} subscribed to {} (type: {}), total subs: {}", 
                sessionId, channel, channelType, totalSubscriptions.get());
        
        return SubscribeResult.success(channel);
    }

    /**
     * 批量订阅
     */
    public void subscribeBatch(String sessionId, Set<String> channels) {
        for (String channel : channels) {
            subscribe(sessionId, channel);
        }
    }

    /**
     * 取消订阅
     */
    public void unsubscribe(String sessionId, String channel) {
        Set<String> subs = channelSubscribers.get(channel);
        if (subs != null) {
            subs.remove(sessionId);
            
            // 如果没有订阅者，停止Kafka消费者
            if (subs.isEmpty()) {
                kafkaConsumerManager.stopConsumer(channel);
                channelSubscribers.remove(channel);
            }
        }
        
        Set<String> channels = sessionChannels.get(sessionId);
        if (channels != null) {
            channels.remove(channel);
        }
        
        // 更新计数器
        totalSubscriptions.decrementAndGet();
        ChannelType channelType = ChannelType.fromChannel(channel);
        channelTypeCounts.get(channelType).decrementAndGet();
        
        log.debug("[Subscription] {} unsubscribed from {}", sessionId, channel);
    }

    /**
     * 取消所有订阅
     */
    public void unsubscribeAll(String sessionId) {
        Set<String> channels = sessionChannels.remove(sessionId);
        if (channels != null) {
            for (String channel : channels) {
                Set<String> subs = channelSubscribers.get(channel);
                if (subs != null) {
                    subs.remove(sessionId);
                    if (subs.isEmpty()) {
                        kafkaConsumerManager.stopConsumer(channel);
                        channelSubscribers.remove(channel);
                    }
                }
                
                // 更新计数器
                totalSubscriptions.decrementAndGet();
                ChannelType channelType = ChannelType.fromChannel(channel);
                channelTypeCounts.get(channelType).decrementAndGet();
            }
            
            log.info("[Subscription] {} unsubscribed from {} channels", sessionId, channels.size());
        }
    }

    /**
     * 获取频道的所有订阅者
     */
    public Set<String> getSubscribers(String channel) {
        Set<String> subs = channelSubscribers.get(channel);
        return subs != null ? Set.copyOf(subs) : Set.of();
    }

    /**
     * 获取session订阅的所有频道
     */
    public Set<String> getChannels(String sessionId) {
        Set<String> channels = sessionChannels.get(sessionId);
        return channels != null ? Set.copyOf(channels) : Set.of();
    }

    /**
     * 检查session是否订阅了频道
     */
    public boolean isSubscribed(String sessionId, String channel) {
        Set<String> channels = sessionChannels.get(sessionId);
        return channels != null && channels.contains(channel);
    }

    /**
     * 获取订阅统计
     */
    public Map<String, Object> getSubscriptionStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("totalSubscriptions", totalSubscriptions.get());
        stats.put("activeChannels", channelSubscribers.size());
        
        Map<String, Integer> typeStats = new ConcurrentHashMap<>();
        channelTypeCounts.forEach((type, count) -> 
            typeStats.put(type.name(), count.get()));
        stats.put("byType", typeStats);
        
        return stats;
    }

    /**
     * 获取频道订阅数
     */
    public int getSubscriberCount(String channel) {
        Set<String> subs = channelSubscribers.get(channel);
        return subs != null ? subs.size() : 0;
    }
}
