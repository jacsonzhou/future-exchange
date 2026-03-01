package com.exchange.liquidation.consumer;

import com.alibaba.fastjson2.JSON;
import com.exchange.liquidation.dto.LiquidationTriggerEvent;
import com.exchange.liquidation.service.LiquidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 强平触发事件消费者
 * 
 * 消费 Margin-Mode-Core 发布的 liquidation-trigger-topic
 * 这是强平流程的入口
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiquidationTriggerConsumer {
    
    private final LiquidationService liquidationService;
    
    /**
     * 消费强平触发事件
     * 
     * 使用手动ACK模式，确保消息不丢失
     * 批量消费提高吞吐量
     */
    @KafkaListener(
        topics = "${kafka.topic.liquidation-trigger:liquidation-trigger-topic}",
        groupId = "${spring.kafka.consumer.group-id:liquidation-service-group}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(
            String message,
            Acknowledgment ack,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key
    ) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("[LiquidationTriggerConsumer] Received liquidation trigger event, partition={}, offset={}, key={}",
                    partition, offset, key);

            // 解析事件
            LiquidationTriggerEvent event = JSON.parseObject(message, LiquidationTriggerEvent.class);

            // 处理强平
            liquidationService.processLiquidation(event);

            // 只有成功才手动确认
            if (ack != null) {
                ack.acknowledge();
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("✅ [LiquidationTriggerConsumer] Processed successfully, partition={}, offset={}, duration={}ms",
                    partition, offset, duration);

        } catch (Exception e) {
            log.error("❌ [LiquidationTriggerConsumer] CRITICAL: Failed to process liquidation trigger, " +
                            "partition={}, offset={}, userId={}, positionId={}, error={}",
                    partition, offset, extractUserId(message), extractPositionId(message), e.getMessage(), e);

            // 严重问题：处理失败不能ACK，必须抛异常让Kafka重试
            throw new RuntimeException("Failed to process liquidation trigger event at offset " +
                    offset + ", will retry", e);
        }
    }

    /**
     * 从消息中提取userId用于日志
     */
    private Long extractUserId(String message) {
        try {
            LiquidationTriggerEvent event = JSON.parseObject(message, LiquidationTriggerEvent.class);
            return event != null ? event.getUserId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从消息中提取positionId用于日志
     */
    private Long extractPositionId(String message) {
        try {
            LiquidationTriggerEvent event = JSON.parseObject(message, LiquidationTriggerEvent.class);
            return event != null ? event.getPositionId() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
