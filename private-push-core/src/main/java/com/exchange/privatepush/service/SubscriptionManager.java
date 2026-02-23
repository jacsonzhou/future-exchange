package com.exchange.privatepush.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 订阅管理器
 * 
 * 🔥 核心职责：
 * 1. 管理频道与 Session 的映射关系
 * 2. 支持反向查找 (Session -> 频道)
 * 3. 快速获取频道的所有订阅者
 * 
 * 数据结构:
 * - channel -> Set<sessionId> (用于广播)
 * - sessionId -> Set<channel> (用于快速取消订阅)
 * 
 * @author Exchange Team
 * @version 1.0.0
 */
@Slf4j
@Service
public class SubscriptionManager {
    
    // Channel -> Set<SessionId>
    private final ConcurrentHashMap<String, Set<String>> channelSubscribers = new ConcurrentHashMap<>();
    
    // SessionId -> Set<Channel>
    private final ConcurrentHashMap<String, Set<String>> sessionChannels = new ConcurrentHashMap<>();
    
    /**
     * 订阅频道
     * 
     * @param sessionId 会话ID
     * @param channel 频道名
     * @return 是否订阅成功 (false = 已订阅)
     */
    public boolean subscribe(String sessionId, String channel) {
        // 添加到频道订阅者集合
        Set<String> subscribers = channelSubscribers.computeIfAbsent(
                channel, k -> ConcurrentHashMap.newKeySet());
        
        boolean added = subscribers.add(sessionId);
        
        if (added) {
            // 添加到会话频道集合
            Set<String> channels = sessionChannels.computeIfAbsent(
                    sessionId, k -> ConcurrentHashMap.newKeySet());
            channels.add(channel);
            
            log.debug("[SubscriptionManager] Subscribed, sessionId={}, channel={}, totalSubscribers={}",
                    sessionId, channel, subscribers.size());
        }
        
        return added;
    }
    
    /**
     * 取消订阅
     * 
     * @param sessionId 会话ID
     * @param channel 频道名
     * @return 是否取消成功
     */
    public boolean unsubscribe(String sessionId, String channel) {
        // 从频道订阅者集合移除
        Set<String> subscribers = channelSubscribers.get(channel);
        boolean removed = false;
        
        if (subscribers != null) {
            removed = subscribers.remove(sessionId);
            
            // 如果频道没有订阅者，移除频道
            if (subscribers.isEmpty()) {
                channelSubscribers.remove(channel);
            }
        }
        
        if (removed) {
            // 从会话频道集合移除
            Set<String> channels = sessionChannels.get(sessionId);
            if (channels != null) {
                channels.remove(channel);
                if (channels.isEmpty()) {
                    sessionChannels.remove(sessionId);
                }
            }
            
            log.debug("[SubscriptionManager] Unsubscribed, sessionId={}, channel={}",
                    sessionId, channel);
        }
        
        return removed;
    }
    
    /**
     * 取消会话的所有订阅
     * 
     * @param sessionId 会话ID
     */
    public void unsubscribeAll(String sessionId) {
        Set<String> channels = sessionChannels.remove(sessionId);
        
        if (channels != null) {
            for (String channel : channels) {
                Set<String> subscribers = channelSubscribers.get(channel);
                if (subscribers != null) {
                    subscribers.remove(sessionId);
                    if (subscribers.isEmpty()) {
                        channelSubscribers.remove(channel);
                    }
                }
            }
            
            log.debug("[SubscriptionManager] Unsubscribed all, sessionId={}, channels={}",
                    sessionId, channels.size());
        }
    }
    
    /**
     * 获取频道的所有订阅者
     * 
     * @param channel 频道名
     * @return 订阅者会话ID集合
     */
    public Set<String> getSubscribers(String channel) {
        return channelSubscribers.getOrDefault(channel, ConcurrentHashMap.newKeySet());
    }
    
    /**
     * 获取会话订阅的所有频道
     * 
     * @param sessionId 会话ID
     * @return 频道集合
     */
    public Set<String> getChannels(String sessionId) {
        return sessionChannels.getOrDefault(sessionId, ConcurrentHashMap.newKeySet());
    }
    
    /**
     * 获取频道订阅者数量
     */
    public int getSubscriberCount(String channel) {
        Set<String> subscribers = channelSubscribers.get(channel);
        return subscribers != null ? subscribers.size() : 0;
    }
    
    /**
     * 检查会话是否已订阅频道
     */
    public boolean isSubscribed(String sessionId, String channel) {
        Set<String> subscribers = channelSubscribers.get(channel);
        return subscribers != null && subscribers.contains(sessionId);
    }
    
    /**
     * 获取所有频道统计
     */
    public Map<String, Integer> getChannelStats() {
        Map<String, Integer> stats = new ConcurrentHashMap<>();
        
        channelSubscribers.forEach((channel, subscribers) -> {
            stats.put(channel, subscribers.size());
        });
        
        return stats;
    }
    
    /**
     * 获取订阅统计
     */
    public SubscriptionStats getStats() {
        return SubscriptionStats.builder()
                .totalChannels(channelSubscribers.size())
                .totalSubscriptions(
                    sessionChannels.values().stream()
                            .mapToInt(Set::size)
                            .sum()
                )
                .build();
    }
    
    /**
     * 订阅统计信息
     */
    @lombok.Builder
    @lombok.Data
    public static class SubscriptionStats {
        private int totalChannels;
        private int totalSubscriptions;
    }
}
