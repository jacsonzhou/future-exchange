package com.exchange.liquidation.consumer;

import com.alibaba.fastjson2.JSON;
import com.exchange.liquidation.dto.LiquidationTriggerEvent;
import com.exchange.liquidation.service.LiquidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

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
    public void consume(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;

        for (ConsumerRecord<String, String> record : records) {
            try {
                log.info("[LiquidationTriggerConsumer] Received liquidation trigger event, " +
                        "partition={}, offset={}, key={}",
                        record.partition(), record.offset(), record.key());

                // 解析事件
                LiquidationTriggerEvent event = JSON.parseObject(record.value(), LiquidationTriggerEvent.class);

                // 处理强平
                liquidationService.processLiquidation(event);

                successCount++;

            } catch (Exception e) {
                failCount++;
                log.error("❌ [LiquidationTriggerConsumer] CRITICAL: Failed to process liquidation trigger, " +
                        "partition={}, offset={}, userId={}, positionId={}, error={}",
                        record.partition(), record.offset(),
                        extractUserId(record.value()), extractPositionId(record.value()),
                        e.getMessage(), e);

                // 严重问题：如果处理失败，不能ACK，必须抛出异常让Kafka重试
                // 防止强平触发事件丢失导致用户资金损失
                throw new RuntimeException("Failed to process liquidation trigger event at offset " +
                        record.offset() + ", will retry", e);
            }
        }

        // 只有全部成功才手动确认
        ack.acknowledge();

        long duration = System.currentTimeMillis() - startTime;
        log.info("✅ [LiquidationTriggerConsumer] Batch processed successfully, total={}, duration={}ms",
                records.size(), duration);
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
