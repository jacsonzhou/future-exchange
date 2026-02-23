package com.exchange.liquidation.scheduler;

import com.alibaba.fastjson2.JSON;
import com.exchange.liquidation.dto.LiquidationCompletedEvent;
import com.exchange.liquidation.entity.LiquidationEvent;
import com.exchange.liquidation.mapper.LiquidationEventMapper;
import com.exchange.liquidation.producer.LiquidationEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 事件重试调度器
 *
 * 定时重试未发送成功的事件，保证事件最终一致性
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventRetryScheduler {

    private final LiquidationEventMapper eventMapper;
    private final LiquidationEventProducer eventProducer;

    /**
     * 每5秒执行一次，重试待发送的事件
     */
    @Scheduled(fixedDelay = 5000)
    public void retryPendingEvents() {
        try {
            long now = System.currentTimeMillis();

            // 查询需要重试的事件（最多100条）
            List<LiquidationEvent> pendingEvents = eventMapper.selectPendingEvents(now, 100);

            if (pendingEvents.isEmpty()) {
                return;
            }

            log.info("🔄 [EventRetryScheduler] Found {} pending events to retry", pendingEvents.size());

            for (LiquidationEvent event : pendingEvents) {
                try {
                    // 解析事件数据
                    LiquidationCompletedEvent completedEvent = JSON.parseObject(
                            event.getEventData(), LiquidationCompletedEvent.class);

                    log.info("🔄 Retrying event, eventId={}, liquidationId={}, retryCount={}/{}",
                            event.getEventId(), event.getLiquidationId(),
                            event.getRetryCount(), event.getMaxRetry());

                    // 重新发送到Kafka
                    eventProducer.sendToKafka(event.getEventId(), completedEvent);

                } catch (Exception e) {
                    log.error("❌ Failed to retry event, eventId={}, liquidationId={}",
                            event.getEventId(), event.getLiquidationId(), e);
                }
            }

        } catch (Exception e) {
            log.error("❌ [EventRetryScheduler] Error during retry", e);
        }
    }

    /**
     * 每小时执行一次，告警失败的事件
     */
    @Scheduled(fixedRate = 3600000)
    public void alertFailedEvents() {
        try {
            // 查询失败的事件（达到最大重试次数）
            List<LiquidationEvent> failedEvents = eventMapper.selectFailedEvents(100);

            if (!failedEvents.isEmpty()) {
                log.error("🚨 [EventRetryScheduler] ALERT: {} events FAILED after max retries, " +
                        "MANUAL INTERVENTION REQUIRED", failedEvents.size());

                for (LiquidationEvent event : failedEvents) {
                    log.error("🚨 Failed event: eventId={}, liquidationId={}, retryCount={}, error={}",
                            event.getEventId(), event.getLiquidationId(),
                            event.getRetryCount(), event.getErrorMsg());
                }

                // TODO: 发送告警到运维平台（钉钉、邮件等）
            }

        } catch (Exception e) {
            log.error("❌ [EventRetryScheduler] Error during alert", e);
        }
    }
}
