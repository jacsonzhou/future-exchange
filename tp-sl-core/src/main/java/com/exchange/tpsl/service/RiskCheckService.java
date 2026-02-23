package com.exchange.tpsl.service;

import com.exchange.tpsl.dto.CreateTpSlRequest;

/**
 * TP/SL风控检查服务
 */
public interface RiskCheckService {

    /**
     * 创建TP/SL订单前的风控检查
     */
    void checkBeforeCreate(CreateTpSlRequest request);

    /**
     * 触发TP/SL订单前的风控检查
     */
    void checkBeforeTrigger(Long userId, Long positionId, Long quantity);
}
