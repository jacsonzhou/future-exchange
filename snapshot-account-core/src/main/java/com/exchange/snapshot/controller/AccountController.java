package com.exchange.snapshot.controller;

import com.exchange.snapshot.entity.AccountSnapshot;
import com.exchange.snapshot.service.AccountSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 账户余额查询控制器
 * 
 * 🔒 安全设计原则：
 * 1. userId 必须从 Header X-User-Id 获取（由 API Gateway 从 JWT Token 解析并透传）
 * 2. 禁止用户通过 URL 参数指定 userId，防止越权查询他人账户
 * 3. 用户只能查询自己的账户余额
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/account")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AccountController {
    
    @Autowired
    private AccountSnapshotService accountSnapshotService;
    
    /**
     * 查询当前登录用户的账户余额
     * 
     * GET /api/v1/account/balance
     * Header: X-User-Id=xxx (由 API Gateway 从 JWT 透传，禁止客户端伪造)
     * 
     * 🔒 安全：用户只能查询自己的余额，userId 从认证信息中获取
     */
    @GetMapping("/balance")
    public ResponseEntity<?> getAccountBalance(@RequestHeader("X-User-Id") Long userId) {
        log.info("[AccountController] Query balance for userId: {}", userId);
        
        try {
            AccountSnapshot snapshot = accountSnapshotService.queryAccount(userId);
            
            if (snapshot == null) {
                // 返回空余额（新用户可能还没有快照记录）
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
                emptyBalance.put("lastBizSeq", 0);
                emptyBalance.put("version", 0);
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
