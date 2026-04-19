package com.exchange.oms.consumer;

import com.exchange.common.proto.event.PrivatePushEvent;
import com.exchange.oms.dto.OrderStateEventDTO;
import com.exchange.oms.entity.OmsOrder;
import com.exchange.oms.mapper.OmsOrderMapper;
import com.exchange.oms.mapper.OmsOrderStateLogMapper;
import com.exchange.oms.entity.OmsOrderStateLog;
import com.exchange.oms.publisher.OrderStatePushPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * 订单状态消费者（Match Engine → OMS）
 * 
 * 🔥 交易所级双通道架构 - 通道2：独立回传通道
 * 
 * Match Engine → Kafka: order-state-{symbol} → OMS
 * 
 * 核心职责：
 * 1. 监听所有Symbol的订单状态更新
 * 2. 更新OMS订单状态和已成交数量
 * 3. 发布到私有推送系统 (Private Push)
 * 4. 幂等处理（避免重复消费）
 * 5. 事务保证
 * 
 * 支持的订单状态：
 * - NEW: 新建
 * - PARTIALLY_FILLED: 部分成交
 * - FILLED: 完全成交
 * - CANCELED: 已撤单
 * - REJECTED: 被拒绝
 * 
 * @author Exchange Team
 * @version 2.0.0
 */
@Slf4j
@Component
public class OrderStateConsumer {
    private static final BigDecimal SCALE_BD = BigDecimal.valueOf(100_000_000L);
    private static final int DEFAULT_LEVERAGE = 10;
    
    @Autowired
    private OmsOrderMapper orderMapper;
    
    @Autowired
    private OmsOrderStateLogMapper stateLogMapper;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private OrderStatePushPublisher orderStatePushPublisher;

    @Autowired(required = false)
    private com.exchange.oms.client.LedgerClient ledgerClient;

    @Value("${execution.topic-mode:LEGACY_ONLY}")
    private String topicMode;

    @Value("${execution.topic.shared.order-state:ex.order.state.v1}")
    private String sharedOrderStateTopic;
    
    /**
     * 消费订单状态事件（来自Match Engine）
     * 
     * Topic Pattern: order-state-.*
     * 
     * 事件类型：
     * - NEW：新建订单
     * - PARTIALLY_FILLED：部分成交
     * - FILLED：完全成交
     * - CANCELED：已撤单
     * - REJECTED：被拒绝
     */
    @KafkaListener(
        topicPattern = "${oms.kafka.order-state.topic-pattern:order-state-.*|ex\\.order\\.state\\.v1}",
        groupId = "${spring.kafka.consumer.group-id:oms-order-state}",
        concurrency = "${oms.kafka.order-state.concurrency:3}"  // 可并发消费不同Symbol
    )
    @Transactional(rollbackFor = Exception.class)
    public void consumeOrderState(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
    ) {
        log.info("[OrderStateConsumer] ⬇️ Receive message, topic={}, partition={}, offset={}",
            topic, partition, offset);

        if (shouldSkipTopic(topic)) {
            log.debug("[OrderStateConsumer] Skip topic by mode, mode={}, topic={}, offset={}",
                normalizeTopicMode(), topic, offset);
            return;
        }
        
        try {
            // 解析消息
            JsonNode rootNode = objectMapper.readTree(message);
            JsonNode eventNode = unwrapEventData(rootNode);
            if (eventNode == null || !eventNode.hasNonNull("orderId") || !eventNode.hasNonNull("status")) {
                log.warn("[OrderStateConsumer] ⚠️ Missing required fields, topic={}, offset={}, payload={}",
                    topic, offset, message);
                return;
            }
            
            Long orderId = eventNode.path("orderId").asLong();
            Long userId = eventNode.has("userId") ? eventNode.get("userId").asLong() : null;
            String symbol = eventNode.has("symbol") ? eventNode.get("symbol").asText() : "";
            String status = eventNode.path("status").asText();
            
            // 解析成交相关信息
            BigDecimal filledQuantityDelta = eventNode.has("filledQuantityDelta")
                ? normalizeToScaled(eventNode.get("filledQuantityDelta").asText())
                : BigDecimal.ZERO;
            
            BigDecimal lastFilledPrice = eventNode.has("lastFilledPrice")
                ? normalizeToScaled(eventNode.get("lastFilledPrice").asText())
                : (eventNode.has("avgPrice")
                    ? normalizeToScaled(eventNode.get("avgPrice").asText())
                    : BigDecimal.ZERO);
                
            Long tradeId = eventNode.has("tradeId") ? eventNode.get("tradeId").asLong() : null;
            BigDecimal fee = eventNode.has("fee") ? new BigDecimal(eventNode.get("fee").asText()) : BigDecimal.ZERO;
            String feeAsset = eventNode.has("feeAsset") ? eventNode.get("feeAsset").asText() : "USDT";
            Long tradeTime = eventNode.has("tradeTime") ? eventNode.get("tradeTime").asLong() : System.currentTimeMillis();
            Long matchSequence = eventNode.has("matchSequence") ? eventNode.get("matchSequence").asLong() : 0L;
            
            log.info("[OrderStateConsumer] Process order state, orderId={}, userId={}, status={}, filledDelta={}",
                orderId, userId, status, filledQuantityDelta);
            
            // 查询订单
            OmsOrder order = orderMapper.selectById(orderId);
            if (order == null) {
                log.warn("[OrderStateConsumer] ⚠️ Order not found, orderId={}", orderId);
                return;
            }
            
            // 获取旧状态
            Integer oldStatus = order.getStatus();
            Integer newStatus = mapStatus(status);

            // 🔥 终态保护：已成交订单不允许被回退到非终态
            if (oldStatus != null && oldStatus == 4) { // FILLED
                log.warn("[OrderStateConsumer] Order already FILLED, skip state change, orderId={}, incomingStatus={}",
                    orderId, status);
                return;
            }
            if (oldStatus != null && (oldStatus == 5 || oldStatus == 6)) { // CANCELED / REJECTED
                log.debug("[OrderStateConsumer] Order already in final state, skip, orderId={}, oldStatus={}",
                    orderId, oldStatus);
                return;
            }

            // 计算新的已成交数量
            BigDecimal oldFilledQty = order.getFilledQuantity() != null ? 
                    order.getFilledQuantity() : BigDecimal.ZERO;
            BigDecimal orderQty = order.getQuantity() != null ? order.getQuantity() : BigDecimal.ZERO;
            BigDecimal maxAppendable = orderQty.subtract(oldFilledQty);
            if (maxAppendable.compareTo(BigDecimal.ZERO) < 0) {
                maxAppendable = BigDecimal.ZERO;
            }
            BigDecimal effectiveFilledDelta = filledQuantityDelta.min(maxAppendable);
            BigDecimal newFilledQty = oldFilledQty.add(effectiveFilledDelta);

            // 防重复累计：即使消息重复投递，也不允许超过订单总量
            if (filledQuantityDelta.compareTo(effectiveFilledDelta) > 0) {
                log.warn("[OrderStateConsumer] ⚠️ Clamp filled delta, orderId={}, rawDelta={}, effectiveDelta={}, oldFilled={}, qty={}",
                    orderId, filledQuantityDelta, effectiveFilledDelta, oldFilledQty, orderQty);
            }

            // 🔥 强制终态保护：已成交满必须设为 FILLED，无论上游发来什么状态
            // 此判断无条件执行，即使 filledDelta == 0（例如撤单时订单已完全成交）
            if (orderQty.compareTo(BigDecimal.ZERO) > 0 && newFilledQty.compareTo(orderQty) >= 0) {
                if (newStatus == null || newStatus != 4) {
                    log.info("[OrderStateConsumer] Force FILLED due to full fill, orderId={}, incomingStatus={}, filled={}/{}",
                        orderId, status, newFilledQty, orderQty);
                }
                newStatus = 4; // FILLED
            }
            
            // 更新订单
            if (newStatus != null && !newStatus.equals(oldStatus)) {
                // 有成交增量时，状态和已成交数量一起更新，避免 FILLED 但 filled_quantity 仍为0
                int updated;
                if (effectiveFilledDelta.compareTo(BigDecimal.ZERO) > 0) {
                    updated = orderMapper.updateFilledQuantity(
                        orderId,
                        effectiveFilledDelta,
                        newStatus,
                        System.currentTimeMillis(),
                        order.getVersion()
                    );
                } else {
                    updated = orderMapper.updateStatus(
                        orderId,
                        oldStatus,
                        newStatus,
                        System.currentTimeMillis(),
                        order.getVersion()
                    );
                }
                
                if (updated > 0) {
                    // 记录状态变更日志
                    recordStateLog(orderId, order.getUserId(), oldStatus, newStatus, 
                        "MATCH_ENGINE_REPORT", "Match engine reported: " + status);

                    if (effectiveFilledDelta.compareTo(BigDecimal.ZERO) > 0) {
                        releaseFrozenMarginForFill(order, effectiveFilledDelta);
                    }
                    
                    log.info("[OrderStateConsumer] ✅ Order state updated, orderId={}, {}→{}, filled={}",
                        orderId, mapStatusName(oldStatus), mapStatusName(newStatus), newFilledQty);
                    
                    String pushStatus = mapStatusName(newStatus);
                    // 🔥 发布到私有推送系统
                    publishToPrivatePush(order, pushStatus, effectiveFilledDelta, lastFilledPrice, 
                            tradeId, fee, feeAsset, tradeTime);
                    
                } else {
                    log.warn("[OrderStateConsumer] ⚠️ Order update failed (version conflict?), orderId={}", 
                        orderId);
                }
            } else if (effectiveFilledDelta.compareTo(BigDecimal.ZERO) > 0) {
                // 🔥 修复：使用乐观锁更新方法更新成交数量
                int updated = orderMapper.updateFilledQuantity(
                    orderId,
                    effectiveFilledDelta,
                    newStatus != null ? newStatus : oldStatus,
                    System.currentTimeMillis(),
                    order.getVersion()
                );
                
                if (updated == 0) {
                    log.warn("[OrderStateConsumer] ⚠️ Order filled quantity update failed (version conflict?), orderId={}", 
                        orderId);
                    return;
                }

                releaseFrozenMarginForFill(order, effectiveFilledDelta);
                
                log.info("[OrderStateConsumer] ✅ Order filled quantity updated, orderId={}, filled={}",
                    orderId, newFilledQty);
                
                String pushStatus = newStatus != null ? mapStatusName(newStatus) : status;
                // 🔥 发布到私有推送系统
                publishToPrivatePush(order, pushStatus, effectiveFilledDelta, lastFilledPrice, 
                        tradeId, fee, feeAsset, tradeTime);
            }
            
        } catch (Exception e) {
            log.error("[OrderStateConsumer] ❌ Process order state error, topic={}, offset={}",
                topic, offset, e);
            throw new RuntimeException("Process order state failed", e);
        }
    }

    private JsonNode unwrapEventData(JsonNode rootNode) {
        if (rootNode != null && rootNode.has("data") && rootNode.get("data").isObject()) {
            return rootNode.get("data");
        }
        return rootNode;
    }

    private boolean shouldSkipTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return false;
        }

        String mode = normalizeTopicMode();
        boolean sharedTopic = topic.equals(sharedOrderStateTopic);
        boolean legacyTopic = topic.startsWith("order-state-");

        if ("SHARED_ONLY".equals(mode)) {
            return legacyTopic;
        }
        if ("LEGACY_ONLY".equals(mode) || "DUAL_WRITE".equals(mode)) {
            return sharedTopic;
        }
        return false;
    }

    private String normalizeTopicMode() {
        if (topicMode == null || topicMode.isBlank()) {
            return "LEGACY_ONLY";
        }
        return topicMode.trim().toUpperCase(Locale.ROOT);
    }
    
    /**
     * 发布到私有推送系统
     */
    private void publishToPrivatePush(
            OmsOrder order,
            String status,
            BigDecimal filledQuantityDelta,
            BigDecimal lastFilledPrice,
            Long tradeId,
            BigDecimal fee,
            String feeAsset,
            Long tradeTime
    ) {
        try {
            // 构建 DTO
            OrderStateEventDTO dto = OrderStateEventDTO.builder()
                    .userId(order.getUserId())
                    .orderId(order.getId())
                    .symbol(order.getSymbol())
                    .status(status)
                    .filledQuantityDelta(filledQuantityDelta)
                    .cumulativeFilledQty(order.getFilledQuantity())
                    .cumulativeFilledAmount(order.getFilledQuantity().multiply(
                            order.getPrice() != null ? order.getPrice() : BigDecimal.ZERO))
                    .lastFilledPrice(lastFilledPrice)
                    .fee(fee)
                    .feeAsset(feeAsset)
                    .tradeTime(tradeTime)
                    .tradeId(tradeId)
                    .eventTime(System.currentTimeMillis())
                    .build();
            
            // 发布到私有推送
            orderStatePushPublisher.publishFromOrderStateEvent(dto, order);
            
        } catch (Exception e) {
            log.error("[OrderStateConsumer] Failed to publish to private push, orderId={}", 
                    order.getId(), e);
            // 不抛异常，避免影响主流程
        }
    }
    
    /**
     * 映射状态字符串到数据库状态码
     */
    private Integer mapStatus(String status) {
        switch (status) {
            case "NEW":
                return 0;
            case "PENDING_RISK":
                return 1;
            case "FROZEN":
                return 2;
            case "PARTIALLY_FILLED":
                return 3;
            case "FILLED":
                return 4;
            case "CANCELED":
                return 5;
            case "REJECTED":
                return 6;
            case "PENDING_CANCEL":
                return 7;
            default:
                log.warn("[OrderStateConsumer] Unknown status: {}", status);
                return null;
        }
    }
    
    /**
     * 映射状态码到状态名称
     */
    private String mapStatusName(Integer status) {
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
     * 记录状态变更日志
     */
    private void recordStateLog(Long orderId, Long userId, Integer fromStatus, 
                                Integer toStatus, String reasonCode, String reasonMsg) {
        OmsOrderStateLog stateLog = new OmsOrderStateLog();
        stateLog.setOrderId(orderId);
        stateLog.setUserId(userId);
        stateLog.setFromStatus(fromStatus != null ? fromStatus : -1);
        stateLog.setToStatus(toStatus);
        stateLog.setReasonCode(reasonCode);
        stateLog.setReasonMsg(reasonMsg);
        stateLog.setOperator("MATCH_ENGINE");
        stateLog.setCreatedAt(System.currentTimeMillis());
        stateLogMapper.insert(stateLog);
    }

    private void releaseFrozenMarginForFill(OmsOrder order, BigDecimal filledQuantityDelta) {
        if (ledgerClient == null) {
            return;
        }
        if (order == null || order.getPrice() == null || filledQuantityDelta == null) {
            return;
        }
        if (filledQuantityDelta.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        try {
            int leverage = resolveLeverage(order.getLeverage());
            BigDecimal unfreezeAmount = calculateRequiredMargin(order.getPrice(), filledQuantityDelta, leverage);
            if (unfreezeAmount.compareTo(BigDecimal.ZERO) <= 0) {
                return;
            }

            com.exchange.oms.client.LedgerClient.UnfreezeRequest unfreezeRequest =
                new com.exchange.oms.client.LedgerClient.UnfreezeRequest();
            unfreezeRequest.setUserId(order.getUserId());
            unfreezeRequest.setCurrency("USDT");
            unfreezeRequest.setAmount(unfreezeAmount);
            unfreezeRequest.setOrderId(order.getId());
            ledgerClient.unfreezeMargin(unfreezeRequest);

            log.info("[OrderStateConsumer] ✅ Released frozen margin on fill, orderId={}, userId={}, filledDelta={}, unfreezeAmount={}",
                order.getId(), order.getUserId(), filledQuantityDelta, unfreezeAmount);
        } catch (Exception e) {
            log.error("[OrderStateConsumer] ❌ Release frozen margin failed, orderId={}", order.getId(), e);
        }
    }

    /**
     * 计算保证金：price 和 quantity 均为 1e8 缩放后的整数格式。
     */
    private BigDecimal calculateRequiredMargin(BigDecimal price, BigDecimal quantity, int leverage) {
        if (price == null || quantity == null || leverage <= 0) {
            return BigDecimal.ZERO;
        }
        // OmsOrder 的 price/quantity 为实际金额（未缩放），直接计算即可
        return price.multiply(quantity)
            .divide(BigDecimal.valueOf(leverage), 8, RoundingMode.HALF_UP);
    }

    private int resolveLeverage(Integer leverage) {
        if (leverage == null || leverage <= 0) {
            return DEFAULT_LEVERAGE;
        }
        return leverage;
    }

    private BigDecimal normalizeToScaled(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        String valueText = raw.trim();
        BigDecimal value = new BigDecimal(valueText);
        int dotIndex = valueText.indexOf('.');
        if (dotIndex < 0) {
            return value.setScale(0, RoundingMode.HALF_UP);
        }
        String fraction = valueText.substring(dotIndex + 1);
        boolean fractionAllZero = !fraction.isEmpty() && fraction.chars().allMatch(ch -> ch == '0');
        if (fractionAllZero && value.abs().compareTo(SCALE_BD) >= 0) {
            return value.setScale(0, RoundingMode.HALF_UP);
        }
        return value.multiply(SCALE_BD).setScale(0, RoundingMode.HALF_UP);
    }
}
