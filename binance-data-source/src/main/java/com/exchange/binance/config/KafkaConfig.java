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
import org.springframework.kafka.core.KafkaAdmin;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private final BinanceDataSourceConfig dataSourceConfig;

    public KafkaConfig(BinanceDataSourceConfig dataSourceConfig) {
        this.dataSourceConfig = dataSourceConfig;
    }

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
     * 按配置的交易对动态创建外部行情 Topic。
     */
    @Bean
    public KafkaAdmin.NewTopics binanceMarketTopics() {
        String source = dataSourceConfig.getSource();
        List<String> symbols = dataSourceConfig.getSymbols();
        List<String> intervals = dataSourceConfig.getKlineIntervals();

        Set<String> names = new LinkedHashSet<>();
        if (symbols != null) {
            for (String symbol : symbols) {
                if (symbol == null || symbol.isBlank()) {
                    continue;
                }
                String normalized = symbol.trim().toUpperCase();
                names.add(String.format(dataSourceConfig.getKafka().getDepthTopicFormat(), source, normalized));
                names.add(String.format(dataSourceConfig.getKafka().getTradeTopicFormat(), source, normalized));
                names.add(String.format(dataSourceConfig.getKafka().getTickerTopicFormat(), source, normalized));

                if (intervals != null) {
                    for (String interval : intervals) {
                        if (interval == null || interval.isBlank()) {
                            continue;
                        }
                        names.add(String.format(
                                dataSourceConfig.getKafka().getKlineTopicFormat(),
                                source,
                                normalized,
                                interval.trim()
                        ));
                    }
                }
            }
        }

        List<NewTopic> topics = new ArrayList<>(names.size());
        for (String name : names) {
            topics.add(TopicBuilder.name(name)
                    .partitions(1)
                    .replicas(1)
                    .build());
        }
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }
}
