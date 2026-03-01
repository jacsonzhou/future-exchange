package com.exchange.oms.publisher;

import com.exchange.oms.dto.CfdOrderCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * CFD 指令发布器（OMS -> CFD Dealer）
 */
@Slf4j
@Component
public class CfdOrderCommandPublisher {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${oms.kafka.cfd-order-command.topic-prefix:cfd-order-command-}")
    private String topicPrefix;

    public void publish(CfdOrderCommand command) {
        try {
            String topic = topicPrefix + command.getSymbol();
            String key = command.getSymbol() + ":" + command.getOrderId();
            String value = objectMapper.writeValueAsString(command);

            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(topic, key, value).toCompletableFuture();

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("[CfdOrderPublisher] ✅ sent, topic={}, partition={}, offset={}, orderId={}, type={}",
                            topic,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset(),
                            command.getOrderId(),
                            command.getEventType());
                } else {
                    log.error("[CfdOrderPublisher] ❌ send failed, topic={}, orderId={}, type={}",
                            topic, command.getOrderId(), command.getEventType(), ex);
                }
            });
        } catch (Exception e) {
            log.error("[CfdOrderPublisher] ❌ build/send failed, orderId={}, type={}",
                    command.getOrderId(), command.getEventType(), e);
            throw new RuntimeException("Failed to publish CFD command", e);
        }
    }
}
