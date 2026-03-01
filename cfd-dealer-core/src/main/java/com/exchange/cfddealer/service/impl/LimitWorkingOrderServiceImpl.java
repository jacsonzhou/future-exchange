package com.exchange.cfddealer.service.impl;

import com.exchange.cfddealer.config.CfdDealerProperties;
import com.exchange.cfddealer.dto.CfdOrderCommand;
import com.exchange.cfddealer.dto.MarketExecutionResult;
import com.exchange.cfddealer.entity.CfdWorkingOrder;
import com.exchange.cfddealer.mapper.CfdWorkingOrderMapper;
import com.exchange.cfddealer.producer.OrderStateProducer;
import com.exchange.cfddealer.producer.TradeEventProducer;
import com.exchange.cfddealer.service.LimitWorkingOrderService;
import com.exchange.cfddealer.service.MarketExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class LimitWorkingOrderServiceImpl implements LimitWorkingOrderService {

    private static final String STATUS_WORKING = "WORKING";
    private static final String STATUS_FILLED = "FILLED";
    private static final String STATUS_CANCELED = "CANCELED";

    private final CfdWorkingOrderMapper workingOrderMapper;
    private final MarketExecutionService marketExecutionService;
    private final TradeEventProducer tradeEventProducer;
    private final OrderStateProducer orderStateProducer;
    private final CfdDealerProperties properties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleLimitSubmit(CfdOrderCommand command) {
        MarketExecutionResult immediateFill = marketExecutionService.executeLimit(command);
        if (immediateFill != null) {
            tradeEventProducer.publishTrade(command, immediateFill);
            orderStateProducer.publishFilled(immediateFill);
            log.info("[CFD-DEALER] limit immediate filled, orderId={}, symbol={}, price={}, qty={}",
                immediateFill.getOrderId(),
                immediateFill.getSymbol(),
                immediateFill.getVwapPrice(),
                immediateFill.getFilledQuantity());
            return;
        }

        CfdWorkingOrder record = toWorkingOrder(command);
        try {
            workingOrderMapper.insert(record);
            log.info("[CFD-DEALER] limit queued as WORKING, orderId={}, symbol={}, side={}, price={}, qty={}",
                record.getOrderId(),
                record.getSymbol(),
                record.getSide(),
                record.getLimitPrice(),
                record.getQuantity());
        } catch (DuplicateKeyException duplicateKeyException) {
            CfdWorkingOrder existing = workingOrderMapper.selectById(record.getOrderId());
            if (existing != null) {
                log.info("[CFD-DEALER] limit submit duplicated, keep existing status, orderId={}, status={}",
                    existing.getOrderId(), existing.getStatus());
                return;
            }
            throw duplicateKeyException;
        }
    }

    @Override
    public void handleCancel(CfdOrderCommand command) {
        if (command.getOrderId() == null || command.getSymbol() == null || command.getUserId() == null) {
            return;
        }

        long now = System.currentTimeMillis();
        int updated = workingOrderMapper.updateStatusToFinal(
            command.getOrderId(),
            STATUS_WORKING,
            STATUS_CANCELED,
            "CFD_CANCEL",
            command.getEventTime() != null ? command.getEventTime() : now,
            now
        );

        if (updated <= 0) {
            return;
        }

        orderStateProducer.publishCanceled(command.getOrderId(), command.getUserId(), normalizeSymbol(command.getSymbol()), "USER_CANCEL");
        log.info("[CFD-DEALER] working order canceled, orderId={}, symbol={}", command.getOrderId(), command.getSymbol());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int triggerWorkingOrders(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        List<CfdWorkingOrder> workingOrders = workingOrderMapper.selectWorkingBySymbol(normalizedSymbol, properties.getTriggerBatchSize());
        if (workingOrders == null || workingOrders.isEmpty()) {
            return 0;
        }
        return triggerCandidates(normalizedSymbol, workingOrders, "REFERENCE_TRIGGER", null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int triggerWorkingOrdersByQuote(String symbol,
                                           BigDecimal bestBid,
                                           BigDecimal bestAsk,
                                           Long referenceEventTime,
                                           String triggerSource,
                                           Long referenceOffset) {
        if (bestBid == null || bestAsk == null
            || bestBid.compareTo(BigDecimal.ZERO) <= 0
            || bestAsk.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }

        String normalizedSymbol = normalizeSymbol(symbol);
        List<CfdWorkingOrder> candidates = workingOrderMapper.selectTriggerCandidates(
            normalizedSymbol,
            bestBid,
            bestAsk,
            properties.getTriggerBatchSize()
        );
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        String source = triggerSource == null || triggerSource.isBlank()
            ? "REFERENCE_EVENT"
            : triggerSource;
        return triggerCandidates(normalizedSymbol, candidates, source, referenceEventTime, referenceOffset);
    }

    private int triggerCandidates(String symbol,
                                  List<CfdWorkingOrder> workingOrders,
                                  String triggerSource,
                                  Long referenceEventTime,
                                  Long referenceOffset) {
        int triggered = 0;
        for (CfdWorkingOrder workingOrder : workingOrders) {
            CfdOrderCommand command = toLimitCommand(workingOrder, triggerSource, referenceOffset, referenceEventTime);
            MarketExecutionResult result = marketExecutionService.executeLimit(command);
            if (result == null) {
                continue;
            }

            long now = System.currentTimeMillis();
            int updated = workingOrderMapper.updateStatusToFinal(
                workingOrder.getOrderId(),
                STATUS_WORKING,
                STATUS_FILLED,
                triggerSource,
                result.getReferenceEventTime() != null ? result.getReferenceEventTime() : now,
                now
            );
            if (updated <= 0) {
                continue;
            }

            tradeEventProducer.publishTrade(command, result);
            orderStateProducer.publishFilled(result);
            triggered++;

            log.info("[CFD-DEALER] working order triggered FILLED, orderId={}, symbol={}, triggerSource={}, vwap={}, qty={}, refTopic={}, refOffset={}",
                workingOrder.getOrderId(),
                symbol,
                triggerSource,
                result.getVwapPrice(),
                result.getFilledQuantity(),
                result.getReferenceTopic(),
                result.getReferenceOffset());
        }
        return triggered;
    }

    private CfdWorkingOrder toWorkingOrder(CfdOrderCommand command) {
        long now = System.currentTimeMillis();
        CfdWorkingOrder record = new CfdWorkingOrder();
        record.setOrderId(command.getOrderId());
        record.setUserId(command.getUserId());
        record.setSymbol(normalizeSymbol(command.getSymbol()));
        record.setSide("SELL".equalsIgnoreCase(command.getSide()) ? 1 : 0);
        record.setLimitPrice(parsePositive(command.getPrice()));
        record.setQuantity(parsePositive(command.getQuantity()));
        record.setRemainingQuantity(parsePositive(command.getQuantity()));
        record.setStatus(STATUS_WORKING);
        record.setTriggerSource("CFD_ORDER_SUBMIT");
        record.setTriggerEventTime(command.getEventTime() != null ? command.getEventTime() : now);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setVersion(0);
        return record;
    }

    private CfdOrderCommand toLimitCommand(CfdWorkingOrder workingOrder) {
        return toLimitCommand(workingOrder, null, null, null);
    }

    private CfdOrderCommand toLimitCommand(CfdWorkingOrder workingOrder,
                                           String referenceTopic,
                                           Long referenceOffset,
                                           Long referenceEventTime) {
        CfdOrderCommand command = new CfdOrderCommand();
        command.setEventType("CFD_ORDER_SUBMIT");
        command.setOrderId(workingOrder.getOrderId());
        command.setUserId(workingOrder.getUserId());
        command.setSymbol(workingOrder.getSymbol());
        command.setSide(workingOrder.getSide() != null && workingOrder.getSide() == 1 ? "SELL" : "BUY");
        command.setOrderType("LIMIT");
        command.setPrice(workingOrder.getLimitPrice() == null ? null : workingOrder.getLimitPrice().toPlainString());
        command.setQuantity(workingOrder.getRemainingQuantity() == null ? null : workingOrder.getRemainingQuantity().toPlainString());
        command.setExecutionMode("CFD_DEALER");
        command.setLiquiditySource(properties.getLiquiditySource());
        command.setLeverage(10);
        command.setReferenceTopic(referenceTopic);
        command.setReferenceOffset(referenceOffset);
        command.setReferenceEventTime(referenceEventTime);
        command.setEventTime(System.currentTimeMillis());
        return command;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "BTCUSDT";
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal parsePositive(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("limit working order field is blank");
        }
        BigDecimal parsed = new BigDecimal(value.trim());
        if (parsed.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("limit working order field <= 0");
        }
        return parsed;
    }
}
