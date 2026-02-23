package com.exchange.oms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * OMS提交模式配置
 *
 * 支持两种模式：
 * 1. kafka - 异步通过Kafka发送（生产推荐）
 * 2. feign - 同步通过Feign直连（降级/测试）
 *
 * 配置项：
 * exchange.oms.submit-mode: kafka | feign
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "exchange.oms")
public class OmsSubmitModeConfig {

    /**
     * 提交模式：kafka（默认）| feign
     */
    private String submitMode = "kafka";

    /**
     * 是否使用Kafka模式
     */
    public boolean isKafkaMode() {
        return "kafka".equalsIgnoreCase(submitMode);
    }

    /**
     * 是否使用Feign模式
     */
    public boolean isFeignMode() {
        return "feign".equalsIgnoreCase(submitMode);
    }
}
