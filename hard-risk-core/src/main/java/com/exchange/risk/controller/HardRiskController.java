package com.exchange.risk.controller;

import com.exchange.risk.dto.CheckOrderRiskRequest;
import com.exchange.risk.dto.CheckOrderRiskResponse;
import com.exchange.risk.service.HardRiskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * Hard Risk Gate 对外接口Controller
 * 
 * 提供同步风控检查接口
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/risk")
public class HardRiskController {
    
    @Autowired
    private HardRiskService hardRiskService;
    
    /**
     * 检查订单风险（核心接口）
     * 
     * POST /api/v1/risk/check
     * 
     * Headers:
     * - X-Trace-Id
     * - X-Request-Id
     * - X-User-Id
     */
    @PostMapping("/check")
    public CheckOrderRiskResponse checkOrderRisk(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestHeader(value = "X-User-Id") Long userId,
            @RequestBody CheckOrderRiskRequest request) {
        
        log.info("[HardRisk-API] Check order risk, userId={}, orderId={}, traceId={}",
            userId, request.getOrderId(), traceId);
        
        // 设置header信息
        request.setTraceId(traceId);
        request.setRequestId(requestId);
        request.setUserId(userId);
        
        return hardRiskService.checkOrderRisk(request);
    }
}






