package com.exchange.privatepush.controller;

import com.exchange.privatepush.service.SessionManager;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 私有推送 REST API 控制器
 * 
 * 🔥 核心职责：
 * 1. 提供快照恢复接口（断线重连时拉取）
 * 2. 提供订单、账户、持仓快照查询
 * 3. 提供会话统计接口
 * 
 * 用途：
 * - 客户端断线重连后拉取最新状态
 * - 管理后台查询会话统计
 * 
 * @author Exchange Team
 * @version 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/private")
public class PrivatePushController {
    
    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private com.exchange.privatepush.service.MessagePersistenceService messagePersistenceService;

    // TODO: 注入 OMS、Account、Position 服务客户端
    // @Autowired
    // private OmsClient omsClient;
    // @Autowired
    // private AccountClient accountClient;
    // @Autowired
    // private PositionClient positionClient;
    
    /**
     * 获取当前订单列表（快照）
     * 
     * GET /api/v1/private/orders/open
     * 
     * 用途：客户端断线重连后拉取当前委托订单
     */
    @GetMapping("/orders/open")
    public ApiResponse<List<OrderSnapshot>> getOpenOrders(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) String symbol
    ) {
        log.info("[PrivatePushController] Get open orders, userId={}, symbol={}", userId, symbol);
        
        try {
            // TODO: 调用 OMS 服务获取当前订单
            // List<OrderSnapshot> orders = omsClient.getOpenOrders(userId, symbol);
            
            // 临时返回空列表
            List<OrderSnapshot> orders = new ArrayList<>();
            
            return ApiResponse.success(orders);
            
        } catch (Exception e) {
            log.error("[PrivatePushController] Failed to get open orders, userId={}", userId, e);
            return ApiResponse.error(500, "Failed to get open orders");
        }
    }
    
    /**
     * 获取订单历史（快照）
     * 
     * GET /api/v1/private/orders/history
     */
    @GetMapping("/orders/history")
    public ApiResponse<List<OrderSnapshot>> getOrderHistory(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Long startTime,
            @RequestParam(required = false) Long endTime,
            @RequestParam(defaultValue = "100") int limit
    ) {
        log.info("[PrivatePushController] Get order history, userId={}, symbol={}, limit={}", 
                userId, symbol, limit);
        
        try {
            // TODO: 调用 OMS 服务获取订单历史
            // List<OrderSnapshot> orders = omsClient.getOrderHistory(userId, symbol, startTime, endTime, limit);
            
            // 临时返回空列表
            List<OrderSnapshot> orders = new ArrayList<>();
            
            return ApiResponse.success(orders);
            
        } catch (Exception e) {
            log.error("[PrivatePushController] Failed to get order history, userId={}", userId, e);
            return ApiResponse.error(500, "Failed to get order history");
        }
    }
    
    /**
     * 获取账户余额快照
     * 
     * GET /api/v1/private/account/balance
     */
    @GetMapping("/account/balance")
    public ApiResponse<AccountSnapshot> getAccountBalance(
            @RequestHeader("X-User-Id") Long userId
    ) {
        log.info("[PrivatePushController] Get account balance, userId={}", userId);
        
        try {
            // TODO: 调用 Account 服务获取余额快照
            // AccountSnapshot snapshot = accountClient.getBalanceSnapshot(userId);
            
            // 临时返回空对象
            AccountSnapshot snapshot = new AccountSnapshot();
            
            return ApiResponse.success(snapshot);
            
        } catch (Exception e) {
            log.error("[PrivatePushController] Failed to get account balance, userId={}", userId, e);
            return ApiResponse.error(500, "Failed to get account balance");
        }
    }
    
    /**
     * 获取持仓快照
     * 
     * GET /api/v1/private/position
     */
    @GetMapping("/position")
    public ApiResponse<List<PositionSnapshot>> getPositions(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) String symbol
    ) {
        log.info("[PrivatePushController] Get positions, userId={}, symbol={}", userId, symbol);
        
        try {
            // TODO: 调用 Position 服务获取持仓快照
            // List<PositionSnapshot> positions = positionClient.getPositions(userId, symbol);
            
            // 临时返回空列表
            List<PositionSnapshot> positions = new ArrayList<>();
            
            return ApiResponse.success(positions);
            
        } catch (Exception e) {
            log.error("[PrivatePushController] Failed to get positions, userId={}", userId, e);
            return ApiResponse.error(500, "Failed to get positions");
        }
    }
    
    /**
     * 恢复丢失的消息（断线重连时使用）
     *
     * GET /api/v1/private/messages/recover?lastSeq={seq}
     *
     * 用途：客户端断线重连后恢复丢失的消息
     */
    @GetMapping("/messages/recover")
    public ApiResponse<MessageRecoveryResponse> recoverMessages(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false, defaultValue = "0") long lastSeq
    ) {
        log.info("[PrivatePushController] Recover messages, userId={}, lastSeq={}", userId, lastSeq);

        try {
            // 从Redis获取丢失的消息
            List<com.alibaba.fastjson2.JSONObject> messages =
                    messagePersistenceService.getMessagesAfter(userId, lastSeq);

            MessageRecoveryResponse response = new MessageRecoveryResponse();
            response.setLastSeq(lastSeq);
            response.setMessages(messages);
            response.setRecoveredCount(messages.size());

            return ApiResponse.success(response);

        } catch (Exception e) {
            log.error("[PrivatePushController] Failed to recover messages, userId={}, lastSeq={}",
                    userId, lastSeq, e);
            return ApiResponse.error(500, "Failed to recover messages");
        }
    }

    /**
     * 获取会话统计（管理接口）
     *
     * GET /api/v1/private/stats
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats() {
        log.info("[PrivatePushController] Get stats");
        
        try {
            SessionManager.SessionStats sessionStats = sessionManager.getStats();
            
            Map<String, Object> stats = new HashMap<>();
            stats.put("sessions", sessionStats.getTotalSessions());
            stats.put("users", sessionStats.getTotalUsers());
            stats.put("ips", sessionStats.getTotalIps());
            
            return ApiResponse.success(stats);
            
        } catch (Exception e) {
            log.error("[PrivatePushController] Failed to get stats", e);
            return ApiResponse.error(500, "Failed to get stats");
        }
    }
    
    // ==================== 内部类 ====================
    
    @Data
    public static class OrderSnapshot {
        private Long orderId;
        private String clientOrderId;
        private String symbol;
        private String side;
        private String type;
        private String price;
        private String quantity;
        private String filledQuantity;
        private String status;
        private Long createdTime;
        private Long updatedTime;
        private Long seq;
    }
    
    @Data
    public static class AccountSnapshot {
        private Long userId;
        private List<Balance> balances;
        private Long timestamp;
    }
    
    @Data
    public static class Balance {
        private String asset;
        private String available;
        private String frozen;
    }
    
    @Data
    public static class PositionSnapshot {
        private String symbol;
        private String side;
        private String quantity;
        private String entryPrice;
        private String markPrice;
        private String unrealizedPnl;
        private Integer leverage;
        private String margin;
    }

    @Data
    public static class MessageRecoveryResponse {
        private long lastSeq;
        private int recoveredCount;
        private List<com.alibaba.fastjson2.JSONObject> messages;
    }

    @Data
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;
        
        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.code = 0;
            response.message = "success";
            response.data = data;
            return response;
        }
        
        public static <T> ApiResponse<T> error(int code, String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.code = code;
            response.message = message;
            return response;
        }
    }
}

