package com.exchange.snapshot.config;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * Kafka 消费者配置（生产级）
 *
 * 🔥 核心修复：
 * 将默认 ackMode 从 BATCH 改为 MANUAL_IMMEDIATE，确保 offset 仅在业务事务
 * 成功提交后才被确认，防止消息丢失（资金数据）。
 *
 * 风险背景：
 * - Spring Kafka 默认 ackMode = BATCH，容器会在批次边界自动提交 offset
 * - 若某条消息事务失败但同批前面消息成功，较新的 offset 可能被提前提交
 * - 导致失败消息被跳过，account_snapshot 与 ledger_entry 出现永久不一致
 */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<?, ?> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> kafkaConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, kafkaConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
