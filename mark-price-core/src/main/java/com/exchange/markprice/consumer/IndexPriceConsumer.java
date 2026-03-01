package com.exchange.markprice.consumer;

import com.exchange.markprice.service.MarkPriceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 指数价格更新消费者
 */
@Slf4j
@Component
public class IndexPriceConsumer {

    @Autowired
    private MarkPriceService markPriceService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${mark-price.kafka.index-dlq-topic:index-price-update-dlq}")
    private String indexPriceDlqTopic;

    /**
     * 消费指数价格更新事件
     */
    @KafkaListener(topics = "${mark-price.kafka.index-topic:index-price-update}", 
                   groupId = "${spring.application.name}-index-consumer")
    public void onIndexPriceUpdate(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode data = root.has("data") ? root.get("data") : root;

            String symbol = data.has("symbol") ? data.get("symbol").asText() : null;
            if (symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("Missing symbol in index price event");
            }

            if (!data.has("price") || data.get("price").isNull()) {
                throw new IllegalArgumentException("Missing price in index price event");
            }
            long price = data.get("price").asLong();
            if (price <= 0) {
                throw new IllegalArgumentException("Invalid price in index price event: " + price);
            }

            String indexPriceId = data.has("indexPriceId") && !data.get("indexPriceId").isNull()
                    ? data.get("indexPriceId").asText()
                    : symbol + "-" + offset;
            Long sourceEventTime = data.has("sourceEventTime") && !data.get("sourceEventTime").isNull()
                    ? data.get("sourceEventTime").asLong()
                    : (data.has("timestamp") ? data.get("timestamp").asLong(System.currentTimeMillis()) : System.currentTimeMillis());
            String sourceTopic = data.has("sourceTopic") && !data.get("sourceTopic").isNull()
                    ? data.get("sourceTopic").asText()
                    : topic;
            Long sourceOffset = data.has("sourceOffset") && !data.get("sourceOffset").isNull()
                    ? data.get("sourceOffset").asLong()
                    : offset;
            
            log.debug("Received index price update: symbol={}, price={}, indexPriceId={}", symbol, price, indexPriceId);
            
            // 仅基于 index 事件驱动 mark 计算
            markPriceService.onIndexPriceUpdate(symbol, price, indexPriceId, sourceEventTime, sourceTopic, sourceOffset);
        } catch (Exception e) {
            log.error("Failed to process index price update, topic={}, partition={}, offset={}",
                topic, partition, offset, e);
            publishDlq(message, topic, partition, offset, e);
        }
    }

    private void publishDlq(String message, String topic, int partition, long offset, Exception e) {
        try {
            Map<String, Object> dlq = new HashMap<>();
            dlq.put("eventType", "INDEX_PRICE_CONSUME_FAILED");
            dlq.put("eventTime", System.currentTimeMillis());

            Map<String, Object> data = new HashMap<>();
            data.put("sourceTopic", topic);
            data.put("partition", partition);
            data.put("offset", offset);
            data.put("rawMessage", message);
            data.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            dlq.put("data", data);

            kafkaTemplate.send(indexPriceDlqTopic, topic + ":" + partition, objectMapper.writeValueAsString(dlq));
        } catch (Exception ex) {
            log.error("Failed to publish index price DLQ, topic={}, partition={}, offset={}",
                topic, partition, offset, ex);
        }
    }
}
