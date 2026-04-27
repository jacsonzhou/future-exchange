package com.exchange.oms.controller;

import com.exchange.oms.dto.*;
import com.exchange.oms.dto.OrderListRequest;
import com.exchange.oms.dto.OrderListResponse;
import com.exchange.oms.service.OmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * OMS对外接口Controller
 * 
 * 暴露给API Gateway的REST接口
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/oms/order")
public class OmsController {
    
    @Autowired
    private OmsService omsService;
    
    /**
     * 提交订单
     * 
     * POST /api/v1/oms/order/submit
     * 
     * Headers:
     * - X-Trace-Id
     * - X-Request-Id
     * - X-User-Id
     * - X-Idempotency-Key (必填)
     */
    @PostMapping("/submit")
    public SubmitOrderResponse submitOrder(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestHeader(value = "X-User-Id") Long userId,
            @RequestBody SubmitOrderRequest request) {
        
        log.info("[OMS-API] Submit order, userId={}, clientOrderId={}, traceId={}", 
            userId, request.getClientOrderId(), traceId);
        
        // 设置header信息到request
        request.setTraceId(traceId);
        request.setRequestId(requestId);
        request.setUserId(userId);
        
        return omsService.submitOrder(request);
    }

    /**
     * 按 clientOrderId 确认提交结果（网关降级幂等确认）
     *
     * GET /api/v1/oms/order/confirm-submit?clientOrderId=xxx
     *
     * Headers:
     * - X-User-Id
     */
    @GetMapping("/confirm-submit")
    public SubmitOrderResponse confirmSubmitByClientOrderId(
            @RequestHeader(value = "X-User-Id") Long userId,
            @RequestParam("clientOrderId") String clientOrderId) {

        log.info("[OMS-API] Confirm submit, userId={}, clientOrderId={}", userId, clientOrderId);
        return omsService.confirmSubmitByClientOrderId(userId, clientOrderId);
    }
    
    /**
     * 撤单
     * 
     * POST /api/v1/oms/order/cancel
     * 
     * Headers:
     * - X-Trace-Id
     * - X-Request-Id
     * - X-User-Id
     */
    @PostMapping("/cancel")
    public CancelOrderResponse cancelOrder(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestHeader(value = "X-User-Id") Long userId,
            @RequestBody CancelOrderRequest request) {
        
        log.info("[OMS-API] Cancel order, userId={}, orderId={}, traceId={}", 
            userId, request.getOrderId(), traceId);
        
        // 设置header信息到request
        request.setTraceId(traceId);
        request.setRequestId(requestId);
        request.setUserId(userId);
        
        return omsService.cancelOrder(request);
    }
    
    /**
     * 查询订单
     * 
     * GET /api/v1/oms/order/query?orderId=xxx
     * 
     * Headers:
     * - X-User-Id
     */
    @GetMapping("/query")
    public QueryOrderResponse queryOrder(
            @RequestHeader(value = "X-User-Id") Long userId,
            @RequestParam("orderId") String orderId) {
        
        log.info("[OMS-API] Query order, userId={}, orderId={}", userId, orderId);
        
        QueryOrderRequest request = new QueryOrderRequest();
        request.setUserId(userId);
        request.setOrderId(orderId);
        
        return omsService.queryOrder(request);
    }
    
    /**
     * 查询订单列表
     * 
     * GET /api/v1/oms/order/list?status=NEW,PARTIALLY_FILLED&limit=20
     * 
     * Headers:
     * - X-User-Id
     * 
     * @param status 可选，状态过滤（多个用逗号分隔）
     * @param symbol 可选，交易对过滤
     * @param offset 可选，分页偏移，默认0
     * @param limit 可选，分页大小，默认20
     */
    @GetMapping("/list")
    public OrderListResponse queryOrderList(
            @RequestHeader(value = "X-User-Id") Long userId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "offset", required = false, defaultValue = "0") Integer offset,
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit) {
        
        log.info("[OMS-API] Query order list, userId={}, status={}, symbol={}, offset={}, limit={}", 
            userId, status, symbol, offset, limit);
        
        OrderListRequest request = new OrderListRequest();
        request.setUserId(userId);
        request.setStatus(status);
        request.setSymbol(symbol);
        request.setOffset(offset);
        request.setLimit(Math.min(limit, 100)); // 最大100条
        
        return omsService.queryOrderList(request);
    }
}




