package com.exchange.cfddealer.service;

import com.exchange.cfddealer.dto.CfdOrderCommand;

public interface LimitWorkingOrderService {

    void handleLimitSubmit(CfdOrderCommand command);

    void handleCancel(CfdOrderCommand command);

    int triggerWorkingOrders(String symbol);
}
