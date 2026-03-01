package com.exchange.cfddealer.consumer;

import com.exchange.cfddealer.dto.CfdOrderCommand;
import com.exchange.cfddealer.dto.MarketExecutionResult;
import com.exchange.cfddealer.service.LimitWorkingOrderService;
import com.exchange.cfddealer.producer.OrderStateProducer;
import com.exchange.cfddealer.producer.TradeEventProducer;
import com.exchange.cfddealer.service.MarketExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class CfdOrderCommandConsumer {

    private final ObjectMapper objectMapper;
    private final MarketExecutionService marketExecutionService;
    private final LimitWorkingOrderService limitWorkingOrderService;
    private final TradeEventProducer tradeEventProducer;
    private final OrderStateProducer orderStateProducer;

    @KafkaListener(
            topicPattern = "${cfd.dealer.command-topic-pattern:cfd-order-command-.*}",
            groupId = "${cfd.dealer.consumer-group:cfd-dealer-command}"
    )
    public void consume(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        try {
            CfdOrderCommand command = objectMapper.readValue(message, CfdOrderCommand.class);
            String eventType = normalize(command.getEventType());
            String symbol = normalizeSymbol(command.getSymbol());

            if ("CFD_ORDER_SUBMIT".equals(eventType)) {
                handleSubmit(command, topic, offset, symbol, eventType);
                return;
            }

            if ("CFD_CANCEL".equals(eventType)) {
                limitWorkingOrderService.handleCancel(command);
                log.info("[CFD-DEALER] cancel consumed, orderId={}, symbol={}, topic={}, offset={}",
                    command.getOrderId(),
                    symbol,
                    topic,
                    offset);
                return;
            }

            log.warn("[CFD-DEALER] unknown eventType, eventType={}, topic={}, offset={}", eventType, topic, offset);
        } catch (Exception e) {
            log.error("[CFD-DEALER] consume command failed, topic={}, offset={}, payload={}", topic, offset, message, e);
            throw new RuntimeException("consume cfd command failed", e);
        }
    }

    private void handleSubmit(CfdOrderCommand command, String topic, long offset, String symbol, String eventType) {
        String orderType = normalize(command.getOrderType());

        try {
            if ("MARKET".equals(orderType)) {
                MarketExecutionResult result = marketExecutionService.executeMarket(command);
                tradeEventProducer.publishTrade(command, result);
                orderStateProducer.publishFilled(result);
                log.info("[CFD-DEALER] market filled, orderId={}, symbol={}, vwap={}, qty={}, sequence={}, refTopic={}, refOffset={}",
                    result.getOrderId(),
                    result.getSymbol(),
                    result.getVwapPrice(),
                    result.getFilledQuantity(),
                    result.getMatchSequence(),
                    result.getReferenceTopic(),
                    result.getReferenceOffset());
                return;
            }

            if ("LIMIT".equals(orderType)) {
                limitWorkingOrderService.handleLimitSubmit(command);
                return;
            }

            throw new IllegalArgumentException("unsupported orderType: " + orderType + ", eventType=" + eventType);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? "dealer execute failed" : e.getMessage();
            orderStateProducer.publishRejected(
                    command.getOrderId(),
                    command.getUserId(),
                    symbol,
                    reason,
                    "CFD_REJECT_" + System.currentTimeMillis()
            );
            log.error("[CFD-DEALER] market submit rejected, orderId={}, symbol={}, reason={}",
                    command.getOrderId(),
                    symbol,
                    reason,
                    e);
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "BTCUSDT";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
