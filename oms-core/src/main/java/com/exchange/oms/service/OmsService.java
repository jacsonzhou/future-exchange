package com.exchange.oms.service;

import com.exchange.oms.dto.*;

/**
 * OMS核心服务接口
 */
public interface OmsService {
    
    /**
     * 提交订单
     * 
     * 核心流程：
     * 1. 参数校验
     * 2. 幂等检查
     * 3. 创建订单（NEW）
     * 4. 调用Hard Risk Gate
     * 5. 调用Account Service冻结
     * 6. 更新状态=FROZEN
     * 7. 投递OrderEvent -> Match Engine
     * 
     * @param request 提交订单请求
     * @return 提交订单响应
     */
    SubmitOrderResponse submitOrder(SubmitOrderRequest request);
    
    /**
     * 撤单
     * 
     * 核心流程：
     * 1. 查询订单
     * 2. 状态校验
     * 3. 更新状态=CANCELED
     * 4. 投递CancelEvent -> Match Engine
     * 5. 解冻资金
     * 
     * @param request 撤单请求
     * @return 撤单响应
     */
    CancelOrderResponse cancelOrder(CancelOrderRequest request);
    
    /**
     * 查询订单
     * 
     * @param request 查询订单请求
     * @return 查询订单响应
     */
    QueryOrderResponse queryOrder(QueryOrderRequest request);
    
    /**
     * 查询订单列表
     * 
     * @param request 订单列表查询请求
     * @return 订单列表查询响应
     */
    OrderListResponse queryOrderList(OrderListRequest request);
    
    /**
     * 处理成交回报（从撮合引擎回调）
     * 
     * @param orderId 订单ID
     * @param filledQuantity 成交数量
     */
    void handleTradeReport(Long orderId, String filledQuantity);
}



