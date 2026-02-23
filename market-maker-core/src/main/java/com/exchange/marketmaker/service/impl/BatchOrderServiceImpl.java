package com.exchange.marketmaker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exchange.marketmaker.client.OmsClient;
import com.exchange.marketmaker.dto.request.BatchCancelRequest;
import com.exchange.marketmaker.dto.request.BatchOrderRequest;
import com.exchange.marketmaker.dto.request.CancelAllRequest;
import com.exchange.marketmaker.dto.request.ModifyOrderRequest;
import com.exchange.marketmaker.dto.response.BatchOrderResponse;
import com.exchange.marketmaker.entity.MarketMaker;
import com.exchange.marketmaker.mapper.MarketMakerMapper;
import com.exchange.marketmaker.service.BatchOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批量订单服务实现（完整版）
 */
@Slf4j
@Service
public class BatchOrderServiceImpl implements BatchOrderService {

    @Autowired
    private OmsClient omsClient;

    @Autowired
    private MarketMakerMapper marketMakerMapper;

    // 线程池用于并行处理批量订单
    private final ExecutorService executorService = new ThreadPoolExecutor(
            10,  // 核心线程数
            50,  // 最大线程数
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    @Override
    public BatchOrderResponse batchCreateOrder(Long userId, BatchOrderRequest request) {
        log.info("[MM-BatchOrder] Batch create order, userId={}, batchId={}, count={}",
                userId, request.getBatchId(), request.getOrders().size());

        // 验证做市商身份
        MarketMaker mm = marketMakerMapper.selectOne(
                new LambdaQueryWrapper<MarketMaker>()
                        .eq(MarketMaker::getUserId, userId)
        );
        if (mm == null || !"ACTIVE".equals(mm.getStatus())) {
            log.warn("[MM-BatchOrder] User is not active market maker, userId={}", userId);
            return buildErrorResponse(request.getBatchId(), request.getOrders().size(),
                    "User is not active market maker");
        }

        BatchOrderResponse response = new BatchOrderResponse();
        response.setBatchId(request.getBatchId());
        response.setTotalCount(request.getOrders().size());

        List<BatchOrderResponse.OrderResult> results = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 并行下单处理
        List<Future<BatchOrderResponse.OrderResult>> futures = new ArrayList<>();

        for (int i = 0; i < request.getOrders().size(); i++) {
            final int index = i;
            final BatchOrderRequest.SingleOrderRequest orderReq = request.getOrders().get(i);

            Future<BatchOrderResponse.OrderResult> future = executorService.submit(() -> {
                return processSingleOrder(userId, orderReq, index, request.getBatchId());
            });

            futures.add(future);
        }

        // 收集结果
        for (Future<BatchOrderResponse.OrderResult> future : futures) {
            try {
                BatchOrderResponse.OrderResult result = future.get(5, TimeUnit.SECONDS);
                results.add(result);

                if (result.getSuccess()) {
                    successCount.incrementAndGet();
                } else {
                    failCount.incrementAndGet();
                }

            } catch (TimeoutException e) {
                log.error("[MM-BatchOrder] Order processing timeout", e);
                BatchOrderResponse.OrderResult result = new BatchOrderResponse.OrderResult();
                result.setSuccess(false);
                result.setErrorMsg("Processing timeout");
                results.add(result);
                failCount.incrementAndGet();

            } catch (Exception e) {
                log.error("[MM-BatchOrder] Order processing failed", e);
                BatchOrderResponse.OrderResult result = new BatchOrderResponse.OrderResult();
                result.setSuccess(false);
                result.setErrorMsg(e.getMessage());
                results.add(result);
                failCount.incrementAndGet();
            }
        }

        response.setSuccessCount(successCount.get());
        response.setFailCount(failCount.get());
        response.setResults(results);

        log.info("[MM-BatchOrder] Batch create order finished, success={}, fail={}",
                successCount.get(), failCount.get());

        return response;
    }

    /**
     * 处理单个订单
     */
    private BatchOrderResponse.OrderResult processSingleOrder(
            Long userId, BatchOrderRequest.SingleOrderRequest orderReq, int index, String batchId) {

        BatchOrderResponse.OrderResult result = new BatchOrderResponse.OrderResult();
        result.setIndex(index);

        try {
            // 构建OMS下单请求
            OmsClient.SubmitOrderRequest omsReq = new OmsClient.SubmitOrderRequest();
            omsReq.setUserId(userId);
            omsReq.setTraceId(UUID.randomUUID().toString());
            omsReq.setRequestId(batchId + "-" + index);
            omsReq.setClientOrderId(orderReq.getClientOrderId() != null ?
                    orderReq.getClientOrderId() : batchId + "-" + index);
            omsReq.setSymbol(orderReq.getSymbol());
            omsReq.setSide(orderReq.getSide());
            omsReq.setType(orderReq.getOrderType());
            omsReq.setPrice(orderReq.getPrice());
            omsReq.setQuantity(orderReq.getQuantity());
            omsReq.setTimeInForce(orderReq.getTimeInForce() != null ?
                    orderReq.getTimeInForce() : "GTC");

            // 调用OMS下单
            OmsClient.SubmitOrderResponse omsResp = omsClient.submitOrder(omsReq);

            if (omsResp != null && omsResp.getSuccess()) {
                result.setSuccess(true);
                result.setOrderId(Long.parseLong(omsResp.getOrderId()));
                log.info("[MM-BatchOrder] Order created successfully, index={}, orderId={}",
                        index, omsResp.getOrderId());
            } else {
                result.setSuccess(false);
                result.setErrorMsg(omsResp != null ? omsResp.getErrorMessage() : "Unknown error");
                log.warn("[MM-BatchOrder] Order creation failed, index={}, error={}",
                        index, result.getErrorMsg());
            }

        } catch (Exception e) {
            log.error("[MM-BatchOrder] Create order exception, index={}", index, e);
            result.setSuccess(false);
            result.setErrorMsg(e.getMessage());
        }

        return result;
    }

    @Override
    public BatchOrderResponse batchCancelOrder(Long userId, BatchCancelRequest request) {
        log.info("[MM-BatchOrder] Batch cancel order, userId={}, batchId={}, orderIds={}",
                userId, request.getBatchId(), request.getOrderIds());

        BatchOrderResponse response = new BatchOrderResponse();
        response.setBatchId(request.getBatchId());

        List<Long> orderIds = request.getOrderIds();
        if (orderIds == null || orderIds.isEmpty()) {
            // 如果没有指定订单ID，按条件查询
            // TODO: 从OMS查询符合条件的订单
            log.warn("[MM-BatchOrder] No order IDs provided");
            return buildErrorResponse(request.getBatchId(), 0, "No order IDs provided");
        }

        response.setTotalCount(orderIds.size());

        List<BatchOrderResponse.OrderResult> results = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 并行撤单处理
        List<Future<BatchOrderResponse.OrderResult>> futures = new ArrayList<>();

        for (int i = 0; i < orderIds.size(); i++) {
            final int index = i;
            final Long orderId = orderIds.get(i);

            Future<BatchOrderResponse.OrderResult> future = executorService.submit(() -> {
                return processSingleCancel(userId, orderId, index, request.getBatchId());
            });

            futures.add(future);
        }

        // 收集结果
        for (Future<BatchOrderResponse.OrderResult> future : futures) {
            try {
                BatchOrderResponse.OrderResult result = future.get(5, TimeUnit.SECONDS);
                results.add(result);

                if (result.getSuccess()) {
                    successCount.incrementAndGet();
                } else {
                    failCount.incrementAndGet();
                }

            } catch (Exception e) {
                log.error("[MM-BatchOrder] Cancel order failed", e);
                BatchOrderResponse.OrderResult result = new BatchOrderResponse.OrderResult();
                result.setSuccess(false);
                result.setErrorMsg(e.getMessage());
                results.add(result);
                failCount.incrementAndGet();
            }
        }

        response.setSuccessCount(successCount.get());
        response.setFailCount(failCount.get());
        response.setResults(results);

        log.info("[MM-BatchOrder] Batch cancel order finished, success={}, fail={}",
                successCount.get(), failCount.get());

        return response;
    }

    /**
     * 处理单个撤单
     */
    private BatchOrderResponse.OrderResult processSingleCancel(
            Long userId, Long orderId, int index, String batchId) {

        BatchOrderResponse.OrderResult result = new BatchOrderResponse.OrderResult();
        result.setIndex(index);
        result.setOrderId(orderId);

        try {
            // 构建OMS撤单请求
            OmsClient.CancelOrderRequest omsReq = new OmsClient.CancelOrderRequest();
            omsReq.setUserId(userId);
            omsReq.setTraceId(UUID.randomUUID().toString());
            omsReq.setRequestId(batchId + "-cancel-" + index);
            omsReq.setOrderId(String.valueOf(orderId));

            // 调用OMS撤单
            OmsClient.CancelOrderResponse omsResp = omsClient.cancelOrder(omsReq);

            if (omsResp != null && omsResp.getSuccess()) {
                result.setSuccess(true);
                log.info("[MM-BatchOrder] Order cancelled successfully, index={}, orderId={}",
                        index, orderId);
            } else {
                result.setSuccess(false);
                result.setErrorMsg(omsResp != null ? omsResp.getErrorMessage() : "Unknown error");
                log.warn("[MM-BatchOrder] Order cancellation failed, index={}, orderId={}, error={}",
                        index, orderId, result.getErrorMsg());
            }

        } catch (Exception e) {
            log.error("[MM-BatchOrder] Cancel order exception, orderId={}", orderId, e);
            result.setSuccess(false);
            result.setErrorMsg(e.getMessage());
        }

        return result;
    }

    @Override
    public Integer cancelAllOrders(Long userId, CancelAllRequest request) {
        log.info("[MM-BatchOrder] Cancel all orders, userId={}, symbol={}, side={}",
                userId, request.getSymbol(), request.getSide());

        // TODO: 从OMS查询用户所有未完成订单
        // 1. 调用OMS查询接口，获取所有未完成订单
        // 2. 按symbol和side过滤
        // 3. 批量撤单

        // 临时实现：直接返回0
        // 实际应该查询订单表，然后调用批量撤单
        log.warn("[MM-BatchOrder] Cancel all orders not fully implemented yet");

        return 0;
    }

    @Override
    public Boolean modifyOrder(Long userId, ModifyOrderRequest request) {
        log.info("[MM-BatchOrder] Modify order, userId={}, orderId={}, newPrice={}, newQty={}",
                userId, request.getOrderId(), request.getNewPrice(), request.getNewQuantity());

        try {
            // 策略：先查询订单，再撤单，最后重新下单

            // 1. 查询原订单
            OmsClient.QueryOrderResponse orderInfo = omsClient.queryOrder(userId,
                    String.valueOf(request.getOrderId()));

            if (orderInfo == null) {
                log.warn("[MM-BatchOrder] Order not found, orderId={}", request.getOrderId());
                return false;
            }

            // 2. 撤销原订单
            OmsClient.CancelOrderRequest cancelReq = new OmsClient.CancelOrderRequest();
            cancelReq.setUserId(userId);
            cancelReq.setTraceId(UUID.randomUUID().toString());
            cancelReq.setRequestId(UUID.randomUUID().toString());
            cancelReq.setOrderId(String.valueOf(request.getOrderId()));

            OmsClient.CancelOrderResponse cancelResp = omsClient.cancelOrder(cancelReq);

            if (cancelResp == null || !cancelResp.getSuccess()) {
                log.warn("[MM-BatchOrder] Cancel order failed, orderId={}", request.getOrderId());
                return false;
            }

            // 3. 重新下单（使用新价格/数量）
            OmsClient.SubmitOrderRequest submitReq = new OmsClient.SubmitOrderRequest();
            submitReq.setUserId(userId);
            submitReq.setTraceId(UUID.randomUUID().toString());
            submitReq.setRequestId(UUID.randomUUID().toString());
            submitReq.setClientOrderId("modify-" + request.getOrderId());
            submitReq.setSymbol(orderInfo.getSymbol());
            submitReq.setSide(orderInfo.getSide());
            submitReq.setType("LIMIT");
            submitReq.setPrice(request.getNewPrice() != null ?
                    request.getNewPrice() : orderInfo.getPrice());
            submitReq.setQuantity(request.getNewQuantity() != null ?
                    request.getNewQuantity() : orderInfo.getQuantity());
            submitReq.setTimeInForce("GTC");

            OmsClient.SubmitOrderResponse submitResp = omsClient.submitOrder(submitReq);

            if (submitResp != null && submitResp.getSuccess()) {
                log.info("[MM-BatchOrder] Order modified successfully, oldOrderId={}, newOrderId={}",
                        request.getOrderId(), submitResp.getOrderId());
                return true;
            } else {
                log.warn("[MM-BatchOrder] Re-submit order failed after cancel");
                return false;
            }

        } catch (Exception e) {
            log.error("[MM-BatchOrder] Modify order failed", e);
            return false;
        }
    }

    /**
     * 构建错误响应
     */
    private BatchOrderResponse buildErrorResponse(String batchId, int totalCount, String errorMsg) {
        BatchOrderResponse response = new BatchOrderResponse();
        response.setBatchId(batchId);
        response.setTotalCount(totalCount);
        response.setSuccessCount(0);
        response.setFailCount(totalCount);

        List<BatchOrderResponse.OrderResult> results = new ArrayList<>();
        for (int i = 0; i < totalCount; i++) {
            BatchOrderResponse.OrderResult result = new BatchOrderResponse.OrderResult();
            result.setIndex(i);
            result.setSuccess(false);
            result.setErrorMsg(errorMsg);
            results.add(result);
        }
        response.setResults(results);

        return response;
    }
}
