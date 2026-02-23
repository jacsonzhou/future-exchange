package com.exchange.risk.controller;

import com.exchange.risk.dto.CheckOrderRiskRequest;
import com.exchange.risk.dto.CheckOrderRiskResponse;
import com.exchange.risk.service.HardRiskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 风控内部控制器
 * 
 * 🔥 核心职责：
 * 接收 OMS 的风控检查请求，包含保证金和余额信息
 */
@Slf4j
@RestController
@RequestMapping("/internal/risk")
public class RiskInternalController {
    
    @Autowired
    private HardRiskService hardRiskService;
    
    /**
     * 风控检查（增强版）
     * 
     * @param request 风控检查请求
     * @param requiredMargin 所需保证金（单位：分）
     * @param totalBalance 用户总余额（单位：分）
     * @return 风控检查结果
     */
    @PostMapping("/check")
    public CheckOrderRiskResponse checkRisk(
            @RequestBody CheckOrderRiskRequest request,
            @RequestParam(value = "requiredMargin", required = false) Long requiredMargin,
            @RequestParam(value = "totalBalance", required = false) Long totalBalance) {
        
        log.info("[RiskInternal] Check risk, orderId={}, userId={}, requiredMargin={}, totalBalance={}",
            request.getOrderId(), request.getUserId(), requiredMargin, totalBalance);
        
        // 传递保证金和余额信息到服务层
        return hardRiskService.checkOrderRisk(request, requiredMargin, totalBalance);
    }
    
    /**
     * 健康检查
     */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}

