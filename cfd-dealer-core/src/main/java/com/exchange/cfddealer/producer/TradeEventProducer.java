package com.exchange.cfddealer.producer;

import com.exchange.cfddealer.config.CfdDealerProperties;
import com.exchange.cfddealer.dto.CfdOrderCommand;
import com.exchange.cfddealer.dto.MarketExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TradeEventProducer {

    private static final BigDecimal SCALE = new BigDecimal("100000000");

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final CfdDealerProperties properties;

    public void publishTrade(CfdOrderCommand command, MarketExecutionResult result) {
        try {
            long dealerAccountId = properties.getDealerAccountId();
            boolean userBuy = "BUY".equalsIgnoreCase(result.getSide());
            boolean isBuyerMaker = !userBuy;

            long makerUserId = dealerAccountId;
            long takerUserId = result.getUserId();
            long makerOrderId = result.getOrderId();
            long takerOrderId = result.getOrderId();

            int userLeverage = (command.getLeverage() == null || command.getLeverage() <= 0)
                    ? 10
                    : command.getLeverage();
            int dealerLeverage = 1;

            Map<String, Object> event = new HashMap<>();
            event.put("eventType", "TRADE");
            event.put("symbol", result.getSymbol());
            event.put("sequence", result.getMatchSequence());
            event.put("tradeId", result.getTradeId());
            event.put("price", toScaledLong(result.getVwapPrice()));
            event.put("quantity", toScaledLong(result.getFilledQuantity()));
            event.put("timestamp", result.getTradeTime());
            event.put("isBuyerMaker", isBuyerMaker);

            event.put("makerOrderId", makerOrderId);
            event.put("takerOrderId", takerOrderId);
            event.put("makerUserId", makerUserId);
            event.put("takerUserId", takerUserId);
            event.put("makerLeverage", dealerLeverage);
            event.put("takerLeverage", userLeverage);

            event.put("executionMode", result.getExecutionMode());
            event.put("liquiditySource", result.getLiquiditySource());
            event.put("referenceTopic", result.getReferenceTopic());
            event.put("referenceOffset", result.getReferenceOffset());
            event.put("referenceEventTime", result.getReferenceEventTime());
            event.put("dealerAccountId", dealerAccountId);

            String topic = properties.getTradeTopicPrefix() + result.getSymbol();
            String payload = objectMapper.writeValueAsString(event);

            kafkaTemplate.send(topic, result.getTradeId(), payload)
                    .whenComplete((sendResult, throwable) -> {
                        if (throwable == null) {
                            log.info("[CFD-DEALER] trade-event sent, topic={}, tradeId={}, offset={}",
                                    topic,
                                    result.getTradeId(),
                                    sendResult.getRecordMetadata().offset());
                        } else {
                            log.error("[CFD-DEALER] trade-event send failed, topic={}, tradeId={}",
                                    topic,
                                    result.getTradeId(),
                                    throwable);
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("publish trade-event failed", e);
        }
    }

    private long toScaledLong(BigDecimal value) {
        if (value == null) {
            return 0L;
        }
        return value.multiply(SCALE).setScale(0, RoundingMode.HALF_UP).longValue();
    }
}
