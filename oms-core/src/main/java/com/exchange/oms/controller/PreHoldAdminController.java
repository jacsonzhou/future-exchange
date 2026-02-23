package com.exchange.oms.controller;

import com.exchange.common.core.Money;
import com.exchange.oms.service.MarginPreHoldService;
import com.exchange.oms.service.PreHoldRecoveryService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 预扣管理接口（内部使用）
 * 
 * 🔥 核心职责：
 * 1. 查询用户预扣状态
 * 2. 手动触发预扣恢复
 * 3. 清理异常预扣
 * 
 * ⚠️ 注意：本接口仅供内部管理使用，需要权限控制
 */
@Slf4j
@RestController
@RequestMapping("/internal/prehold")
public class PreHoldAdminController {
    
    @Autowired
    private MarginPreHoldService marginPreHoldService;
    
    @Autowired
    private PreHoldRecoveryService preHoldRecoveryService;
    
    /**
     * 查询用户预扣状态
     * 
     * @param userId 用户ID
     * @return 预扣状态
     */
    @GetMapping("/status/{userId}")
    public PreHoldStatusResponse getPreHoldStatus(@PathVariable Long userId) {
        log.info("[PreHoldAdmin] Query pre-hold status, userId={}", userId);
        
        long totalPreHold = marginPreHoldService.getTotalPreHold(userId);
        List<Long> orderIds = marginPreHoldService.getPreHoldOrderIds(userId);
        
        PreHoldStatusResponse response = new PreHoldStatusResponse();
        response.setUserId(userId);
        response.setTotalPreHold(totalPreHold);
        response.setTotalPreHoldFormatted(Money.format(totalPreHold));
        response.setOrderCount(orderIds.size());
        response.setOrderIds(orderIds);
        
        return response;
    }
    
    /**
     * 查询订单预扣金额
     * 
     * @param userId 用户ID
     * @param orderId 订单ID
     * @return 预扣金额
     */
    @GetMapping("/order")
    public OrderPreHoldResponse getOrderPreHold(
            @RequestParam("userId") Long userId,
            @RequestParam("orderId") Long orderId) {
        log.info("[PreHoldAdmin] Query order pre-hold, userId={}, orderId={}", userId, orderId);
        
        long amount = marginPreHoldService.getOrderPreHold(userId, orderId);
        
        OrderPreHoldResponse response = new OrderPreHoldResponse();
        response.setUserId(userId);
        response.setOrderId(orderId);
        response.setAmount(amount);
        response.setAmountFormatted(Money.format(amount));
        response.setExists(amount > 0);
        
        return response;
    }
    
    /**
     * 手动释放订单预扣
     * 
     * @param userId 用户ID
     * @param orderId 订单ID
     * @return 释放结果
     */
    @PostMapping("/release")
    public ReleaseResponse releasePreHold(
            @RequestParam("userId") Long userId,
            @RequestParam("orderId") Long orderId) {
        log.warn("[PreHoldAdmin] Manual release pre-hold, userId={}, orderId={}", userId, orderId);
        
        long released = marginPreHoldService.releasePreHold(userId, orderId);
        
        ReleaseResponse response = new ReleaseResponse();
        response.setUserId(userId);
        response.setOrderId(orderId);
        response.setReleasedAmount(released);
        response.setReleasedAmountFormatted(Money.format(released));
        response.setSuccess(released > 0);
        
        return response;
    }
    
    /**
     * 清理用户所有预扣（⚠️ 危险操作）
     * 
     * @param userId 用户ID
     * @return 操作结果
     */
    @PostMapping("/clear/{userId}")
    public ClearResponse clearAllPreHold(@PathVariable Long userId) {
        log.warn("[PreHoldAdmin] ⚠️ Clear all pre-hold, userId={}", userId);
        
        long beforeTotal = marginPreHoldService.getTotalPreHold(userId);
        marginPreHoldService.clearAllPreHold(userId);
        long afterTotal = marginPreHoldService.getTotalPreHold(userId);
        
        ClearResponse response = new ClearResponse();
        response.setUserId(userId);
        response.setClearedAmount(beforeTotal);
        response.setClearedAmountFormatted(Money.format(beforeTotal));
        response.setSuccess(afterTotal == 0);
        
        return response;
    }
    
    /**
     * 手动触发预扣恢复
     * 
     * @return 恢复结果
     */
    @PostMapping("/recover")
    public RecoverResponse triggerRecovery() {
        log.info("[PreHoldAdmin] Trigger manual recovery");
        
        PreHoldRecoveryService.RecoveryResult result = preHoldRecoveryService.triggerRecovery();
        
        RecoverResponse response = new RecoverResponse();
        response.setTotalOrders(result.getTotalOrders());
        response.setRecoveredCount(result.getRecoveredCount());
        response.setSuccess(true);
        
        return response;
    }
    
    // ==================== DTO ====================
    
    @Data
    public static class PreHoldStatusResponse {
        private Long userId;
        private Long totalPreHold;
        private String totalPreHoldFormatted;
        private Integer orderCount;
        private List<Long> orderIds;
    }
    
    @Data
    public static class OrderPreHoldResponse {
        private Long userId;
        private Long orderId;
        private Long amount;
        private String amountFormatted;
        private Boolean exists;
    }
    
    @Data
    public static class ReleaseResponse {
        private Long userId;
        private Long orderId;
        private Long releasedAmount;
        private String releasedAmountFormatted;
        private Boolean success;
    }
    
    @Data
    public static class ClearResponse {
        private Long userId;
        private Long clearedAmount;
        private String clearedAmountFormatted;
        private Boolean success;
    }
    
    @Data
    public static class RecoverResponse {
        private Integer totalOrders;
        private Integer recoveredCount;
        private Boolean success;
    }
}
