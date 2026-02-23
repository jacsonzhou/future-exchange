package com.exchange.privatepush.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 消息持久化服务
 *
 * 🔥 核心职责：
 * 1. Redis缓存最近5分钟的消息
 * 2. 用户重连后自动恢复消息
 * 3. 基于seq去重，避免重复消费
 *
 * Redis数据结构：
 * - Key: user:{userId}:messages
 * - Type: ZSet (score = seq, value = json)
 * - TTL: 5分钟
 *
 * 使用场景：
 * - 用户短暂断线（网络抖动）
 * - 服务重启恢复
 * - 跨节点切换
 *
 * @author Exchange Team
 * @version 1.0.0
 */
@Slf4j
@Service
public class MessagePersistenceService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Redis Key前缀
    private static final String MESSAGE_PREFIX = "user:";
    private static final String MESSAGE_SUFFIX = ":messages";

    // 消息缓存时间（5分钟）
    private static final int MESSAGE_TTL_MINUTES = 5;

    // 最大缓存消息数（每用户）
    private static final int MAX_CACHED_MESSAGES = 1000;

    /**
     * 保存消息到Redis
     *
     * @param userId 用户ID
     * @param seq 序列号
     * @param message 消息JSON
     */
    public void saveMessage(Long userId, long seq, String message) {
        try {
            String key = buildKey(userId);

            // 使用ZSet存储，score=seq保证有序
            redisTemplate.opsForZSet().add(key, message, seq);

            // 设置过期时间
            redisTemplate.expire(key, MESSAGE_TTL_MINUTES, TimeUnit.MINUTES);

            // 限制消息数量，移除最旧的消息
            Long size = redisTemplate.opsForZSet().size(key);
            if (size != null && size > MAX_CACHED_MESSAGES) {
                long removeCount = size - MAX_CACHED_MESSAGES;
                redisTemplate.opsForZSet().removeRange(key, 0, removeCount - 1);
            }

            log.debug("[MessagePersistence] Saved message, userId={}, seq={}", userId, seq);

        } catch (Exception e) {
            log.error("[MessagePersistence] Failed to save message, userId={}, seq={}", userId, seq, e);
        }
    }

    /**
     * 批量保存消息
     *
     * @param userId 用户ID
     * @param messages 消息列表（JSONObject包含seq和data）
     */
    public void saveMessages(Long userId, List<JSONObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        try {
            String key = buildKey(userId);

            // 批量添加
            for (JSONObject msg : messages) {
                long seq = msg.getLongValue("seq");
                String json = msg.toJSONString();
                redisTemplate.opsForZSet().add(key, json, seq);
            }

            // 设置过期时间
            redisTemplate.expire(key, MESSAGE_TTL_MINUTES, TimeUnit.MINUTES);

            // 限制消息数量
            Long size = redisTemplate.opsForZSet().size(key);
            if (size != null && size > MAX_CACHED_MESSAGES) {
                long removeCount = size - MAX_CACHED_MESSAGES;
                redisTemplate.opsForZSet().removeRange(key, 0, removeCount - 1);
            }

            log.debug("[MessagePersistence] Batch saved {} messages, userId={}", messages.size(), userId);

        } catch (Exception e) {
            log.error("[MessagePersistence] Failed to batch save messages, userId={}", userId, e);
        }
    }

    /**
     * 获取指定序列号之后的消息（用于断线恢复）
     *
     * @param userId 用户ID
     * @param lastSeq 最后收到的序列号
     * @return 消息列表
     */
    public List<JSONObject> getMessagesAfter(Long userId, long lastSeq) {
        try {
            String key = buildKey(userId);

            // 查询 seq > lastSeq 的消息
            Set<Object> messages = redisTemplate.opsForZSet().rangeByScore(
                    key,
                    lastSeq + 1,
                    Double.MAX_VALUE
            );

            if (messages == null || messages.isEmpty()) {
                return new ArrayList<>();
            }

            List<JSONObject> result = new ArrayList<>();
            for (Object msg : messages) {
                JSONObject json = JSON.parseObject(msg.toString());
                result.add(json);
            }

            log.info("[MessagePersistence] Recovered {} messages, userId={}, lastSeq={}",
                    result.size(), userId, lastSeq);

            return result;

        } catch (Exception e) {
            log.error("[MessagePersistence] Failed to get messages, userId={}, lastSeq={}",
                    userId, lastSeq, e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取用户的所有缓存消息
     *
     * @param userId 用户ID
     * @return 消息列表
     */
    public List<JSONObject> getAllMessages(Long userId) {
        try {
            String key = buildKey(userId);

            // 查询所有消息
            Set<ZSetOperations.TypedTuple<Object>> messages = redisTemplate.opsForZSet()
                    .rangeWithScores(key, 0, -1);

            if (messages == null || messages.isEmpty()) {
                return new ArrayList<>();
            }

            List<JSONObject> result = new ArrayList<>();
            for (ZSetOperations.TypedTuple<Object> tuple : messages) {
                if (tuple.getValue() != null) {
                    JSONObject json = JSON.parseObject(tuple.getValue().toString());
                    result.add(json);
                }
            }

            log.debug("[MessagePersistence] Got {} messages, userId={}", result.size(), userId);

            return result;

        } catch (Exception e) {
            log.error("[MessagePersistence] Failed to get all messages, userId={}", userId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 清除用户的缓存消息
     *
     * @param userId 用户ID
     */
    public void clearMessages(Long userId) {
        try {
            String key = buildKey(userId);
            redisTemplate.delete(key);

            log.debug("[MessagePersistence] Cleared messages, userId={}", userId);

        } catch (Exception e) {
            log.error("[MessagePersistence] Failed to clear messages, userId={}", userId, e);
        }
    }

    /**
     * 获取用户的消息数量
     *
     * @param userId 用户ID
     * @return 消息数量
     */
    public long getMessageCount(Long userId) {
        try {
            String key = buildKey(userId);
            Long size = redisTemplate.opsForZSet().size(key);
            return size != null ? size : 0;

        } catch (Exception e) {
            log.error("[MessagePersistence] Failed to get message count, userId={}", userId, e);
            return 0;
        }
    }

    /**
     * 检查消息是否存在（去重）
     *
     * @param userId 用户ID
     * @param seq 序列号
     * @return true=存在，false=不存在
     */
    public boolean messageExists(Long userId, long seq) {
        try {
            String key = buildKey(userId);
            Long rank = redisTemplate.opsForZSet().rank(key, seq);
            return rank != null;

        } catch (Exception e) {
            log.error("[MessagePersistence] Failed to check message existence, userId={}, seq={}",
                    userId, seq, e);
            return false;
        }
    }

    /**
     * 构建Redis Key
     *
     * @param userId 用户ID
     * @return Redis Key
     */
    private String buildKey(Long userId) {
        return MESSAGE_PREFIX + userId + MESSAGE_SUFFIX;
    }

    /**
     * 获取持久化统计
     *
     * @return 统计信息
     */
    public PersistenceStats getStats() {
        // 获取所有用户的消息Key
        Set<String> keys = redisTemplate.keys(MESSAGE_PREFIX + "*" + MESSAGE_SUFFIX);
        int totalUsers = keys != null ? keys.size() : 0;

        long totalMessages = 0;
        if (keys != null) {
            for (String key : keys) {
                Long size = redisTemplate.opsForZSet().size(key);
                totalMessages += (size != null ? size : 0);
            }
        }

        return PersistenceStats.builder()
                .totalUsers(totalUsers)
                .totalMessages(totalMessages)
                .build();
    }

    /**
     * 持久化统计
     */
    @lombok.Builder
    @lombok.Data
    public static class PersistenceStats {
        private int totalUsers;
        private long totalMessages;
    }
}
