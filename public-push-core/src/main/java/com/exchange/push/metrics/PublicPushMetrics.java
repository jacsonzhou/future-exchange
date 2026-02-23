package com.exchange.push.metrics;

import com.exchange.push.service.ConnectionManager;
import com.exchange.push.service.KafkaConsumerManager;
import com.exchange.push.service.SubscriptionManager;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 公有推送系统监控指标
 */
@Slf4j
@Component
public class PublicPushMetrics {

    @Autowired
    private MeterRegistry meterRegistry;
    
    @Autowired
    private ConnectionManager connectionManager;
    
    @Autowired
    private SubscriptionManager subscriptionManager;
    
    @Autowired
    private KafkaConsumerManager kafkaConsumerManager;

    @PostConstruct
    public void init() {
        // 注册自定义指标
        registerCustomGauges();
        
        log.info("[Metrics] Public Push metrics initialized");
    }

    private void registerCustomGauges() {
        // 连接指标
        Gauge.builder("publicpush.connections.active", 
                () -> connectionManager.getSessions().size())
            .description("Number of active WebSocket connections")
            .register(meterRegistry);
        
        // 订阅指标
        Gauge.builder("publicpush.subscriptions.total", 
                () -> (Number) subscriptionManager.getSubscriptionStats().get("totalSubscriptions"))
            .description("Total number of subscriptions")
            .register(meterRegistry);
        
        Gauge.builder("publicpush.subscriptions.channels", 
                () -> (Number) subscriptionManager.getSubscriptionStats().get("activeChannels"))
            .description("Number of active channels")
            .register(meterRegistry);
        
        // Kafka消费者指标
        Gauge.builder("publicpush.kafka.consumers", 
                () -> (Number) kafkaConsumerManager.getConsumerStats().get("activeConsumers"))
            .description("Number of active Kafka consumers")
            .register(meterRegistry);
    }

    /**
     * 定期输出统计日志
     */
    @Scheduled(fixedRate = 60000)
    public void logStatistics() {
        Map<String, Object> connectionStats = connectionManager.getConnectionStats();
        Map<String, Object> subscriptionStats = subscriptionManager.getSubscriptionStats();
        Map<String, Object> consumerStats = kafkaConsumerManager.getConsumerStats();
        
        log.info("[Stats] Connections: {}, Subscriptions: {}, Channels: {}, Kafka Consumers: {}",
                connectionStats.get("activeConnections"),
                subscriptionStats.get("totalSubscriptions"),
                subscriptionStats.get("activeChannels"),
                consumerStats.get("activeConsumers"));
    }
}
