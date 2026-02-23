package com.exchange.marketmaker.controller;

import com.exchange.common.core.Result;
import com.exchange.marketmaker.dto.request.BatchCancelRequest;
import com.exchange.marketmaker.dto.request.BatchOrderRequest;
import com.exchange.marketmaker.dto.request.CancelAllRequest;
import com.exchange.marketmaker.dto.request.ModifyOrderRequest;
import com.exchange.marketmaker.dto.response.BatchOrderResponse;
import com.exchange.marketmaker.service.BatchOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 批量订单Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/mm/batch-order")
public class BatchOrderController {

    @Autowired
    private BatchOrderService batchOrderService;

    /**
     * 批量下单
     */
    @PostMapping("/create")
    public Result<BatchOrderResponse> batchCreateOrder(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody BatchOrderRequest request) {

        log.info("[MM-API] Batch create order, userId={}, batchId={}, count={}",
                userId, request.getBatchId(), request.getOrders().size());

        // TODO: 验证做市商身份和权限
        // TODO: 频率限制检查

        BatchOrderResponse response = batchOrderService.batchCreateOrder(userId, request);

        return Result.success(response);
    }

    /**
     * 批量撤单
     */
    @PostMapping("/cancel")
    public Result<BatchOrderResponse> batchCancelOrder(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody BatchCancelRequest request) {

        log.info("[MM-API] Batch cancel order, userId={}, batchId={}",
                userId, request.getBatchId());

        BatchOrderResponse response = batchOrderService.batchCancelOrder(userId, request);

        return Result.success(response);
    }

    /**
     * 一键撤单
     */
    @PostMapping("/cancel-all")
    public Result<Map<String, Integer>> cancelAllOrders(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody CancelAllRequest request) {

        log.info("[MM-API] Cancel all orders, userId={}, symbol={}", userId, request.getSymbol());

        Integer cancelledCount = batchOrderService.cancelAllOrders(userId, request);

        Map<String, Integer> result = new HashMap<>();
        result.put("cancelledCount", cancelledCount);

        return Result.success(result);
    }

    /**
     * 修改订单
     */
    @PostMapping("/modify")
    public Result<Map<String, Object>> modifyOrder(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody ModifyOrderRequest request) {

        log.info("[MM-API] Modify order, userId={}, orderId={}", userId, request.getOrderId());

        Boolean success = batchOrderService.modifyOrder(userId, request);

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", request.getOrderId());
        result.put("newPrice", request.getNewPrice());
        result.put("newQuantity", request.getNewQuantity());
        result.put("modifyTime", System.currentTimeMillis());

        return Result.success(result);
    }
}
