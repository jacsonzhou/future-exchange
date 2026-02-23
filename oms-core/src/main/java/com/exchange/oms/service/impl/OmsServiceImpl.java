package com.exchange.oms.service.impl;

import com.exchange.common.core.IdGenerator;
import com.exchange.oms.dto.*;
import com.exchange.oms.dto.OrderListRequest;
import com.exchange.oms.dto.OrderListResponse;
import com.exchange.oms.entity.OmsIdempotentKey;
import com.exchange.oms.entity.OmsOrder;
import com.exchange.oms.entity.OmsOrderEvent;
import com.exchange.oms.entity.OmsOrderStateLog;
import com.exchange.oms.enums.OmsErrorCode;
import com.exchange.oms.exception.OmsException;
import com.exchange.oms.mapper.OmsIdempotentKeyMapper;
import com.exchange.oms.mapper.OmsOrderEventMapper;
import com.exchange.oms.mapper.OmsOrderMapper;
import com.exchange.oms.mapper.OmsOrderStateLogMapper;
import com.exchange.oms.service.OmsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * OMS核心服务实现
 * 
 * 核心设计：
 * 1. 订单状态机驱动
 * 2. 幂等保证
 * 3. 事件溯源
 * 4. 状态审计链
 */
@Slf4j
@Service
public class OmsServiceImpl implements OmsService {
    
    @Autowired
    private OmsOrderMapper orderMapper;
    
    @Autowired
    private OmsOrderEventMapper eventMapper;
    
    @Autowired
    private OmsOrderStateLogMapper stateLogMapper;
    
    @Autowired
    private OmsIdempotentKeyMapper idempotentKeyMapper;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private com.exchange.oms.publisher.OrderEventPublisher orderEventPublisher;
    
    @Autowired
    private com.exchange.oms.publisher.OrderStatePushPublisher orderStatePushPublisher;
    
    @Autowired(required = false)
    private com.exchange.oms.client.LedgerClient ledgerClient;
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubmitOrderResponse submitOrder(SubmitOrderRequest request) {
        log.info("[OMS] Submit order start, userId={}, clientOrderId={}, symbol={}", 
            request.getUserId(), request.getClientOrderId(), request.getSymbol());
        
        try {
            // 1. 参数校验
            validateSubmitRequest(request);
            
            // 2. 幂等检查
            OmsIdempotentKey existingKey = idempotentKeyMapper.selectByUserIdAndKey(
                request.getUserId(), request.getClientOrderId());
            
            if (existingKey != null) {
                // 幂等返回
                String requestHash = calculateRequestHash(request);
                if (!requestHash.equals(existingKey.getRequestHash())) {
                    throw new OmsException(OmsErrorCode.OMS_1002);
                }
                
                OmsOrder existingOrder = orderMapper.selectById(existingKey.getOrderId());
                return SubmitOrderResponse.success(
                    existingOrder.getId().toString(),
                    mapOrderStatus(existingOrder.getStatus()),
                    existingOrder.getClientOrderId()
                );
            }
            
            // 3. 创建订单（NEW）
            Long orderId = IdGenerator.generate();
            long now = System.currentTimeMillis();
            
            OmsOrder order = new OmsOrder();
            order.setId(orderId);
            order.setUserId(request.getUserId());
            order.setClientOrderId(request.getClientOrderId());
            order.setSymbol(request.getSymbol());
            order.setSide(mapSide(request.getSide()));
            order.setType(mapType(request.getType()));
            order.setPrice(new BigDecimal(request.getPrice()));
            order.setQuantity(new BigDecimal(request.getQuantity()));
            order.setFilledQuantity(BigDecimal.ZERO);
            order.setStatus(0); // NEW
            order.setTimeInForce(request.getTimeInForce());
            order.setRiskCheckStatus(0);
            order.setFreezeStatus(0);
            order.setVersion(0);
            order.setCreatedAt(now);
            order.setUpdatedAt(now);
            
            orderMapper.insert(order);
            
            // 4. 记录幂等key
            OmsIdempotentKey idemKey = new OmsIdempotentKey();
            idemKey.setUserId(request.getUserId());
            idemKey.setIdemKey(request.getClientOrderId());
            idemKey.setOrderId(orderId);
            idemKey.setRequestHash(calculateRequestHash(request));
            idemKey.setCreatedAt(now);
            idempotentKeyMapper.insert(idemKey);
            
            // 5. 记录事件
            recordEvent(orderId, request.getUserId(), request.getSymbol(), 
                "ORDER_SUBMIT", "OMS", request);
            
            // 6. 记录状态变更
            recordStateLog(orderId, request.getUserId(), null, 0, "ORDER_CREATED", 
                "Order created", request.getTraceId());
            
            // 7. 调用Hard Risk Gate（同步）
            // TODO: 调用风控服务
            boolean riskPassed = true; // 模拟
            
            if (!riskPassed) {
                updateOrderStatus(order, 6, "RISK_REJECTED", "Risk check failed");
                throw new OmsException(OmsErrorCode.OMS_3001);
            }
            
            // 8. 更新状态=PENDING_RISK
            updateOrderStatus(order, 1, "RISK_PASSED", "Risk check passed");
            
            // 9. 调用Ledger Service冻结保证金（同步）
            BigDecimal requiredMargin = calculateRequiredMargin(
                new BigDecimal(request.getPrice()),
                new BigDecimal(request.getQuantity()),
                request.getLeverage() != null ? request.getLeverage() : 10 // 默认10倍杠杆
            );
            
            if (ledgerClient != null) {
                try {
                    com.exchange.oms.client.LedgerClient.FreezeRequest freezeRequest = 
                        new com.exchange.oms.client.LedgerClient.FreezeRequest();
                    freezeRequest.setUserId(request.getUserId());
                    freezeRequest.setCurrency("USDT"); // 默认USDT
                    freezeRequest.setAmount(requiredMargin);
                    freezeRequest.setOrderId(orderId);
                    
                    ledgerClient.freezeMargin(freezeRequest);
                    log.info("[OMS] ✅ Freeze margin success, userId={}, amount={}, orderId={}", 
                        request.getUserId(), requiredMargin, orderId);
                } catch (Exception e) {
                    log.error("[OMS] ❌ Freeze margin failed, userId={}, orderId={}", 
                        request.getUserId(), orderId, e);
                    updateOrderStatus(order, 6, "FREEZE_FAILED", "Freeze failed: " + e.getMessage());
                    throw new OmsException(OmsErrorCode.OMS_3002);
                }
            } else {
                log.warn("[OMS] LedgerClient not available, skipping freeze (test mode)");
            }
            
            // 10. 更新状态=FROZEN
            updateOrderStatus(order, 2, "FROZEN", "Fund frozen");
            
            // 11. 投递OrderEvent -> Match Engine ⭐
            com.exchange.oms.dto.OrderEventCommand command = buildOrderCommand(order, request);
            log.info("[OMS-LINK] >>> Sending to Kafka, orderId={}, symbol={}, side={}, price={}, qty={}", 
                orderId, order.getSymbol(), order.getSide(), order.getPrice(), order.getQuantity());
            orderEventPublisher.publishOrderEvent(command);
            log.info("[OMS-LINK] >>> Kafka sent success, orderId={}", orderId);
            
            // 12. 发送执行报告到 WebSocket (private-order-state topic)
            orderStatePushPublisher.publishNewOrder(order);
            log.info("[OMS] Execution report sent to private push, orderId={}", orderId);
            
            log.info("[OMS] Submit order success, orderId={}", orderId);
            
            return SubmitOrderResponse.success(
                orderId.toString(),
                "FROZEN",
                request.getClientOrderId()
            );
            
        } catch (OmsException e) {
            log.error("[OMS] Submit order failed, error={}", e.getErrorMessage(), e);
            return SubmitOrderResponse.fail(e.getErrorCode(), e.getErrorMessage());
        } catch (Exception e) {
            log.error("[OMS] Submit order system error", e);
            return SubmitOrderResponse.fail(OmsErrorCode.OMS_9001.getCode(), 
                OmsErrorCode.OMS_9001.getMessage());
        }
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CancelOrderResponse cancelOrder(CancelOrderRequest request) {
        log.info("[OMS] Cancel order start, userId={}, orderId={}", 
            request.getUserId(), request.getOrderId());
        
        try {
            // 1. 查询订单
            Long orderId = Long.parseLong(request.getOrderId());
            OmsOrder order = orderMapper.selectById(orderId);
            
            if (order == null) {
                throw new OmsException(OmsErrorCode.OMS_2001);
            }
            
            // 2. 权限校验
            if (!order.getUserId().equals(request.getUserId())) {
                throw new OmsException(OmsErrorCode.OMS_2001);
            }
            
            // 3. 状态校验
            if (!order.isCancelable()) {
                throw new OmsException(OmsErrorCode.OMS_2002);
            }
            
            // 4. 解冻保证金（如果订单已冻结）
            if (order.getFreezeStatus() != null && order.getFreezeStatus() == 1 && ledgerClient != null) {
                try {
                    // 计算解冻金额：剩余数量 * 价格 / 杠杆
                    BigDecimal unfreezeAmount = calculateRequiredMargin(
                        order.getPrice(),
                        order.getRemainingQuantity(),
                        10 // 默认10倍杠杆，实际应该从订单中读取
                    );
                    
                    com.exchange.oms.client.LedgerClient.UnfreezeRequest unfreezeRequest = 
                        new com.exchange.oms.client.LedgerClient.UnfreezeRequest();
                    unfreezeRequest.setUserId(order.getUserId());
                    unfreezeRequest.setCurrency("USDT");
                    unfreezeRequest.setAmount(unfreezeAmount);
                    unfreezeRequest.setOrderId(orderId);
                    
                    ledgerClient.unfreezeMargin(unfreezeRequest);
                    log.info("[OMS] ✅ Unfreeze margin success, userId={}, amount={}, orderId={}", 
                        order.getUserId(), unfreezeAmount, orderId);
                } catch (Exception e) {
                    log.error("[OMS] ❌ Unfreeze margin failed, userId={}, orderId={}", 
                        order.getUserId(), orderId, e);
                    // 解冻失败不影响撤单，记录日志即可
                }
            }
            
            // 5. 更新状态=CANCELED
            int updated = orderMapper.updateStatus(
                orderId, order.getStatus(), 5, System.currentTimeMillis(), order.getVersion());
            
            if (updated == 0) {
                throw new OmsException(OmsErrorCode.OMS_9003);
            }
            
            // 5. 记录事件
            recordEvent(orderId, request.getUserId(), order.getSymbol(), 
                "ORDER_CANCEL", "OMS", request);
            
            // 6. 记录状态变更
            recordStateLog(orderId, request.getUserId(), order.getStatus(), 5, 
                "USER_CANCEL", "User canceled", request.getTraceId());
            
            // 7. 投递CancelEvent -> Match Engine ⭐
            com.exchange.oms.dto.OrderEventCommand cancelCommand = buildCancelCommand(order);
            orderEventPublisher.publishOrderEvent(cancelCommand);
            log.info("[OMS] Cancel event sent to match engine, orderId={}", orderId);
            
            // 8. 解冻资金（异步）
            // TODO: 通知账户服务解冻
            
            log.info("[OMS] Cancel order success, orderId={}", orderId);
            
            return CancelOrderResponse.success(request.getOrderId(), "CANCELED");
            
        } catch (OmsException e) {
            log.error("[OMS] Cancel order failed, error={}", e.getErrorMessage(), e);
            return CancelOrderResponse.fail(e.getErrorCode(), e.getErrorMessage());
        } catch (Exception e) {
            log.error("[OMS] Cancel order system error", e);
            return CancelOrderResponse.fail(OmsErrorCode.OMS_9001.getCode(), 
                OmsErrorCode.OMS_9001.getMessage());
        }
    }
    
    @Override
    public QueryOrderResponse queryOrder(QueryOrderRequest request) {
        Long orderId = Long.parseLong(request.getOrderId());
        OmsOrder order = orderMapper.selectById(orderId);
        
        if (order == null || !order.getUserId().equals(request.getUserId())) {
            return null;
        }
        
        return convertToResponse(order);
    }
    
    @Override
    public OrderListResponse queryOrderList(OrderListRequest request) {
        log.info("[OMS] Query order list, userId={}, symbol={}, status={}, offset={}, limit={}",
            request.getUserId(), request.getSymbol(), request.getStatus(), 
            request.getOffset(), request.getLimit());
        
        // 根据状态判断查询活跃订单还是历史订单
        boolean isActiveQuery = isActiveStatusQuery(request.getStatus());
        
        List<OmsOrder> orders;
        if (isActiveQuery) {
            // 查询活跃订单
            orders = orderMapper.selectActiveOrdersWithLimit(
                request.getUserId(),
                request.getLimit()
            );
        } else {
            // 查询历史订单
            orders = orderMapper.selectHistoryOrdersWithLimit(
                request.getUserId(),
                request.getLimit()
            );
        }
        
        // 转换响应
        List<QueryOrderResponse> orderResponses = orders.stream()
            .map(this::convertToResponse)
            .collect(java.util.stream.Collectors.toList());
        
        OrderListResponse response = new OrderListResponse();
        response.setOrders(orderResponses);
        response.setTotal((long) orderResponses.size());
        response.setOffset(request.getOffset());
        response.setLimit(request.getLimit());
        response.setHasMore(orderResponses.size() >= request.getLimit());
        
        return response;
    }
    
    /**
     * 判断是否是查询活跃订单
     */
    private boolean isActiveStatusQuery(String status) {
        if (status == null || status.isEmpty()) {
            return true; // 默认查询活跃订单
        }
        // 如果状态包含终态 (FILLED/CANCELED/REJECTED)，认为是历史查询
        // 注意：PARTIALLY_FILLED 不是终态，是活跃状态
        String upperStatus = status.toUpperCase();
        
        // 检查是否包含终态（注意排除 PARTIALLY_FILLED）
        boolean hasFilled = upperStatus.contains("FILLED");
        boolean hasPartiallyFilled = upperStatus.contains("PARTIALLY_FILLED");
        boolean hasCanceled = upperStatus.contains("CANCELED");
        boolean hasRejected = upperStatus.contains("REJECTED");
        
        // 只有当包含 FILLED 但不包含 PARTIALLY_FILLED 时，才认为是终态
        boolean hasFinalFilled = hasFilled && !hasPartiallyFilled;
        boolean containsFinalStatus = hasFinalFilled || hasCanceled || hasRejected;
        
        log.info("[DEBUG] status={}, hasFilled={}, hasPartiallyFilled={}, hasFinalFilled={}, isActive={}", 
            status, hasFilled, hasPartiallyFilled, hasFinalFilled, !containsFinalStatus);
        
        return !containsFinalStatus;
    }
    
    /**
     * 转换订单为响应对象
     * 
     * 注意：数据库中 price/quantity/filledQuantity 是以 8 位小数精度存储的内部格式
     * 需要除以 10^8 转换为实际金额后返回给前端
     */
    private QueryOrderResponse convertToResponse(OmsOrder order) {
        QueryOrderResponse response = new QueryOrderResponse();
        response.setOrderId(order.getId().toString());
        response.setClientOrderId(order.getClientOrderId());
        response.setSymbol(order.getSymbol());
        response.setSide(mapOrderSide(order.getSide()));
        response.setType(mapOrderType(order.getType()));
        
        // 精度系数：8位小数 = 10^8
        BigDecimal SCALE = new BigDecimal("100000000");
        
        // 将内部格式转换为实际金额（除以 10^8）
        if (order.getPrice() != null) {
            BigDecimal actualPrice = order.getPrice().divide(SCALE, 8, java.math.RoundingMode.HALF_UP);
            response.setPrice(actualPrice.stripTrailingZeros().toPlainString());
        }
        
        BigDecimal actualQuantity = order.getQuantity().divide(SCALE, 8, java.math.RoundingMode.HALF_UP);
        response.setQuantity(actualQuantity.stripTrailingZeros().toPlainString());
        
        BigDecimal actualFilledQty = order.getFilledQuantity().divide(SCALE, 8, java.math.RoundingMode.HALF_UP);
        response.setFilledQuantity(actualFilledQty.stripTrailingZeros().toPlainString());
        
        response.setStatus(mapOrderStatus(order.getStatus()));
        response.setCreateTime(order.getCreatedAt());
        return response;
    }
    

    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleTradeReport(Long orderId, String filledQuantity) {
        log.info("[OMS] Handle trade report, orderId={}, filled={}", orderId, filledQuantity);
        
        OmsOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            log.error("[OMS] Order not found, orderId={}", orderId);
            return;
        }
        
        BigDecimal delta = new BigDecimal(filledQuantity);
        BigDecimal newFilled = order.getFilledQuantity().add(delta);
        
        // 判断新状态
        int newStatus = 3; // PARTIALLY_FILLED
        if (newFilled.compareTo(order.getQuantity()) >= 0) {
            newStatus = 4; // FILLED
        }
        
        // 更新已成交数量和状态
        int updated = orderMapper.updateFilledQuantity(
            orderId, delta, newStatus, System.currentTimeMillis(), order.getVersion());
        
        if (updated == 0) {
            log.error("[OMS] Update filled quantity failed, orderId={}", orderId);
            return;
        }
        
        // 记录状态变更
        recordStateLog(orderId, order.getUserId(), order.getStatus(), newStatus, 
            "TRADE_FILLED", "Trade filled: " + filledQuantity, null);
        
        log.info("[OMS] Handle trade report success, orderId={}, newStatus={}", 
            orderId, newStatus);
    }
    
    // ==================== 私有辅助方法 ====================
    
    private void validateSubmitRequest(SubmitOrderRequest request) {
        if (request.getUserId() == null || request.getUserId() <= 0) {
            throw new OmsException(OmsErrorCode.OMS_4001);
        }
        if (request.getClientOrderId() == null || request.getClientOrderId().isEmpty()) {
            throw new OmsException(OmsErrorCode.OMS_4001);
        }
        if (request.getSymbol() == null || request.getSymbol().isEmpty()) {
            throw new OmsException(OmsErrorCode.OMS_4001);
        }
        if (request.getQuantity() == null) {
            throw new OmsException(OmsErrorCode.OMS_4003);
        }
        
        BigDecimal qty = new BigDecimal(request.getQuantity());
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new OmsException(OmsErrorCode.OMS_4003);
        }
        
        if ("LIMIT".equals(request.getType())) {
            if (request.getPrice() == null) {
                throw new OmsException(OmsErrorCode.OMS_4002);
            }
            BigDecimal price = new BigDecimal(request.getPrice());
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                throw new OmsException(OmsErrorCode.OMS_4002);
            }
        }
    }
    
    private String calculateRequestHash(SubmitOrderRequest request) {
        String data = request.getUserId() + "|" + 
                     request.getClientOrderId() + "|" + 
                     request.getSymbol() + "|" + 
                     request.getSide() + "|" + 
                     request.getType() + "|" + 
                     request.getPrice() + "|" + 
                     request.getQuantity();
        return DigestUtils.md5DigestAsHex(data.getBytes(StandardCharsets.UTF_8));
    }
    
    private void updateOrderStatus(OmsOrder order, Integer newStatus, 
                                   String reasonCode, String reasonMsg) {
        long now = System.currentTimeMillis();
        int updated = orderMapper.updateStatus(
            order.getId(), order.getStatus(), newStatus, now, order.getVersion());
        
        if (updated == 0) {
            throw new OmsException(OmsErrorCode.OMS_9003);
        }
        
        recordStateLog(order.getId(), order.getUserId(), order.getStatus(), 
            newStatus, reasonCode, reasonMsg, null);
        
        order.setStatus(newStatus);
        order.setVersion(order.getVersion() + 1);
        order.setUpdatedAt(now);
    }
    
    private void recordEvent(Long orderId, Long userId, String symbol, 
                           String eventType, String eventSource, Object payload) {
        try {
            OmsOrderEvent event = new OmsOrderEvent();
            event.setOrderId(orderId);
            event.setUserId(userId);
            event.setSymbol(symbol);
            event.setEventType(eventType);
            event.setEventSource(eventSource);
            event.setEventPayload(objectMapper.writeValueAsString(payload));
            event.setCreatedAt(System.currentTimeMillis());
            eventMapper.insert(event);
        } catch (Exception e) {
            log.error("[OMS] Record event failed", e);
        }
    }
    
    private void recordStateLog(Long orderId, Long userId, Integer fromStatus, 
                               Integer toStatus, String reasonCode, String reasonMsg, 
                               String traceId) {
        OmsOrderStateLog stateLog = new OmsOrderStateLog();
        stateLog.setOrderId(orderId);
        stateLog.setUserId(userId);
        stateLog.setFromStatus(fromStatus != null ? fromStatus : -1);
        stateLog.setToStatus(toStatus);
        stateLog.setReasonCode(reasonCode);
        stateLog.setReasonMsg(reasonMsg);
        stateLog.setOperator("SYSTEM");
        stateLog.setTraceId(traceId);
        stateLog.setCreatedAt(System.currentTimeMillis());
        stateLogMapper.insert(stateLog);
    }
    
    private Integer mapSide(String side) {
        return "BUY".equals(side) ? 0 : 1;
    }
    
    private Integer mapType(String type) {
        return "LIMIT".equals(type) ? 0 : 1;
    }
    
    private String mapOrderSide(Integer side) {
        return side == 0 ? "BUY" : "SELL";
    }
    
    private String mapOrderType(Integer type) {
        return type == 0 ? "LIMIT" : "MARKET";
    }
    
    private String mapOrderStatus(Integer status) {
        switch (status) {
            case 0: return "NEW";
            case 1: return "PENDING_RISK";
            case 2: return "FROZEN";
            case 3: return "PARTIALLY_FILLED";
            case 4: return "FILLED";
            case 5: return "CANCELED";
            case 6: return "REJECTED";
            default: return "UNKNOWN";
        }
    }
    
    /**
     * 计算所需保证金
     * 
     * 公式：保证金 = 价格 * 数量 / 杠杆倍数
     * 
     * @param price 价格
     * @param quantity 数量
     * @param leverage 杠杆倍数
     * @return 所需保证金
     */
    /**
     * 计算所需保证金
     * 
     * 注意：price 和 quantity 是 8 位小数的整数格式（如 4000000000000 表示 40000.00000000）
     * 需要先除以 10^8 转换为实际金额，再计算保证金
     */
    private BigDecimal calculateRequiredMargin(BigDecimal price, BigDecimal quantity, Integer leverage) {
        if (price == null || quantity == null || leverage == null || leverage <= 0) {
            throw new IllegalArgumentException("Invalid margin calculation parameters");
        }
        
        // 精度系数：8位小数 = 10^8
        BigDecimal SCALE = new BigDecimal("100000000");
        
        // 转换为实际金额：price 和 quantity 都是 8 位小数的整数格式
        BigDecimal actualPrice = price.divide(SCALE, 8, java.math.RoundingMode.HALF_UP);
        BigDecimal actualQuantity = quantity.divide(SCALE, 8, java.math.RoundingMode.HALF_UP);
        
        // 计算保证金：价格 * 数量 / 杠杆
        BigDecimal margin = actualPrice.multiply(actualQuantity)
            .divide(BigDecimal.valueOf(leverage), 8, java.math.RoundingMode.HALF_UP);
        
        log.debug("[OMS] Calculate margin: price={} (actual={}), quantity={} (actual={}), leverage={}, margin={}",
            price, actualPrice, quantity, actualQuantity, leverage, margin);
        
        return margin;
    }
    
    /**
     * 构建订单命令（发送给Match Engine）
     */
    private com.exchange.oms.dto.OrderEventCommand buildOrderCommand(OmsOrder order, SubmitOrderRequest request) {
        com.exchange.oms.dto.OrderEventCommand command = new com.exchange.oms.dto.OrderEventCommand();
        command.setEventType("ORDER_SUBMIT");
        command.setOrderId(order.getId());
        command.setUserId(order.getUserId());
        command.setSymbol(order.getSymbol());
        command.setSide(mapOrderSide(order.getSide()));
        command.setOrderType(mapOrderType(order.getType()));
        // 🔥 修复：发送未缩放的价格（如 50000.00），让 Match Engine 自己乘以 PRICE_SCALE
        // 避免重复缩放导致溢出
        if (order.getPrice() != null) {
            // 将已缩放的价格（5000000000000）还原为原始价格（50000.00）
            BigDecimal scaledPrice = order.getPrice();
            BigDecimal originalPrice = scaledPrice.divide(new BigDecimal(100_000_000), 8, BigDecimal.ROUND_HALF_UP);
            command.setPrice(originalPrice.toPlainString());
        }
        // 🔥 数量保持原始值（如 1.00），不要除以 PRICE_SCALE
        // Match Engine 会处理数量的显示
        command.setQuantity(order.getQuantity().divide(new BigDecimal(100_000_000), 8, BigDecimal.ROUND_HALF_UP).toPlainString());
        command.setEventTime(System.currentTimeMillis());
        return command;
    }
    
    /**
     * 构建撤单命令
     */
    private com.exchange.oms.dto.OrderEventCommand buildCancelCommand(OmsOrder order) {
        com.exchange.oms.dto.OrderEventCommand command = new com.exchange.oms.dto.OrderEventCommand();
        command.setEventType("ORDER_CANCEL");
        command.setOrderId(order.getId());
        command.setUserId(order.getUserId());
        command.setSymbol(order.getSymbol());
        command.setEventTime(System.currentTimeMillis());
        return command;
    }
}

