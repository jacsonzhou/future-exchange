package com.exchange.oms.controller;

import com.exchange.oms.service.MarginPreHoldService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 保证金预扣查询接口
 * 
 * 供前端查询用户的预扣保证金信息
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/oms")
public class PreHoldController {
    
    @Autowired
    private MarginPreHoldService marginPreHoldService;
    
    /**
     * 获取用户预扣保证金总额
     * 
     * @param userId 用户ID
     * @return 预扣金额（USDT）
     */
    @GetMapping("/prehold/{userId}")
    public Map<String, Object> getUserPreHold(@PathVariable Long userId) {
        log.info("[PreHoldController] Query pre-hold, userId={}", userId);
        
        long preHoldAmount = marginPreHoldService.getTotalPreHold(userId);
        List<Long> orderIds = marginPreHoldService.getPreHoldOrderIds(userId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        // 转换为USDT（8位小数）
        result.put("preHold", new BigDecimal(preHoldAmount).divide(new BigDecimal("100000000"), 8, BigDecimal.ROUND_HALF_UP).doubleValue());
        result.put("preHoldRaw", preHoldAmount);
        result.put("orderCount", orderIds.size());
        result.put("orderIds", orderIds);
        
        return result;
    }
    
    /**
     * 获取指定订单的预扣金额
     * 
     * @param userId 用户ID
     * @param orderId 订单ID
     * @return 预扣金额
     */
    @GetMapping("/prehold/{userId}/{orderId}")
    public Map<String, Object> getOrderPreHold(
            @PathVariable Long userId,
            @PathVariable Long orderId) {
        
        log.info("[PreHoldController] Query order pre-hold, userId={}, orderId={}", userId, orderId);
        
        long preHoldAmount = marginPreHoldService.getOrderPreHold(userId, orderId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("orderId", orderId);
        result.put("preHold", new BigDecimal(preHoldAmount).divide(new BigDecimal("100000000"), 8, BigDecimal.ROUND_HALF_UP).doubleValue());
        result.put("preHoldRaw", preHoldAmount);
        
        return result;
    }
    
    /**
     * 管理员接口：清理用户所有预扣（用于紧急恢复）
     * 
     * @param userId 用户ID
     * @return 操作结果
     */
    @PostMapping("/admin/prehold/clear/{userId}")
    public Map<String, Object> clearUserPreHold(@PathVariable Long userId) {
        log.warn("[PreHoldController] Clear all pre-hold for userId={}", userId);
        
        marginPreHoldService.clearAllPreHold(userId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "All pre-hold cleared for user " + userId);
        return result;
    }
}
