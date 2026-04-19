package com.exchange.oms.service.impl;

import com.exchange.common.core.IdGenerator;
import com.exchange.common.core.Money;
import com.exchange.oms.dto.*;
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

    @Autowired(required = false)
    private com.exchange.oms.client.HardRiskClient hardRiskClient;

    @Autowired(required = false)
    private com.exchange.oms.client.SnapshotClient snapshotClient;

    @Autowired(required = false)
    private com.exchange.oms.service.OrderService orderService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubmitOrderResponse submitOrder(SubmitOrderRequest request) {
        log.info("[OMS] Submit order start, userId={}, clientOrderId={}, symbol={}",
            request.getUserId(), request.getClientOrderId(), request.getSymbol());

        Long orderId = null;
        boolean ledgerFrozen = false;
        BigDecimal requiredMargin = null;

        try {
            // 1. 参数校验
            validateSubmitRequest(request);

            // 2. 幂等检查
            OmsIdempotentKey existingKey = idempotentKeyMapper.selectByUserIdAndKey(
                request.getUserId(), request.getClientOrderId());

            if (existingKey != null) {
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
            orderId = IdGenerator.generate();
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
            order.setLeverage(request.getLeverage());
            order.setVersion(0);
            order.setCreatedAt(now);
            order.setUpdatedAt(now);
            orderMapper.insert(order);

            // 4. 记录幂等key + 事件 + 状态日志
            OmsIdempotentKey idemKey = new OmsIdempotentKey();
            idemKey.setUserId(request.getUserId());
            idemKey.setIdemKey(request.getClientOrderId());
            idemKey.setOrderId(orderId);
            idemKey.setRequestHash(calculateRequestHash(request));
            idemKey.setCreatedAt(now);
            idempotentKeyMapper.insert(idemKey);

            recordEvent(orderId, request.getUserId(), request.getSymbol(),
                "ORDER_SUBMIT", "OMS", request);
            recordStateLog(orderId, request.getUserId(), null, 0, "ORDER_CREATED",
                "Order created", request.getTraceId());

            // 5. 🔥 调用硬风控（Fail-Close）
            if (hardRiskClient != null && snapshotClient != null) {
                long requiredMarginLong = calculateRequiredMarginInLong(
                    order.getPrice(), order.getQuantity(), order.getLeverage());
                Long totalBalance = snapshotClient.getUserAvailableBalance(request.getUserId());
                if (totalBalance == null) totalBalance = 0L;

                CheckOrderRiskRequest riskRequest = buildRiskRequest(request, orderId);
                CheckOrderRiskResponse riskResponse = hardRiskClient.checkRisk(
                    riskRequest, requiredMarginLong, totalBalance);

                if (riskResponse == null || !"PASS".equals(riskResponse.getResult())) {
                    String reason = (riskResponse != null) ? riskResponse.getRejectReason() : "NO_RESPONSE";
                    String message = (riskResponse != null) ? riskResponse.getRejectMessage() : "Risk check no response";
                    log.warn("[OMS] Risk check rejected: orderId={}, reason={}, message={}", orderId, reason, message);
                    updateOrderStatus(order, 6, "REJECTED", "Risk check failed: " + message);
                    throw new OmsException(OmsErrorCode.OMS_3001);
                }
                log.info("[OMS] Risk check passed: orderId={}", orderId);
            } else {
                log.warn("[OMS] HardRiskClient or SnapshotClient not available, skipping risk check (test mode)");
            }
            updateOrderStatus(order, 1, "PENDING_RISK", "Risk check passed");

            // 6. 调用 Ledger 冻结保证金
            requiredMargin = calculateRequiredMargin(
                order.getPrice(), order.getQuantity(),
                request.getLeverage() != null ? request.getLeverage() : 10);

            if (ledgerClient != null) {
                try {
                    com.exchange.oms.client.LedgerClient.FreezeRequest freezeRequest =
                        new com.exchange.oms.client.LedgerClient.FreezeRequest();
                    freezeRequest.setUserId(request.getUserId());
                    freezeRequest.setCurrency("USDT");
                    freezeRequest.setAmount(requiredMargin);
                    freezeRequest.setOrderId(orderId);
                    ledgerClient.freezeMargin(freezeRequest);
                    ledgerFrozen = true;
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
            updateOrderStatus(order, 2, "FROZEN", "Fund frozen");
            order.setFreezeStatus(1);
            orderMapper.updateById(order);

            // 7. 投递 OrderEvent -> Match Engine
            com.exchange.oms.dto.OrderEventCommand command = buildOrderCommand(order, request);
            orderEventPublisher.publishOrderEvent(command);
            log.info("[OMS] Order event sent to match engine, orderId={}", orderId);

            // 8. 推送 WebSocket
            orderStatePushPublisher.publishNewOrder(order);
            log.info("[OMS] Execution report sent to private push, orderId={}", orderId);

            log.info("[OMS] Submit order success, orderId={}", orderId);
            return SubmitOrderResponse.success(orderId.toString(), "FROZEN", request.getClientOrderId());

        } catch (OmsException e) {
            log.error("[OMS] Submit order failed, error={}", e.getErrorMessage(), e);
            // 🔥 异常时解冻 Ledger（如果已冻结）
            rollbackLedgerFreeze(orderId, request.getUserId(), requiredMargin);
            return SubmitOrderResponse.fail(e.getErrorCode(), e.getErrorMessage());
        } catch (Exception e) {
            log.error("[OMS] Submit order system error", e);
            rollbackLedgerFreeze(orderId, request.getUserId(), requiredMargin);
            return SubmitOrderResponse.fail(OmsErrorCode.OMS_9001.getCode(),
                OmsErrorCode.OMS_9001.getMessage());
        }
    }

    @Override
    public SubmitOrderResponse confirmSubmitByClientOrderId(Long userId, String clientOrderId) {
        return null;
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
                        order.getLeverage() != null && order.getLeverage() > 0 ? order.getLeverage() : 10
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
            
            // 5. 更新状态=PENDING_CANCEL（等待 Match Engine 确认）
            int updated = orderMapper.updateStatus(
                orderId, order.getStatus(), 7, System.currentTimeMillis(), order.getVersion());
            
            if (updated == 0) {
                throw new OmsException(OmsErrorCode.OMS_9003);
            }
            
            // 5. 记录事件
            recordEvent(orderId, request.getUserId(), order.getSymbol(), 
                "ORDER_CANCEL", "OMS", request);
            
            // 6. 记录状态变更
            recordStateLog(orderId, request.getUserId(), order.getStatus(), 7, 
                "USER_CANCEL", "Cancel request sent, awaiting match engine confirmation", request.getTraceId());
            
            // 7. 投递CancelEvent -> Match Engine ⭐
            com.exchange.oms.dto.OrderEventCommand cancelCommand = buildCancelCommand(order);
            orderEventPublisher.publishOrderEvent(cancelCommand);
            log.info("[OMS] Cancel event sent to match engine, orderId={}", orderId);
            
            // 8. 解冻资金（异步）
            // TODO: 通知账户服务解冻
            
            log.info("[OMS] Cancel order success, orderId={}", orderId);
            
            return CancelOrderResponse.success(request.getOrderId(), "PENDING_CANCEL");
            
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
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId) {
        log.info("[OMS] Internal cancel order, orderId={}", orderId);

        try {
            OmsOrder order = orderMapper.selectById(orderId);
            if (order == null) {
                log.warn("[OMS] Cancel order not found, orderId={}", orderId);
                return;
            }

            if (!order.isCancelable()) {
                log.warn("[OMS] Cancel order not cancelable, orderId={}, status={}", orderId, order.getStatus());
                return;
            }

            // 解冻保证金
            if (order.getFreezeStatus() != null && order.getFreezeStatus() == 1 && ledgerClient != null) {
                try {
                    BigDecimal unfreezeAmount = calculateRequiredMargin(
                        order.getPrice(),
                        order.getRemainingQuantity(),
                        order.getLeverage() != null && order.getLeverage() > 0 ? order.getLeverage() : 10
                    );
                    com.exchange.oms.client.LedgerClient.UnfreezeRequest unfreezeRequest =
                        new com.exchange.oms.client.LedgerClient.UnfreezeRequest();
                    unfreezeRequest.setUserId(order.getUserId());
                    unfreezeRequest.setCurrency("USDT");
                    unfreezeRequest.setAmount(unfreezeAmount);
                    unfreezeRequest.setOrderId(orderId);
                    ledgerClient.unfreezeMargin(unfreezeRequest);
                } catch (Exception e) {
                    log.error("[OMS] Unfreeze margin failed on internal cancel, orderId={}", orderId, e);
                }
            }

            // 更新状态=PENDING_CANCEL
            int updated = orderMapper.updateStatus(
                orderId, order.getStatus(), 7, System.currentTimeMillis(), order.getVersion());
            if (updated == 0) {
                log.warn("[OMS] Internal cancel order update failed (version conflict), orderId={}", orderId);
                return;
            }

            // 记录事件
            recordEvent(orderId, order.getUserId(), order.getSymbol(),
                "ORDER_CANCEL", "OMS", null);

            // 记录状态变更
            recordStateLog(orderId, order.getUserId(), order.getStatus(), 7,
                "INTERNAL_CANCEL", "Internal cancel request", null);

            // 投递CancelEvent -> Match Engine
            com.exchange.oms.dto.OrderEventCommand cancelCommand = buildCancelCommand(order);
            orderEventPublisher.publishOrderEvent(cancelCommand);
            log.info("[OMS] Internal cancel event sent, orderId={}", orderId);

        } catch (Exception e) {
            log.error("[OMS] Internal cancel order failed, orderId={}", orderId, e);
        }
    }

    @Override
    public Long createLiquidationOrder(com.exchange.common.proto.request.CreateOrderRequest request) {
        if (orderService != null) {
            return orderService.createLiquidationOrder(request);
        }
        throw new RuntimeException("OrderService not available for liquidation order");
    }

    @Override
    public Long createAdlOrder(com.exchange.common.proto.request.CreateOrderRequest request) {
        if (orderService != null) {
            return orderService.createAdlOrder(request);
        }
        throw new RuntimeException("OrderService not available for ADL order");
    }

    @Override
    public QueryOrderResponse queryOrder(QueryOrderRequest request) {
        Long orderId = Long.parseLong(request.getOrderId());
        OmsOrder order = orderMapper.selectById(orderId);
        
        if (order == null || !order.getUserId().equals(request.getUserId())) {
            return null;
        }
        
        QueryOrderResponse response = new QueryOrderResponse();
        response.setOrderId(order.getId().toString());
        response.setClientOrderId(order.getClientOrderId());
        response.setSymbol(order.getSymbol());
        response.setSide(mapOrderSide(order.getSide()));
        response.setType(mapOrderType(order.getType()));
        response.setPrice(order.getPrice() != null ? order.getPrice().toPlainString() : null);
        response.setQuantity(order.getQuantity().toPlainString());
        response.setFilledQuantity(order.getFilledQuantity().toPlainString());
        response.setStatus(mapOrderStatus(order.getStatus()));
        response.setCreateTime(order.getCreatedAt());
        
        return response;
    }

    @Override
    public OrderListResponse queryOrderList(OrderListRequest request) {
        return null;
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
            case 7: return "PENDING_CANCEL";
            default: return "UNKNOWN";
        }
    }
    
    /**
     * 计算所需保证金
     *
     * 公式：保证金 = 价格 * 数量 / 杠杆倍数
     */
    private BigDecimal calculateRequiredMargin(BigDecimal price, BigDecimal quantity, Integer leverage) {
        if (price == null || quantity == null || leverage == null || leverage <= 0) {
            throw new IllegalArgumentException("Invalid margin calculation parameters");
        }
        return price.multiply(quantity).divide(BigDecimal.valueOf(leverage), 8, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 计算订单所需保证金（long 版本，用于风控）
     */
    private long calculateRequiredMarginInLong(BigDecimal price, BigDecimal quantity, Integer leverage) {
        BigDecimal margin = calculateRequiredMargin(price, quantity,
            leverage != null && leverage > 0 ? leverage : 10);
        return margin.multiply(BigDecimal.valueOf(Money.SCALE)).longValue();
    }

    /**
     * 构建风控检查请求
     */
    private CheckOrderRiskRequest buildRiskRequest(SubmitOrderRequest request, long orderId) {
        CheckOrderRiskRequest riskRequest = new CheckOrderRiskRequest();
        riskRequest.setOrderId(String.valueOf(orderId));
        riskRequest.setUserId(request.getUserId());
        riskRequest.setSymbol(request.getSymbol());
        riskRequest.setSide(request.getSide());
        riskRequest.setPrice(request.getPrice());
        riskRequest.setQuantity(request.getQuantity());
        riskRequest.setLeverage(request.getLeverage());
        riskRequest.setReduceOnly(request.getReduceOnly());
        return riskRequest;
    }

    /**
     * 异常时回滚 Ledger 冻结
     */
    private void rollbackLedgerFreeze(Long orderId, Long userId, BigDecimal amount) {
        if (orderId == null || userId == null || ledgerClient == null) {
            return;
        }
        try {
            com.exchange.oms.client.LedgerClient.UnfreezeRequest unfreezeRequest =
                new com.exchange.oms.client.LedgerClient.UnfreezeRequest();
            unfreezeRequest.setUserId(userId);
            unfreezeRequest.setCurrency("USDT");
            unfreezeRequest.setAmount(amount);
            unfreezeRequest.setOrderId(orderId);
            ledgerClient.unfreezeMargin(unfreezeRequest);
            log.info("[OMS] ✅ Rolled back ledger freeze on failure, orderId={}", orderId);
        } catch (Exception ex) {
            log.error("[OMS] ❌ Failed to rollback ledger freeze, orderId={}", orderId, ex);
        }
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
        command.setPrice(order.getPrice() != null ? order.getPrice().toPlainString() : null);
        command.setQuantity(order.getQuantity().toPlainString());
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

