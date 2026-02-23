package com.exchange.adl.consumer;

import com.exchange.adl.event.LiquidationCompletedEvent;
import com.exchange.adl.service.AdlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
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

    /**
     * 消费强平完成事件
     */
    @KafkaListener(topics = "liquidation-completed-topic", groupId = "adl-service-group")
    public void onLiquidationCompleted(String message) {
        try {
            log.info("Received liquidation completed event: {}", message);

            // 解析事件
            LiquidationCompletedEvent event = objectMapper.readValue(message, LiquidationCompletedEvent.class);

            // 检查是否穿仓
            if (event.getIsBankrupt() != null && event.getIsBankrupt()) {
                log.warn("Bankruptcy detected: liquidationId={}, userId={}, loss={}",
                        event.getLiquidationId(), event.getUserId(), event.getBankruptLoss());

                // 处理穿仓事件，检查是否需要ADL
                adlService.onLiquidationCompleted(
                        event.getLiquidationId(),
                        event.getUserId(),
                        event.getSymbol(),
                        event.getSide(),
                        event.getBankruptPrice(),
                        event.getRemainingQty(),
                        event.getBankruptLoss()
                );
            } else {
                log.debug("Liquidation completed without bankruptcy: liquidationId={}",
                        event.getLiquidationId());
            }

        } catch (Exception e) {
            log.error("Failed to process liquidation completed event", e);
            // 不抛出异常，避免阻塞消费者
        }
    }

    /**
     * 消费强平触发事件（可选，用于提前准备ADL排名）
     */
    @KafkaListener(topics = "liquidation-trigger-topic", groupId = "adl-service-group")
    public void onLiquidationTrigger(String message) {
        try {
            log.debug("Received liquidation trigger event: {}", message);
            // 可以在此处提前计算ADL排名，优化性能
        } catch (Exception e) {
            log.error("Failed to process liquidation trigger event", e);
        }
    }
}
