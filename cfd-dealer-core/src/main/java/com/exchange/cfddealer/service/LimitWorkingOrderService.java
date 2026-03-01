package com.exchange.cfddealer.service;

import com.exchange.cfddealer.dto.CfdOrderCommand;

import java.math.BigDecimal;

public interface LimitWorkingOrderService {

    void handleLimitSubmit(CfdOrderCommand command);

    void handleCancel(CfdOrderCommand command);

    int triggerWorkingOrders(String symbol);

    int triggerWorkingOrdersByQuote(String symbol,
                                    BigDecimal bestBid,
                                    BigDecimal bestAsk,
                                    Long referenceEventTime,
                                    String triggerSource,
                                    Long referenceOffset);
}
