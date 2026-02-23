package com.exchange.oms.controller;

import com.exchange.oms.dto.OrderStateEventDTO;
import com.exchange.oms.entity.OmsOrder;
import com.exchange.oms.mapper.OmsOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * OMS内部接口Controller（接收Match Engine回调）
 * 
 * 供撮合引擎、清算等内部服务调用
 */
@Slf4j
@RestController
@RequestMapping("/internal/oms")
public class OmsInternalController {
    
    @Autowired
    private OmsOrderMapper orderMapper;
    
    /**
     * 接收订单状态更新（来自Match Engine）⭐
     * 
     * POST /internal/oms/order-state
     */
    @PostMapping("/order-state")
    public Map<String, Object> receiveOrderState(@RequestBody OrderStateEventDTO event) {
        log.info("[OMS-Internal] Receive order state from MatchEngine, orderId={}, status={}, filledDelta={}",
            event.getOrderId(), event.getStatus(), event.getFilledQuantityDelta());
        
        try {
            // 更新订单状态
            OmsOrder order = orderMapper.selectById(event.getOrderId());
            if (order == null) {
                log.warn("[OMS-Internal] Order not found, orderId={}", event.getOrderId());
                return buildResult(false, "Order not found");
            }
            
            // 更新已成交数量
            if (event.getFilledQuantityDelta() != null && 
                event.getFilledQuantityDelta().compareTo(java.math.BigDecimal.ZERO) > 0) {
                order.setFilledQuantity(
                    order.getFilledQuantity().add(event.getFilledQuantityDelta())
                );
            }
            
            // 更新状态
            Integer newStatus = mapStatus(event.getStatus());
            if (newStatus != null) {
                order.setStatus(newStatus);
            }
            
            order.setUpdatedAt(System.currentTimeMillis());
            
            // 使用updateById（会自动处理version）
            int updated = orderMapper.updateById(order);
            
            if (updated > 0) {
                log.info("[OMS-Internal] Order state updated successfully, orderId={}, newStatus={}",
                    event.getOrderId(), order.getStatus());
                return buildResult(true, "Order state updated");
            } else {
                log.warn("[OMS-Internal] Order state update failed (version conflict?), orderId={}",
                    event.getOrderId());
                return buildResult(false, "Update failed, version conflict");
            }
            
        } catch (Exception e) {
            log.error("[OMS-Internal] Failed to update order state, orderId={}",
                event.getOrderId(), e);
            return buildResult(false, "Error: " + e.getMessage());
        }
    }
    
    /**
     * 处理成交回报（撮合引擎回调）
     * 
     * POST /internal/oms/trade-report
     */
    @PostMapping("/trade-report")
    public Map<String, Object> handleTradeReport(
            @RequestParam("orderId") Long orderId,
            @RequestParam("filledQuantity") String filledQuantity) {
        
        log.info("[OMS-Internal] Handle trade report, orderId={}, filled={}", 
            orderId, filledQuantity);
        
        // 这个方法可能被TradeEvent触发，也可能被OrderStateEvent触发
        // 通常OrderStateEvent已经包含了成交信息，所以这个接口可能是冗余的
        
        return buildResult(true, "Trade report received");
    }
    
    /**
     * 健康检查
     * 
     * GET /internal/oms/health
     */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }
    
    /**
     * 映射订单状态
     */
    private Integer mapStatus(String status) {
        switch (status) {
            case "PARTIALLY_FILLED":
                return 3;
            case "FILLED":
                return 4;
            case "CANCELED":
                return 5;
            default:
                return null;
        }
    }
    
    /**
     * 构建响应
     */
    private Map<String, Object> buildResult(boolean success, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", message);
        return result;
    }
}
