package com.exchange.tpsl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * Kafka 消费者配置
 *
 * 使用 MANUAL_IMMEDIATE 模式，确保消费者异常时 Kafka 不自动提交 offset，
 * 业务在确认处理成功后再手动 ack。
 */
@Configuration
public class KafkaConsumerConfig {

    @Bean("kafkaManualAckListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaManualAckListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
