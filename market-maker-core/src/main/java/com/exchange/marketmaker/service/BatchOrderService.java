package com.exchange.marketmaker.service;

import com.exchange.marketmaker.dto.request.BatchCancelRequest;
import com.exchange.marketmaker.dto.request.BatchOrderRequest;
import com.exchange.marketmaker.dto.request.CancelAllRequest;
import com.exchange.marketmaker.dto.request.ModifyOrderRequest;
import com.exchange.marketmaker.dto.response.BatchOrderResponse;

/**
 * 批量订单服务接口
 */
public interface BatchOrderService {

    /**
     * 批量下单
     */
    BatchOrderResponse batchCreateOrder(Long userId, BatchOrderRequest request);

    /**
     * 批量撤单
     */
    BatchOrderResponse batchCancelOrder(Long userId, BatchCancelRequest request);

    /**
     * 一键撤单
     */
    Integer cancelAllOrders(Long userId, CancelAllRequest request);

    /**
     * 修改订单
     */
    Boolean modifyOrder(Long userId, ModifyOrderRequest request);
}
