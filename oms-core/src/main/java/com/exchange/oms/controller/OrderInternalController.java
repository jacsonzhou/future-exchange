package com.exchange.oms.controller;

import com.exchange.common.core.enums.OrderType;
import com.exchange.common.core.enums.Side;
import com.exchange.common.proto.event.OrderCommand;
import com.exchange.common.proto.request.CreateOrderRequest;
import com.exchange.common.proto.response.CreateOrderResponse;
import com.exchange.oms.service.OrderService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * OMS 内部接口控制器
 * 
 * 供其他微服务调用，包括：
 * - Liquidation Service (强平服务)
 * - TP-SL Service (止盈止损服务)
 * - ADL Service (自动减仓服务)
 */
@Slf4j
@RestController
@RequestMapping("/internal/order")
public class OrderInternalController {
    
    @Autowired
    private OrderService orderService;
    
    /**
     * 创建强平订单
     * 
     * 特点：
     * 1. 跳过风控检查 (或简化检查)
     * 2. 跳过保证金预扣
     * 3. 标记订单来源为 LIQUIDATION
     * 
     * @param request 强平订单请求
     * @return 订单ID
     */
    @PostMapping("/createLiquidation")
    public Long createLiquidationOrder(@RequestBody LiquidationOrderRequest request) {
        log.info("[OrderInternalController] Creating liquidation order, userId={}, positionId={}, symbol={}",
                request.getUserId(), request.getPositionId(), request.getSymbol());
        
        try {
            // 转换为标准订单请求
            CreateOrderRequest orderRequest = new CreateOrderRequest();
            orderRequest.setUserId(request.getUserId());
            orderRequest.setSymbol(request.getSymbol());
            orderRequest.setSide(Side.valueOf(request.getSide()));
            orderRequest.setOrderType(OrderType.valueOf(request.getOrderType()));
            orderRequest.setQuantity(request.getQuantity());
            orderRequest.setPrice(request.getPrice());
            orderRequest.setReduceOnly(request.getReduceOnly() == null || request.getReduceOnly());
            orderRequest.setOrderSource(
                request.getOrderSource() == null || request.getOrderSource().isBlank()
                    ? "LIQUIDATION"
                    : request.getOrderSource()
            );
            orderRequest.setPositionId(request.getPositionId());
            
            // 调用服务创建订单 (特殊处理)
            Long orderId = orderService.createLiquidationOrder(orderRequest);
            
            log.info("[OrderInternalController] Liquidation order created, orderId={}", orderId);
            return orderId;
            
        } catch (Exception e) {
            log.error("[OrderInternalController] Failed to create liquidation order", e);
            throw new RuntimeException("Failed to create liquidation order: " + e.getMessage());
        }
    }
    
    /**
     * 创建ADL减仓订单
     */
    @PostMapping("/createAdl")
    public Long createAdlOrder(@RequestBody AdlOrderRequest request) {
        log.info("[OrderInternalController] Creating ADL order, userId={}, symbol={}",
                request.getUserId(), request.getSymbol());
        
        CreateOrderRequest orderRequest = new CreateOrderRequest();
        orderRequest.setUserId(request.getUserId());
        orderRequest.setSymbol(request.getSymbol());
        orderRequest.setSide(Side.valueOf(request.getSide()));
        orderRequest.setOrderType(OrderType.MARKET);
        orderRequest.setQuantity(request.getQuantity());
        orderRequest.setReduceOnly(true);
        orderRequest.setOrderSource("ADL");
        
        return orderService.createAdlOrder(orderRequest);
    }
    
    /**
     * 取消订单
     */
    @PostMapping("/cancel")
    public void cancelOrder(@RequestBody Long orderId) {
        log.info("[OrderInternalController] Cancelling order, orderId={}", orderId);
        orderService.cancelOrder(orderId);
    }
    
    // ==================== DTO ====================
    
    @lombok.Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LiquidationOrderRequest {
        private Long userId;
        private String symbol;
        private String side;
        private String orderType;
        private Long quantity;
        private Long price;
        private Boolean reduceOnly;
        private String orderSource;
        private Long positionId;
        private String liquidationId;
    }
    
    @lombok.Data
    public static class AdlOrderRequest {
        private Long userId;
        private String symbol;
        private String side;
        private Long quantity;
        private String adlExecutionId;
    }
}
