package com.exchange.marketmaker.kafka.producer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 做市商事件生产者
 */
@Slf4j
@Component
public class MmEventProducer {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    /**
     * 发送做市商状态变更事件
     */
    public void sendMmStatusChangeEvent(Long userId, String oldStatus, String newStatus) {
        String topic = "mm-status-change-topic";
        String message = String.format("{\"userId\":%d,\"oldStatus\":\"%s\",\"newStatus\":\"%s\",\"timestamp\":%d}",
                userId, oldStatus, newStatus, System.currentTimeMillis());

        kafkaTemplate.send(topic, message);
        log.info("[MM-Kafka] Send MM status change event, userId={}, oldStatus={}, newStatus={}",
                userId, oldStatus, newStatus);
    }

    /**
     * 发送做市商考核结果事件
     */
    public void sendMmPerformanceEvent(Long userId, String symbol, String date, Boolean isQualified) {
        String topic = "mm-performance-topic";
        String message = String.format("{\"userId\":%d,\"symbol\":\"%s\",\"date\":\"%s\",\"isQualified\":%b,\"timestamp\":%d}",
                userId, symbol, date, isQualified, System.currentTimeMillis());

        kafkaTemplate.send(topic, message);
        log.info("[MM-Kafka] Send MM performance event, userId={}, symbol={}, isQualified={}",
                userId, symbol, isQualified);
    }

    /**
     * 发送做市商告警事件
     */
    public void sendMmAlertEvent(Long userId, String alertType, String message) {
        String topic = "mm-alert-topic";
        String alertMessage = String.format("{\"userId\":%d,\"alertType\":\"%s\",\"message\":\"%s\",\"timestamp\":%d}",
                userId, alertType, message, System.currentTimeMillis());

        kafkaTemplate.send(topic, alertMessage);
        log.warn("[MM-Kafka] Send MM alert event, userId={}, alertType={}, message={}",
                userId, alertType, message);
    }
}
