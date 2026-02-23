package com.exchange.push.controller;

import com.exchange.push.service.ConnectionManager;
import com.exchange.push.service.KafkaConsumerManager;
import com.exchange.push.service.SubscriptionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 公有推送服务管理接口
 */
@RestController
@RequestMapping("/api/v1")
public class PublicPushController {

    @Autowired
    private ConnectionManager connectionManager;
    
    @Autowired
    private SubscriptionManager subscriptionManager;
    
    @Autowired
    private KafkaConsumerManager kafkaConsumerManager;

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", "public-push-service");
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    /**
     * 服务统计
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new HashMap<>();
        
        result.put("connections", connectionManager.getConnectionStats());
        result.put("subscriptions", subscriptionManager.getSubscriptionStats());
        result.put("kafka", kafkaConsumerManager.getConsumerStats());
        
        return result;
    }

    /**
     * 连接统计
     */
    @GetMapping("/stats/connections")
    public Map<String, Object> connectionStats() {
        return connectionManager.getConnectionStats();
    }

    /**
     * 订阅统计
     */
    @GetMapping("/stats/subscriptions")
    public Map<String, Object> subscriptionStats() {
        return subscriptionManager.getSubscriptionStats();
    }

    /**
     * Kafka消费者统计
     */
    @GetMapping("/stats/kafka")
    public Map<String, Object> kafkaStats() {
        return kafkaConsumerManager.getConsumerStats();
    }
}
