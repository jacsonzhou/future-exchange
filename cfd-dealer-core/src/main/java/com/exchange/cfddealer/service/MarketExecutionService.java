package com.exchange.cfddealer.service;

import com.exchange.cfddealer.dto.CfdOrderCommand;
import com.exchange.cfddealer.dto.MarketExecutionResult;

public interface MarketExecutionService {

    MarketExecutionResult executeMarket(CfdOrderCommand command);

    /**
     * LIMIT 单若满足立即成交条件返回成交结果，否则返回 null。
     */
    MarketExecutionResult executeLimit(CfdOrderCommand command);
}
