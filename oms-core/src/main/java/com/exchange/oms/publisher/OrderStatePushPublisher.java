package com.exchange.oms.publisher;

import com.exchange.common.core.IdGenerator;
import com.exchange.common.core.Money;
import com.exchange.common.proto.event.PrivatePushEvent;
import com.exchange.oms.dto.OrderStateEventDTO;
import com.exchange.oms.entity.OmsOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单状态推送发布器 (OMS → Private Push Service)
 * 
 * 🔥 核心职责：将订单状态变更推送到用户的私有频道
 * 
 * 发布的事件类型:
 * 1. EXECUTION_REPORT - 订单执行报告
 *    - NEW: 订单新建
 *    - PARTIALLY_FILLED: 部分成交
 *    - FILLED: 完全成交
 *    - CANCELED: 撤单
 *    - REJECTED: 拒绝
 * 
 * 2. 支持 ACK 机制 (seq序列号)
 * 3. 按 userId 分区，保证单个用户有序
 * 
 * Kafka Topic: private-order-state
 * 分区策略: userId % partitionCount
 * 
 * @author Exchange Team
 * @version 1.0.0
 */
@Slf4j
@Component
public class OrderStatePushPublisher {
    
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    /**
     * 订单状态 Topic
     */
    @Value("${oms.kafka.topic.private-order-state:private-order-state}")
    private String privateOrderStateTopic;
    
    /**
     * 序列号生成器 (按用户)
     * 实际生产环境应使用 Redis 分布式序列号
     */
    private final AtomicLong seqGenerator = new AtomicLong(0);
    
    // ==================== 核心发布方法 ====================
    
    /**
     * 发布订单执行报告 (核心方法)
     * 
     * @param order 订单实体
     * @param orderStatus 订单状态
     * @param executionType 执行类型
     * @param lastFilledQty 本次成交数量
     * @param lastFilledPrice 本次成交价格
     * @param tradeId 成交ID
     * @param fee 手续费
     */
    public void publishExecutionReport(
            OmsOrder order,
            String orderStatus,
            String executionType,
            BigDecimal lastFilledQty,
            BigDecimal lastFilledPrice,
            Long tradeId,
            BigDecimal fee
    ) {
        try {
            // 生成序列号
            Long seq = seqGenerator.incrementAndGet();
            
            // 计算累计成交金额
            BigDecimal filledQty = order.getFilledQuantity() != null ? 
                    order.getFilledQuantity() : BigDecimal.ZERO;
            BigDecimal filledAmount = filledQty.multiply(
                    order.getPrice() != null ? order.getPrice() : BigDecimal.ZERO
            );
            
            // 构建执行报告事件
            PrivatePushEvent event = PrivatePushEvent.buildExecutionReport(
                    order.getUserId(),
                    seq,
                    order.getSymbol(),
                    order.getId(),
                    order.getClientOrderId(),
                    convertSide(order.getSide()),
                    convertOrderType(order.getType()),
                    "GTC", // 默认GTC，实际应从订单获取
                    order.getQuantity(),
                    order.getPrice(),
                    orderStatus,
                    executionType,
                    filledQty,
                    filledAmount,
                    lastFilledQty,
                    lastFilledPrice,
                    fee,
                    "USDT", // 默认USDT，实际应根据配置
                    System.currentTimeMillis(),
                    tradeId
            );
            
            // 发送到 Kafka
            sendToKafka(order.getUserId(), event);
            
            log.info("[OrderStatePush] Published execution report, orderId={}, userId={}, status={}, seq={}",
                    order.getId(), order.getUserId(), orderStatus, seq);
                    
        } catch (Exception e) {
            log.error("[OrderStatePush] Failed to publish execution report, orderId={}", 
                    order.getId(), e);
            // 不抛异常，避免影响主流程
        }
    }
    
    /**
     * 发布新建订单事件
     */
    public void publishNewOrder(OmsOrder order) {
        publishExecutionReport(
                order,
                PrivatePushEvent.OrderStatus.NEW,
                PrivatePushEvent.ExecutionType.NEW,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                BigDecimal.ZERO
        );
    }
    
    /**
     * 发布部分成交事件
     */
    public void publishPartiallyFilled(
            OmsOrder order,
            BigDecimal filledQty,
            BigDecimal filledPrice,
            Long tradeId,
            BigDecimal fee
    ) {
        publishExecutionReport(
                order,
                PrivatePushEvent.OrderStatus.PARTIALLY_FILLED,
                PrivatePushEvent.ExecutionType.TRADE,
                filledQty,
                filledPrice,
                tradeId,
                fee
        );
    }
    
    /**
     * 发布完全成交事件
     */
    public void publishFilled(
            OmsOrder order,
            BigDecimal lastFilledQty,
            BigDecimal lastFilledPrice,
            Long tradeId,
            BigDecimal fee
    ) {
        publishExecutionReport(
                order,
                PrivatePushEvent.OrderStatus.FILLED,
                PrivatePushEvent.ExecutionType.TRADE,
                lastFilledQty,
                lastFilledPrice,
                tradeId,
                fee
        );
    }
    
    /**
     * 发布撤单事件
     */
    public void publishCanceled(OmsOrder order) {
        publishExecutionReport(
                order,
                PrivatePushEvent.OrderStatus.CANCELED,
                PrivatePushEvent.ExecutionType.CANCELED,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                BigDecimal.ZERO
        );
    }
    
    /**
     * 发布拒绝事件
     */
    public void publishRejected(OmsOrder order, String reason) {
        try {
            Long seq = seqGenerator.incrementAndGet();
            
            PrivatePushEvent event = PrivatePushEvent.buildExecutionReport(
                    order.getUserId(),
                    seq,
                    order.getSymbol(),
                    order.getId(),
                    order.getClientOrderId(),
                    convertSide(order.getSide()),
                    convertOrderType(order.getType()),
                    "GTC",
                    order.getQuantity(),
                    order.getPrice(),
                    PrivatePushEvent.OrderStatus.REJECTED,
                    PrivatePushEvent.ExecutionType.NEW,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "USDT",
                    System.currentTimeMillis(),
                    null
            );
            
            // 设置错误信息
            event.setErrorCode(4001);
            event.setErrorMsg(reason);
            
            sendToKafka(order.getUserId(), event);
            
            log.info("[OrderStatePush] Published rejected order, orderId={}, userId={}, reason={}",
                    order.getId(), order.getUserId(), reason);
                    
        } catch (Exception e) {
            log.error("[OrderStatePush] Failed to publish rejected order, orderId={}", 
                    order.getId(), e);
        }
    }
    
    /**
     * 从 DTO 发布执行报告 (供 OrderStateConsumer 使用)
     */
    public void publishFromOrderStateEvent(OrderStateEventDTO dto, OmsOrder order) {
        try {
            // 根据状态判断事件类型
            String orderStatus = dto.getStatus();
            String executionType = mapToExecutionType(orderStatus);
            
            // 计算本次成交数量
            BigDecimal lastFilledQty = dto.getFilledQuantityDelta() != null ? 
                    dto.getFilledQuantityDelta() : BigDecimal.ZERO;
            
            // 构建事件
            Long seq = seqGenerator.incrementAndGet();
            
            PrivatePushEvent event = PrivatePushEvent.buildExecutionReport(
                    dto.getUserId(),
                    seq,
                    dto.getSymbol(),
                    dto.getOrderId(),
                    order != null ? order.getClientOrderId() : null,
                    order != null ? convertSide(order.getSide()) : "BUY",
                    order != null ? convertOrderType(order.getType()) : "LIMIT",
                    "GTC",
                    order != null ? order.getQuantity() : BigDecimal.ZERO,
                    order != null ? order.getPrice() : BigDecimal.ZERO,
                    orderStatus,
                    executionType,
                    dto.getCumulativeFilledQty(),
                    dto.getCumulativeFilledAmount(),
                    lastFilledQty,
                    dto.getLastFilledPrice(),
                    dto.getFee(),
                    dto.getFeeAsset(),
                    dto.getTradeTime(),
                    dto.getTradeId()
            );
            
            sendToKafka(dto.getUserId(), event);
            
            log.info("[OrderStatePush] Published from DTO, orderId={}, status={}, seq={}",
                    dto.getOrderId(), orderStatus, seq);
                    
        } catch (Exception e) {
            log.error("[OrderStatePush] Failed to publish from DTO, orderId={}", 
                    dto.getOrderId(), e);
        }
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 发送事件到 Kafka
     * 
     * 分区策略: userId % partitionCount
     * Key: userId (保证同一用户有序)
     */
    private void sendToKafka(Long userId, PrivatePushEvent event) throws Exception {
        String key = userId.toString();
        String value = objectMapper.writeValueAsString(event);
        
        CompletableFuture<SendResult<String, String>> future = 
                kafkaTemplate.send(privateOrderStateTopic, key, value).toCompletableFuture();
        
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.debug("[OrderStatePush] Kafka send success, topic={}, partition={}, offset={}, userId={}",
                        privateOrderStateTopic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        userId);
            } else {
                log.error("[OrderStatePush] Kafka send failed, userId={}, error={}",
                        userId, ex.getMessage());
            }
        });
    }
    
    /**
     * 转换 side (Integer -> String)
     * 0=BUY, 1=SELL
     */
    private String convertSide(Integer side) {
        if (side == null) return "BUY";
        return side == 0 ? "BUY" : "SELL";
    }
    
    /**
     * 转换 order type (Integer -> String)
     * 0=LIMIT, 1=MARKET
     */
    private String convertOrderType(Integer type) {
        if (type == null) return "LIMIT";
        return type == 0 ? "LIMIT" : "MARKET";
    }
    
    /**
     * 映射订单状态到执行类型
     */
    private String mapToExecutionType(String orderStatus) {
        switch (orderStatus) {
            case "NEW":
                return PrivatePushEvent.ExecutionType.NEW;
            case "PARTIALLY_FILLED":
            case "FILLED":
                return PrivatePushEvent.ExecutionType.TRADE;
            case "CANCELED":
                return PrivatePushEvent.ExecutionType.CANCELED;
            case "REJECTED":
            case "EXPIRED":
                return PrivatePushEvent.ExecutionType.EXPIRED;
            default:
                return PrivatePushEvent.ExecutionType.NEW;
        }
    }
}
