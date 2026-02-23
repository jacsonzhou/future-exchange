package com.exchange.push.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.push.config.PublicPushProperties;
import com.exchange.push.model.ChannelType;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Kafka动态消费者管理器
 * 
 * 职责：
 * - 按需启动消费者（有订阅时才消费）
 * - 每个频道独立消费者
 * - 空闲消费者自动清理
 */
@Slf4j
@Service
public class KafkaConsumerManager {

    @Autowired
    private PublicPushProperties properties;
    
    @Autowired
    private ConsumerFactory<String, String> consumerFactory;
    
    @Autowired
    private ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory;
    
    @Autowired
    @Lazy
    private SubscriptionManager subscriptionManager;
    
    @Autowired
    private MessageDispatcher messageDispatcher;

    // 频道 -> 消费者容器
    private final Map<String, MessageListenerContainer> activeConsumers = new ConcurrentHashMap<>();
    
    // 频道 -> 最后活跃时间
    private final Map<String, Long> lastActivityTime = new ConcurrentHashMap<>();
    
    // 频道 -> 消息计数
    private final Map<String, Long> messageCounters = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("[KafkaConsumerManager] Initialized");
    }

    /**
     * 确保频道消费者已启动
     */
    public synchronized void ensureConsumerStarted(String channel) {
        if (activeConsumers.containsKey(channel)) {
            // 更新活跃时间
            lastActivityTime.put(channel, System.currentTimeMillis());
            return;
        }
        
        // 解析topic
        String topic = channelToTopic(channel);
        String groupId = buildGroupId(channel);
        
        try {
            // 创建消费者容器 (Spring Kafka 3.x API)
            MessageListenerContainer container = kafkaListenerContainerFactory.createContainer(topic);
            container.getContainerProperties().setGroupId(groupId);
            container.getContainerProperties().setMessageListener((org.springframework.kafka.listener.MessageListener<String, String>) record -> {
                onMessage(channel, record);
            });
            container.start();
            
            activeConsumers.put(channel, container);
            lastActivityTime.put(channel, System.currentTimeMillis());
            messageCounters.put(channel, 0L);
            
            log.info("[KafkaConsumer] Started consumer for channel: {} -> topic: {}", channel, topic);
            
        } catch (Exception e) {
            log.error("[KafkaConsumer] Failed to start consumer for channel {}: {}", channel, e.getMessage());
        }
    }

    /**
     * 停止频道消费者
     */
    public synchronized void stopConsumer(String channel) {
        MessageListenerContainer container = activeConsumers.remove(channel);
        if (container != null) {
            container.stop();
            lastActivityTime.remove(channel);
            messageCounters.remove(channel);
            log.info("[KafkaConsumer] Stopped consumer for channel: {}", channel);
        }
    }

    /**
     * 处理消息
     */
    private void onMessage(String channel, ConsumerRecord<String, String> record) {
        try {
            // 更新统计
            lastActivityTime.put(channel, System.currentTimeMillis());
            messageCounters.merge(channel, 1L, Long::sum);
            
            log.info("[PUSH-LINK] <<< Kafka consumed, channel={}, partition={}, offset={}, size={}bytes",
                channel, record.partition(), record.offset(), record.value().length());
            
            // 解析消息
            JSONObject message = JSON.parseObject(record.value());
            
            // 验证消息格式
            if (!validateMessageFormat(channel, message)) {
                log.warn("[KafkaConsumer] Invalid message format for channel: {}, message: {}", 
                        channel, message);
                return;
            }
            
            // 分发消息
            messageDispatcher.broadcast(channel, message);
            
        } catch (Exception e) {
            log.error("[KafkaConsumer] Failed to process message for channel {}: {}", channel, e.getMessage());
        }
    }

    /**
     * 验证消息格式
     * 
     * 必需字段：
     * - e: 事件类型
     * - E: 事件时间
     * - s: 交易对（部分消息需要）
     */
    private boolean validateMessageFormat(String channel, JSONObject message) {
        // 检查必需字段
        if (!message.containsKey("e") || !message.containsKey("E")) {
            return false;
        }
        
        // 根据频道类型验证特定字段
        ChannelType channelType = ChannelType.fromChannel(channel);
        switch (channelType) {
            case TRADE:
            case AGG_TRADE:
            case TICKER:
            case MARK_PRICE:
                // 需要交易对字段
                if (!message.containsKey("s")) {
                    return false;
                }
                break;
            case DEPTH:
                // 深度消息需要序列号字段
                if (!message.containsKey("U") || !message.containsKey("u")) {
                    return false;
                }
                break;
            case KLINE:
                // K线消息需要k字段
                if (!message.containsKey("k")) {
                    return false;
                }
                break;
            default:
                // 其他类型暂不验证
                break;
        }
        
        return true;
    }

    /**
     * 频道转Topic（使用配置的Topic模板）
     */
    private String channelToTopic(String channel) {
        PublicPushProperties.KafkaConfig.TopicConfig topicConfig = properties.getKafka().getTopics();
        
        if (channel.startsWith("trade.")) {
            String symbol = channel.substring(6);
            return topicConfig.getTrade().replace("{symbol}", symbol);
        } else if (channel.startsWith("aggTrade.")) {
            String symbol = channel.substring(9);
            return topicConfig.getAggTrade().replace("{symbol}", symbol);
        } else if (channel.startsWith("depth.")) {
            String symbol = channel.substring(6).replace("@100ms", "").replace("@500ms", "");
            return topicConfig.getDepth().replace("{symbol}", symbol);
        } else if (channel.startsWith("kline.")) {
            // kline.{symbol}.{interval} -> market.kline.{symbol}.{interval}
            // 例如: kline.BTCUSDT.1m -> market.kline.BTCUSDT.1m
            String rest = channel.substring(6); // 去掉 "kline."
            int dotIndex = rest.lastIndexOf('.');
            if (dotIndex > 0) {
                String symbol = rest.substring(0, dotIndex); // "BTCUSDT"
                String interval = rest.substring(dotIndex + 1); // "1m"
                return topicConfig.getKline()
                        .replace("{symbol}", symbol)
                        .replace("{interval}", interval);
            }
        } else if (channel.startsWith("ticker.")) {
            String symbol = channel.substring(7);
            return topicConfig.getTicker().replace("{symbol}", symbol);
        } else if (channel.startsWith("markPrice.")) {
            String symbol = channel.substring(10);
            return topicConfig.getMarkPrice().replace("{symbol}", symbol);
        } else if (channel.equals("ticker@arr")) {
            return topicConfig.getTickerAll();
        } else if (channel.equals("markPrice@arr")) {
            return topicConfig.getMarkPriceAll();
        }
        
        // 默认：使用频道名作为Topic后缀
        return "market." + channel;
    }

    /**
     * 构建消费者组ID
     */
    private String buildGroupId(String channel) {
        return "public-push-service-" + channel.replace(".", "-").replace("@", "-");
    }

    /**
     * 清理空闲消费者（定时任务）
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupIdleConsumers() {
        if (!properties.getKafka().getDynamicConsumer().isEnabled()) {
            return;
        }
        
        long idleTimeoutMs = properties.getKafka().getDynamicConsumer().getIdleTimeoutMinutes() * 60 * 1000;
        long now = System.currentTimeMillis();
        
        Set<String> toRemove = new CopyOnWriteArraySet<>();
        
        lastActivityTime.forEach((channel, lastTime) -> {
            // 检查是否有活跃订阅
            int subscriberCount = subscriptionManager.getSubscriberCount(channel);
            
            if (subscriberCount == 0 && now - lastTime > idleTimeoutMs) {
                toRemove.add(channel);
            }
        });
        
        toRemove.forEach(this::stopConsumer);
        
        if (!toRemove.isEmpty()) {
            log.info("[KafkaConsumer] Cleaned up {} idle consumers", toRemove.size());
        }
    }

    /**
     * 获取消费者统计
     */
    public Map<String, Object> getConsumerStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("activeConsumers", activeConsumers.size());
        
        Map<String, Long> channelStats = new ConcurrentHashMap<>();
        messageCounters.forEach(channelStats::put);
        stats.put("messageCounts", channelStats);
        
        return stats;
    }
}
