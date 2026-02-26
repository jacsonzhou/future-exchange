package com.exchange.push.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 公有推送服务配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "public-push")
public class PublicPushProperties {
    
    private WebSocketConfig websocket = new WebSocketConfig();
    private SubscriptionConfig subscription = new SubscriptionConfig();
    private PushConfig push = new PushConfig();
    private RateLimitConfig rateLimit = new RateLimitConfig();
    private KafkaConfig kafka = new KafkaConfig();
    
    @Data
    public static class WebSocketConfig {
        private int maxConnections = 100000;
        private int maxConnectionsPerIp = 50;
        private int connectionRateLimit = 10;
        private long heartbeatIntervalMs = 30000;
        private long heartbeatTimeoutMs = 5000;
        private int sendBufferSize = 32768;
        private int receiveBufferSize = 32768;
    }
    
    @Data
    public static class SubscriptionConfig {
        private int maxSubscriptionsPerSession = 1024;
        private int subscriptionRateLimit = 10;
        private boolean autoResubscribe = true;
    }
    
    @Data
    public static class PushConfig {
        private long batchIntervalMs = 50;
        private int batchSize = 100;
        private int batchQueueSize = 10000;
        private boolean compressionEnabled = false;
        private int compressionThreshold = 1024;
    }
    
    @Data
    public static class RateLimitConfig {
        private boolean enabled = true;
        private IpConnectionRateLimit ipConnection = new IpConnectionRateLimit();
        private SessionMessageRateLimit sessionMessage = new SessionMessageRateLimit();
        private ChannelRateLimit channel = new ChannelRateLimit();
        
        @Data
        public static class IpConnectionRateLimit {
            private boolean enabled = true;
            private int maxConnections = 50;
            private int ratePerMinute = 10;
        }
        
        @Data
        public static class SessionMessageRateLimit {
            private boolean enabled = true;
            private int maxMessagesPerSecond = 1000;
            private int maxMessagesPerMinute = 10000;
        }
        
        @Data
        public static class ChannelRateLimit {
            private boolean enabled = true;
            private int maxMessagesPerSecond = 10000;
        }
    }
    
    @Data
    public static class KafkaConfig {
        private int consumerThreads = 8;
        private int consumerBatchSize = 500;
        private long pollTimeoutMs = 100;
        private DynamicConsumerConfig dynamicConsumer = new DynamicConsumerConfig();
        private TopicConfig topics = new TopicConfig();
        
        @Data
        public static class DynamicConsumerConfig {
            private boolean enabled = true;
            private int idleTimeoutMinutes = 5;
        }
        
        @Data
        public static class TopicConfig {
            private String trade = "market.trade.{symbol}";
            private String aggTrade = "market.aggtrade.{symbol}";
            private String depth = "market.depth.{symbol}";
            private String kline = "market.kline.{symbol}.{interval}";
            private String ticker = "market.ticker.{symbol}";
            private String tickerAll = "market.ticker.all";
            private String markPrice = "market.markprice.{symbol}";
            private String markPriceAll = "market.markprice.all";
            private String extTrade = "market.ext.{source}.trade.{symbol}";
            private String extDepth = "market.ext.{source}.depth.{symbol}";
            private String extKline = "market.ext.{source}.kline.{symbol}.{interval}";
            private String extTicker = "market.ext.{source}.ticker.{symbol}";
        }
    }
}
