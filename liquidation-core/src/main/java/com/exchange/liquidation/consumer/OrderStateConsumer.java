package com.exchange.liquidation.consumer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.exchange.liquidation.service.OrderMonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 订单状态消费者
 * 
 * 消费OMS发布的订单状态变更事件，更新强平订单状态
 * 
 * Topic: order-state-{symbol}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStateConsumer {

    private static final BigDecimal SCALE = BigDecimal.valueOf(100_000_000L);
    private final OrderMonitorService orderMonitorService;
    
    /**
     * 消费订单状态变更事件
     * 
     * 监听所有symbol的订单状态事件
     */
    @KafkaListener(
        topicPattern = "${kafka.topic.order-status-pattern:order-state-.*}",
        groupId = "${spring.kafka.consumer.group-id:liquidation-order-state-group}",
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
            log.debug("[OrderStateConsumer] Received order state event, partition={}, offset={}, key={}",
                    partition, offset, key);

            // 解析事件
            JSONObject event = JSON.parseObject(message);

            Long orderId = event.getLong("orderId");
            String status = event.getString("status");
            Long filledQty = parseScaledLong(event.get("filledQuantityDelta"));
            Long avgPrice = parseScaledLong(event.get("avgPrice"));

            if (orderId == null || status == null || status.isBlank()) {
                log.warn("[OrderStateConsumer] Skip invalid event, partition={}, offset={}, payload={}",
                        partition, offset, message);
                if (ack != null) {
                    ack.acknowledge();
                }
                return;
            }

            log.info("[OrderStateConsumer] Processing liquidation order state, orderId={}, status={}, filledQty={}, avgPrice={}",
                    orderId, status, filledQty, avgPrice);

            // 处理订单状态变更
            orderMonitorService.handleOrderStatusChange(orderId, status, filledQty, avgPrice);
            if (ack != null) {
                ack.acknowledge();
            }
            long duration = System.currentTimeMillis() - startTime;
            log.info("[OrderStateConsumer] Processed successfully, partition={}, offset={}, duration={}ms",
                    partition, offset, duration);

        } catch (Exception e) {
            // 订单状态通知是旁路消息，处理失败不阻塞消费，避免重复 poison-message
            log.error("[OrderStateConsumer] Failed to process order state event, partition={}, offset={}, error={}",
                    partition, offset, e.getMessage(), e);
            if (ack != null) {
                ack.acknowledge();
            }
        }
    }

    private Long parseScaledLong(Object value) {
        if (value == null) {
            return null;
        }
        BigDecimal raw = new BigDecimal(String.valueOf(value).trim());
        if (raw.compareTo(BigDecimal.ZERO) == 0) {
            return 0L;
        }

        // 兼容两种协议：
        // 1) 事件给人类单位（例如 4.04 BTC / 50000 USDT）
        // 2) 事件已给 1e8 放大整数
        if (raw.scale() <= 0 && raw.abs().compareTo(BigDecimal.valueOf(1_000_000L)) > 0) {
            return raw.longValue();
        }

        BigDecimal scaled = raw.multiply(SCALE);
        return scaled.setScale(0, RoundingMode.HALF_UP).longValue();
    }
}
