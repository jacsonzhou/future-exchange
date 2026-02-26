package com.exchange.binance.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka配置类
 * 
 * 配置Kafka Producer和自动创建Topic
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * Kafka Producer配置
     */
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "1");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    /**
     * 币安深度数据Topic
     * 
     * 注意：实际使用时，每个symbol应该动态创建topic
     * 这里只是示例配置
     */
    @Bean
    public NewTopic binanceDepthTopic() {
        return TopicBuilder.name("market.ext.binance.depth.BTCUSDT")
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic binanceTradeTopic() {
        return TopicBuilder.name("market.ext.binance.trade.BTCUSDT")
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic binanceTickerTopic() {
        return TopicBuilder.name("market.ext.binance.ticker.BTCUSDT")
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic binanceKlineTopic() {
        return TopicBuilder.name("market.ext.binance.kline.BTCUSDT.1m")
                .partitions(1)
                .replicas(1)
                .build();
    }
}
