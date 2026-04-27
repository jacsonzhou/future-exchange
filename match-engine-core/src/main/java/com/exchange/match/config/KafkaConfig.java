package com.exchange.match.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Kafka配置
 * 
 * 🔥 交易所级双通道架构
 */
@EnableKafka
@Configuration
public class KafkaConfig {
    
    /**
     * ObjectMapper用于序列化/反序列化
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}






