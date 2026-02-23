package com.exchange.liquidation.client;

import com.exchange.liquidation.dto.CreateOrderRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * OMS服务客户端
 * 
 * 调用订单管理服务创建强平订单
 */
@FeignClient(name = "oms-core", path = "/internal/order")
public interface OmsClient {
    
    /**
     * 创建强平订单
     * 
     * @param request 订单请求
     * @return 订单ID
     */
    @PostMapping("/createLiquidation")
    Long createOrder(@RequestBody CreateOrderRequest request);
    
    /**
     * 取消订单
     */
    @PostMapping("/cancel")
    void cancelOrder(@RequestBody Long orderId);
}
