package com.exchange.oms.service;

import com.exchange.common.proto.request.CancelOrderRequest;
import com.exchange.common.proto.request.CreateOrderRequest;
import com.exchange.common.proto.response.CreateOrderResponse;

/**
 * 订单服务接口
 */
public interface OrderService {
    
    /**
     * 创建普通订单
     */
    CreateOrderResponse createOrder(CreateOrderRequest request);
    
    /**
     * 取消订单
     */
    CreateOrderResponse cancelOrder(CancelOrderRequest request);
    
    /**
     * 创建强平订单
     * 
     * 特点：
     * 1. 跳过风控检查
     * 2. 跳过保证金预扣
     * 3. 标记订单来源
     * 
     * @param request 订单请求
     * @return 订单ID
     */
    Long createLiquidationOrder(CreateOrderRequest request);
    
    /**
     * 创建ADL减仓订单
     * 
     * @param request 订单请求
     * @return 订单ID
     */
    Long createAdlOrder(CreateOrderRequest request);
    
    /**
     * 取消订单 (内部使用)
     */
    void cancelOrder(Long orderId);
}
