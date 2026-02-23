package com.exchange.liquidation.producer;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exchange.liquidation.dto.LiquidationCompletedEvent;
import com.exchange.liquidation.entity.LiquidationEvent;
import com.exchange.liquidation.mapper.LiquidationEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 强平完成事件发布器
 *
 * 使用本地事件表保证事件发布的可靠性：
 * 1. 先写入本地事件表（与业务同事务）
 * 2. 异步发送到Kafka
 * 3. 发送成功后更新事件状态
 * 4. 发送失败由定时任务重试
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiquidationEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final LiquidationEventMapper eventMapper;

    @Value("${kafka.topic.liquidation-completed:liquidation-completed-topic}")
    private String liquidationCompletedTopic;

    /**
     * 发布强平完成事件
     *
     * 关键：先写本地事件表，再异步发送Kafka，保证事件不丢失
     *
     * @param event 强平完成事件
     */
    @Transactional(rollbackFor = Exception.class)
    public void publishLiquidationCompleted(LiquidationCompletedEvent event) {
        log.info("📤 [LiquidationEventProducer] Publishing liquidation completed event, " +
                "liquidationId={}, userId={}, positionId={}, isBankrupt={}, adlRequired={}",
                event.getLiquidationId(), event.getUserId(), event.getPositionId(),
                event.getIsBankrupt(), event.getAdlRequired());

        // 1. 先写入本地事件表（与业务同事务，保证可靠性）
        String eventId = saveLocalEvent(event);

        // 2. 异步发送到Kafka
        sendToKafka(eventId, event);
    }

    /**
     * 保存到本地事件表
     *
     * 与业务同事务，保证事件不丢失
     */
    private String saveLocalEvent(LiquidationCompletedEvent event) {
        String eventId = generateEventId(event.getLiquidationId());

        // 检查是否已存在（防止重复）
        LambdaQueryWrapper<LiquidationEvent> query = new LambdaQueryWrapper<>();
        query.eq(LiquidationEvent::getLiquidationId, event.getLiquidationId())
             .eq(LiquidationEvent::getEventType, LiquidationEvent.EventType.LIQUIDATION_COMPLETED);

        LiquidationEvent existing = eventMapper.selectOne(query);
        if (existing != null) {
            log.warn("⚠️ Event already exists, eventId={}, liquidationId={}",
                    existing.getEventId(), event.getLiquidationId());
            return existing.getEventId();
        }

        // 创建本地事件记录
        LiquidationEvent localEvent = new LiquidationEvent();
        localEvent.setEventId(eventId);
        localEvent.setLiquidationId(event.getLiquidationId());
        localEvent.setEventType(LiquidationEvent.EventType.LIQUIDATION_COMPLETED);
        localEvent.setTopic(liquidationCompletedTopic);
        localEvent.setEventData(JSON.toJSONString(event));
        localEvent.setSendStatus(LiquidationEvent.SendStatus.PENDING);
        localEvent.setRetryCount(0);
        localEvent.setMaxRetry(5);
        localEvent.setNextRetryTime(System.currentTimeMillis());
        localEvent.setCreatedAt(System.currentTimeMillis());
        localEvent.setUpdatedAt(System.currentTimeMillis());

        eventMapper.insert(localEvent);

        log.info("✅ Local event saved, eventId={}, liquidationId={}", eventId, event.getLiquidationId());
        return eventId;
    }

    /**
     * 发送到Kafka
     *
     * 异步发送，失败由定时任务重试
     */
    public void sendToKafka(String eventId, LiquidationCompletedEvent event) {
        try {
            // 序列化事件
            String value = JSON.toJSONString(event);

            // 使用liquidationId作为key，保证同一强平的事件顺序
            String key = event.getLiquidationId();

            // 异步发送到Kafka
            CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(liquidationCompletedTopic, key, value);

            // 回调处理
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    // 发送成功，更新事件状态
                    markEventSent(eventId, result);
                } else {
                    // 发送失败，记录错误，等待重试
                    markEventFailed(eventId, ex);
                }
            });

        } catch (Exception e) {
            log.error("❌ [LiquidationEventProducer] Send to Kafka error, eventId={}, liquidationId={}",
                    eventId, event.getLiquidationId(), e);
            markEventFailed(eventId, e);
        }
    }

    /**
     * 标记事件发送成功
     */
    private void markEventSent(String eventId, SendResult<String, String> result) {
        try {
            LiquidationEvent event = eventMapper.selectOne(
                new LambdaQueryWrapper<LiquidationEvent>()
                    .eq(LiquidationEvent::getEventId, eventId)
            );

            if (event != null) {
                event.setSendStatus(LiquidationEvent.SendStatus.SENT);
                event.setSentAt(System.currentTimeMillis());
                event.setUpdatedAt(System.currentTimeMillis());
                eventMapper.updateById(event);

                log.info("✅ [LiquidationEventProducer] Event sent successfully, " +
                        "eventId={}, liquidationId={}, partition={}, offset={}",
                        eventId, event.getLiquidationId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        } catch (Exception e) {
            log.error("❌ Failed to update event status, eventId={}", eventId, e);
        }
    }

    /**
     * 标记事件发送失败
     *
     * 计算下次重试时间（指数退避）
     */
    private void markEventFailed(String eventId, Throwable ex) {
        try {
            LiquidationEvent event = eventMapper.selectOne(
                new LambdaQueryWrapper<LiquidationEvent>()
                    .eq(LiquidationEvent::getEventId, eventId)
            );

            if (event != null) {
                int retryCount = event.getRetryCount() + 1;
                event.setRetryCount(retryCount);

                // 指数退避：1s, 2s, 4s, 8s, 16s
                long backoffSeconds = (long) Math.pow(2, retryCount);
                long nextRetryTime = System.currentTimeMillis() + backoffSeconds * 1000;
                event.setNextRetryTime(nextRetryTime);

                event.setErrorMsg(ex.getMessage());
                event.setUpdatedAt(System.currentTimeMillis());

                // 达到最大重试次数，标记为FAILED
                if (retryCount >= event.getMaxRetry()) {
                    event.setSendStatus(LiquidationEvent.SendStatus.FAILED);
                    log.error("❌ [LiquidationEventProducer] Event FAILED after {} retries, " +
                            "eventId={}, liquidationId={}, MANUAL INTERVENTION REQUIRED",
                            retryCount, eventId, event.getLiquidationId());
                } else {
                    log.warn("⚠️ [LiquidationEventProducer] Event send failed, will retry in {}s, " +
                            "eventId={}, liquidationId={}, retryCount={}/{}",
                            backoffSeconds, eventId, event.getLiquidationId(), retryCount, event.getMaxRetry());
                }

                eventMapper.updateById(event);
            }
        } catch (Exception e) {
            log.error("❌ Failed to mark event as failed, eventId={}", eventId, e);
        }
    }

    /**
     * 生成事件ID
     */
    private String generateEventId(String liquidationId) {
        return "EVT_" + liquidationId + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
}

