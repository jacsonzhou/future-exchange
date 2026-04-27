package com.exchange.adl.consumer;

import com.exchange.adl.event.LiquidationCompletedEvent;
import com.exchange.adl.mapper.BankruptcyRecordMapper;
import com.exchange.adl.service.AdlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 强平事件消费者
 *
 * 监听Liquidation Service发布的强平完成事件
 * 检查是否需要触发ADL
 */
@Slf4j
@Component
public class LiquidationEventConsumer {

    @Autowired
    private AdlService adlService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BankruptcyRecordMapper bankruptcyRecordMapper;

    @Value("${adl.liquidation-consumer.dedup-enabled:true}")
    private boolean dedupEnabled;

    /**
     * 消费强平完成事件
     *
     * 使用 MANUAL_IMMEDIATE ack 模式：
     * - 幂等重复消息（DuplicateKeyException）：安全跳过，立即 ack
     * - 业务处理失败：抛出异常，不 ack，Kafka 自动重试
     */
    @KafkaListener(
            topics = "liquidation-completed-topic",
            groupId = "adl-service-group",
            containerFactory = "kafkaManualAckListenerContainerFactory"
    )
    public void onLiquidationCompleted(String message, Acknowledgment ack) {
        try {
            log.info("Received liquidation completed event: {}", message);

            // 解析事件
            LiquidationCompletedEvent event = objectMapper.readValue(message, LiquidationCompletedEvent.class);

            boolean bankrupt = Boolean.TRUE.equals(event.getIsBankrupt());
            boolean adlRequired = Boolean.TRUE.equals(event.getAdlRequired());
            Long bankruptLoss = event.getBankruptLoss();

            // 必须同时满足：发生穿仓 + 需要ADL + 有损失
            if (!bankrupt || !adlRequired || bankruptLoss == null || bankruptLoss <= 0) {
                log.debug("Skip ADL trigger for liquidation event: liquidationId={}, bankrupt={}, adlRequired={}, bankruptLoss={}",
                        event.getLiquidationId(), bankrupt, adlRequired, bankruptLoss);
                ack.acknowledge();
                return;
            }

            Long bankruptQty = event.getBankruptQty();
            if (bankruptQty == null || bankruptQty <= 0) {
                bankruptQty = event.getExecutedQty();
            }

            if (dedupEnabled && isDuplicateLiquidation(event.getLiquidationId())) {
                log.info("Skip duplicate liquidation completed event, liquidationId={}", event.getLiquidationId());
                ack.acknowledge();
                return;
            }

            log.warn("Bankruptcy detected and ADL required: liquidationId={}, userId={}, loss={}, qty={}",
                    event.getLiquidationId(), event.getUserId(), bankruptLoss, bankruptQty);

            adlService.onLiquidationCompleted(
                    event.getLiquidationId(),
                    event.getUserId(),
                    event.getSymbol(),
                    event.getSide(),
                    event.getBankruptPrice(),
                    bankruptQty,
                    bankruptLoss
            );

            // 业务处理成功，手动确认
            ack.acknowledge();
            log.info("ADL processing completed for liquidationId={}", event.getLiquidationId());

        } catch (DuplicateKeyException e) {
            // bankruptcy_record 存在 uk_liquidation，重复消息视为幂等命中，安全 ack
            log.warn("Duplicate liquidation completed message ignored by unique key, message={}", message);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process liquidation completed event, message={}", message, e);
            // 不 ack，让 Kafka 重试；不吞异常，确保不丢失消息
            throw new RuntimeException("ADL processing failed", e);
        }
    }

    /**
     * 消费强平触发事件（可选，用于提前准备ADL排名）
     */
    @KafkaListener(
            topics = "liquidation-trigger-topic",
            groupId = "adl-service-group",
            containerFactory = "kafkaManualAckListenerContainerFactory"
    )
    public void onLiquidationTrigger(String message, Acknowledgment ack) {
        try {
            log.debug("Received liquidation trigger event: {}", message);
            // 可以在此处提前计算ADL排名，优化性能
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process liquidation trigger event, message={}", message, e);
            throw new RuntimeException("Liquidation trigger processing failed", e);
        }
    }

    private boolean isDuplicateLiquidation(String liquidationId) {
        if (liquidationId == null || liquidationId.isBlank()) {
            return false;
        }
        return bankruptcyRecordMapper.selectByLiquidationId(liquidationId) != null;
    }
}
