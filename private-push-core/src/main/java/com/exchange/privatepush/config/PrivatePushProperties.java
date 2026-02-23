package com.exchange.privatepush.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 私有推送配置属性
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "private.push")
public class PrivatePushProperties {
    
    /**
     * 服务端口
     */
    private int port = 8099;
    
    /**
     * 连接配置
     */
    private Connection connection = new Connection();
    
    /**
     * ACK配置
     */
    private Ack ack = new Ack();
    
    /**
     * 限流配置
     */
    private RateLimit rateLimit = new RateLimit();
    
    /**
     * Kafka配置
     */
    private Kafka kafka = new Kafka();
    
    @Data
    public static class Connection {
        /**
         * 每用户最大连接数
         */
        private int maxPerUser = 5;
        
        /**
         * 每IP最大连接数
         */
        private int maxPerIp = 100;
        
        /**
         * 心跳间隔 (秒)
         */
        private int heartbeatInterval = 30;
        
        /**
         * 心跳超时 (秒)
         */
        private int heartbeatTimeout = 60;
        
        /**
         * 会话超时 (分钟)
         */
        private int sessionTimeoutMinutes = 30;
    }
    
    @Data
    public static class Ack {
        /**
         * 是否启用ACK
         */
        private boolean enabled = true;
        
        /**
         * ACK超时 (毫秒)
         */
        private int timeoutMs = 5000;
        
        /**
         * 最大重试次数
         */
        private int maxRetry = 3;
        
        /**
         * 重试间隔 (毫秒)
         */
        private int retryIntervalMs = 1000;
    }
    
    @Data
    public static class RateLimit {
        /**
         * 每连接每秒最大消息数
         */
        private int messagesPerSecond = 100;
        
        /**
         * 每连接每秒最大订阅数
         */
        private int subscribePerSecond = 10;
    }
    
    @Data
    public static class Kafka {
        /**
         * 订单状态Topic
         */
        private String orderStateTopic = "private-order-state";
        
        /**
         * 账户变化Topic
         */
        private String accountChangeTopic = "private-account-change";
        
        /**
         * 持仓变化Topic
         */
        private String positionChangeTopic = "private-position-change";
        
        /**
         * 消费者组ID
         */
        private String consumerGroupId = "private-push-service";
    }
}
