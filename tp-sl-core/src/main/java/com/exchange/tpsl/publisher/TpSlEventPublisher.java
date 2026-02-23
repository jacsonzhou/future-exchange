package com.exchange.tpsl.publisher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * TP/SL事件发布器
 */
@Slf4j
@Component
public class TpSlEventPublisher {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    private static final String TP_SL_TRIGGERED_TOPIC = "tp-sl-triggered";
    private static final String TP_SL_CANCELLED_TOPIC = "tp-sl-cancelled";
    private static final String TP_SL_EXECUTED_TOPIC = "tp-sl-executed";

    /**
     * 发布TP/SL触发事件
     */
    public void publishTriggered(Long orderId, Long userId, String symbol,
                                  String type, Long triggerPrice, Long quantity,
                                  String execType, Long execPrice, String triggerSide) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "TP_SL_TRIGGERED");
        event.put("orderId", orderId);
        event.put("userId", userId);
        event.put("symbol", symbol);
        event.put("type", type);
        event.put("triggerPrice", triggerPrice);
        event.put("quantity", quantity);
        event.put("execType", execType);
        event.put("execPrice", execPrice);
        event.put("triggerSide", triggerSide);
        event.put("timestamp", System.currentTimeMillis());

        kafkaTemplate.send(TP_SL_TRIGGERED_TOPIC, symbol, event);
        log.info("Published TP/SL triggered event: orderId={}, symbol={}", orderId, symbol);
    }

    /**
     * 发布TP/SL撤销事件
     */
    public void publishCancelled(Long orderId, Long userId, String symbol, String reason) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "TP_SL_CANCELLED");
        event.put("orderId", orderId);
        event.put("userId", userId);
        event.put("symbol", symbol);
        event.put("reason", reason);
        event.put("timestamp", System.currentTimeMillis());

        kafkaTemplate.send(TP_SL_CANCELLED_TOPIC, symbol, event);
        log.info("Published TP/SL cancelled event: orderId={}, symbol={}", orderId, symbol);
    }

    /**
     * 发布TP/SL执行完成事件
     */
    public void publishExecuted(Long orderId, Long userId, String symbol,
                                 Long closeOrderId, String execResult) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "TP_SL_EXECUTED");
        event.put("orderId", orderId);
        event.put("userId", userId);
        event.put("symbol", symbol);
        event.put("closeOrderId", closeOrderId);
        event.put("execResult", execResult);
        event.put("timestamp", System.currentTimeMillis());

        kafkaTemplate.send(TP_SL_EXECUTED_TOPIC, symbol, event);
        log.info("Published TP/SL executed event: orderId={}, closeOrderId={}", orderId, closeOrderId);
    }
}
