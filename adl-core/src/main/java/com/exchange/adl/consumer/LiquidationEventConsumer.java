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

            boolean bankrupt = Boolean.TRUE.equals(event.getIsBankrupt());
            boolean adlRequired = Boolean.TRUE.equals(event.getAdlRequired());
            Long bankruptLoss = event.getBankruptLoss();

            // 必须同时满足：发生穿仓 + 需要ADL + 有损失
            if (!bankrupt || !adlRequired || bankruptLoss == null || bankruptLoss <= 0) {
                log.debug("Skip ADL trigger for liquidation event: liquidationId={}, bankrupt={}, adlRequired={}, bankruptLoss={}",
                        event.getLiquidationId(), bankrupt, adlRequired, bankruptLoss);
                return;
            }

            Long bankruptQty = event.getBankruptQty();
            if (bankruptQty == null || bankruptQty <= 0) {
                bankruptQty = event.getExecutedQty();
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
