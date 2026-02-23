package com.exchange.gateway.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 账户余额查询控制器
 *
 * 直接代理到 snapshot-account-core
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/account")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AccountController {
    
    @Autowired
    private RestTemplate restTemplate;
    
    /**
     * 查询账户余额
     * 
     * GET /api/v1/account/balance/{userId}
     */
    @GetMapping("/balance/{userId}")
    public ResponseEntity<?> getAccountBalance(@PathVariable("userId") Long userId) {
        log.info("[AccountController] Query balance for userId: {}", userId);
        
        try {
            // 直接调用 snapshot-account-core
            String url = "http://localhost:8085/internal/snapshot/account/" + userId;
            Map<String, Object> snapshot = restTemplate.getForObject(url, Map.class);
            
            if (snapshot == null || snapshot.isEmpty()) {
                // 返回空余额
                Map<String, Object> emptyBalance = new HashMap<>();
                emptyBalance.put("userId", userId);
                emptyBalance.put("currency", "USDT");
                emptyBalance.put("available", 0);
                emptyBalance.put("frozen", 0);
                emptyBalance.put("positionMargin", 0);
                emptyBalance.put("unrealizedPnl", 0);
                emptyBalance.put("realizedPnl", 0);
                emptyBalance.put("equity", 0);
                emptyBalance.put("marginRatio", 0);
                return ResponseEntity.ok(emptyBalance);
            }
            
            return ResponseEntity.ok(snapshot);
            
        } catch (Exception e) {
            log.error("[AccountController] Failed to query balance for userId: {}", userId, e);
            Map<String, Object> error = new HashMap<>();
            error.put("code", 500);
            error.put("message", "Failed to query balance: " + e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
}
