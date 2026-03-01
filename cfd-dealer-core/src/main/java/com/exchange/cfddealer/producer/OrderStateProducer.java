package com.exchange.cfddealer.producer;

import com.exchange.cfddealer.config.CfdDealerProperties;
import com.exchange.cfddealer.dto.MarketExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStateProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CfdDealerProperties properties;

    public void publishFilled(MarketExecutionResult result) {
        try {
            String topic = properties.getOrderStateTopicPrefix() + result.getSymbol();
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", result.getTradeId());
            event.put("symbol", result.getSymbol());
            event.put("orderId", result.getOrderId());
            event.put("userId", result.getUserId());
            event.put("status", "FILLED");
            event.put("filledQuantityDelta", result.getFilledQuantity().toPlainString());
            event.put("lastFilledPrice", result.getVwapPrice().toPlainString());
            event.put("tradeId", result.getMatchSequence());
            event.put("fee", BigDecimal.ZERO.toPlainString());
            event.put("feeAsset", "USDT");
            event.put("tradeTime", result.getTradeTime());
            event.put("matchSequence", result.getMatchSequence());
            event.put("eventTime", System.currentTimeMillis());
            event.put("executionMode", result.getExecutionMode());
            event.put("liquiditySource", result.getLiquiditySource());
            event.put("referenceTopic", result.getReferenceTopic());
            event.put("referenceOffset", result.getReferenceOffset());
            event.put("referenceEventTime", result.getReferenceEventTime());
            event.put("referenceBestBid", result.getBestBid().toPlainString());
            event.put("referenceBestAsk", result.getBestAsk().toPlainString());
            event.put("referenceVwapPrice", result.getVwapPrice().toPlainString());
            event.put("slippageBps", result.getSlippageBps());

            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, String.valueOf(result.getOrderId()), payload)
                    .whenComplete((sendResult, throwable) -> {
                        if (throwable == null) {
                            log.info("[CFD-DEALER] order-state sent, topic={}, orderId={}, offset={}",
                                    topic,
                                    result.getOrderId(),
                                    sendResult.getRecordMetadata().offset());
                        } else {
                            log.error("[CFD-DEALER] order-state send failed, topic={}, orderId={}",
                                    topic,
                                    result.getOrderId(),
                                    throwable);
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("publish order-state failed", e);
        }
    }

    public void publishRejected(Long orderId, Long userId, String symbol, String reasonMsg, String traceId) {
        try {
            String topic = properties.getOrderStateTopicPrefix() + symbol;
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", traceId);
            event.put("symbol", symbol);
            event.put("orderId", orderId);
            event.put("userId", userId);
            event.put("status", "REJECTED");
            event.put("filledQuantityDelta", "0");
            event.put("lastFilledPrice", "0");
            event.put("tradeId", 0L);
            event.put("fee", BigDecimal.ZERO.toPlainString());
            event.put("feeAsset", "USDT");
            event.put("tradeTime", System.currentTimeMillis());
            event.put("matchSequence", 0L);
            event.put("eventTime", System.currentTimeMillis());
            event.put("rejectReason", reasonMsg);
            event.put("executionMode", "CFD_DEALER");
            event.put("liquiditySource", properties.getLiquiditySource());

            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, String.valueOf(orderId), payload);
        } catch (Exception e) {
            throw new RuntimeException("publish rejected order-state failed", e);
        }
    }

    public void publishCanceled(Long orderId, Long userId, String symbol, String reasonMsg) {
        try {
            String topic = properties.getOrderStateTopicPrefix() + symbol;
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", "CFD_CANCEL_" + System.currentTimeMillis());
            event.put("symbol", symbol);
            event.put("orderId", orderId);
            event.put("userId", userId);
            event.put("status", "CANCELED");
            event.put("filledQuantityDelta", "0");
            event.put("lastFilledPrice", "0");
            event.put("tradeId", 0L);
            event.put("fee", BigDecimal.ZERO.toPlainString());
            event.put("feeAsset", "USDT");
            event.put("tradeTime", System.currentTimeMillis());
            event.put("matchSequence", 0L);
            event.put("eventTime", System.currentTimeMillis());
            event.put("cancelReason", reasonMsg);
            event.put("executionMode", "CFD_DEALER");
            event.put("liquiditySource", properties.getLiquiditySource());

            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, String.valueOf(orderId), payload);
        } catch (Exception e) {
            throw new RuntimeException("publish canceled order-state failed", e);
        }
    }
}
