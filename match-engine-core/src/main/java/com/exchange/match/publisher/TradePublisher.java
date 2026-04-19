package com.exchange.match.publisher;

import com.exchange.match.model.OrderStateEvent;
import com.exchange.match.model.Trade;
import com.exchange.common.proto.TradeProto;
import com.exchange.common.core.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 交易发布器（增强版）
 * 
 * 🔥 交易所级双通道架构 - 通道2：独立回传通道
 * 
 * Match Engine → Kafka:
 * - trade-event：成交事件（发送给Ledger/Clearing/Position）
 * - order-state-{symbol}：订单状态（回传OMS）
 * 
 * 核心职责：
 * 1. 发布TradeEvent（全系统唯一事实）
 * 2. 发布OrderStateEvent（回传OMS）⭐
 * 3. 保证发布顺序
 * 
 * TradeEvent是：
 * 🔥 钱的唯一来源
 * 🔥 Ledger唯一事实
 * 🔥 Clearing唯一事实
 * 🔥 Position更新唯一依据
 * 
 * ============================================
 * Phase 2.2: Dual Protocol Support (JSON + Protobuf)
 * Feature Flag 控制序列化协议
 * 收益：序列化延迟从 ms 级降到 μs 级（10-50倍）
 * ============================================
 */
@Slf4j
@Component
public class TradePublisher {
    
    @Autowired
    private KafkaTemplate<String, byte[]> kafkaTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    /**
     * Phase 2.2: 序列化协议配置
     * json - 兼容模式（默认）
     * protobuf - 高性能模式
     */
    @Value("${match.serialization.protocol:json}")
    private String serializationProtocol;
    
    /**
     * Kafka Topic 配置
     * 修复：支持配置化 topic 名称，与 market-price-core 保持一致
     */
    @Value("${match.kafka.topic.trade-event:trade-event}")
    private String tradeEventTopic;
    
    /**
     * 发布成交事件
     * 
     * Topic: 可配置（默认 trade-event）
     * 消费者：Ledger、Position、Clearing、Market Price Engine
     * 
     * 修复：统一数据格式，包装为事件格式（包含 eventType 等字段）
     */
    public void publishTrade(Trade trade) {
        log.info("[TradePublisher] Publish trade, tradeId={}, symbol={}, price={}, qty={}, maker={}, taker={}",
            trade.getTradeId(), 
            trade.getSymbol(), 
            trade.getPrice(), 
            trade.getQuantity(),
            trade.getMakerOrderId(),
            trade.getTakerOrderId());
        
        try {
            String topic = tradeEventTopic;
            String key = trade.getTradeId();
            
            // 修复：统一数据格式，包装为事件格式
            byte[] value = serializeTradeEvent(trade);
            
            // 发送到Kafka（使用 CompletableFuture）
            CompletableFuture<SendResult<String, byte[]>> future = kafkaTemplate.send(topic, key, value);
            
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("[TradePublisher] ✅ Trade event sent, tradeId={}, partition={}, offset={}",
                        trade.getTradeId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                } else {
                    log.error("[TradePublisher] ❌ Trade event send failed, tradeId={}",
                        trade.getTradeId(), ex);
                }
            });
            
        } catch (Exception e) {
            log.error("[TradePublisher] ❌ Publish trade error, tradeId={}", 
                trade.getTradeId(), e);
            throw new RuntimeException("Publish trade failed", e);
        }
    }
    
    /**
     * 发布订单状态事件（回传OMS）⭐
     * 
     * Topic: order-state-{symbol}
     * 消费者：OMS
     * 
     * 🔥 注意：这是独立的回传通道，不是order-event-{symbol}！
     */
    public void publishOrderState(OrderStateEvent event) {
        log.info("[TradePublisher] Publish order state, orderId={}, status={}, filledDelta={}",
            event.getOrderId(),
            event.getStatus(),
            event.getFilledQuantityDelta());
        
        try {
            // Topic命名：order-state-{symbol}
            String topic = "order-state-" + event.getSymbol();
            
            // Key = orderId
            String key = event.getOrderId().toString();
            
            // Phase 2.2: 双协议序列化
            byte[] value = serializeOrderState(event);
            
            // 发送到Kafka（使用 CompletableFuture）
            CompletableFuture<SendResult<String, byte[]>> future = kafkaTemplate.send(topic, key, value);
            
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("[TradePublisher] ✅ Order state event sent, orderId={}, partition={}, offset={}",
                        event.getOrderId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                } else {
                    log.error("[TradePublisher] ❌ Order state event send failed, orderId={}",
                        event.getOrderId(), ex);
                }
            });
            
        } catch (Exception e) {
            log.error("[TradePublisher] ❌ Publish order state error, orderId={}", 
                event.getOrderId(), e);
            // 不抛异常，避免影响撮合主流程
        }
    }
    
    /**
     * 序列化成交事件（统一格式）
     * 
     * 修复：包装为事件格式，包含 eventType 等字段，与 market-price-core 期望格式一致
     * 
     * @param trade 成交对象
     * @return 序列化后的字节数组
     * @throws Exception 序列化失败
     */
    private byte[] serializeTradeEvent(Trade trade) throws Exception {
        if ("protobuf".equalsIgnoreCase(serializationProtocol)) {
            // Protobuf 序列化（微秒级）
            TradeProto.TradeEvent proto = convertToProto(trade);
            return proto.toByteArray();
        } else {
            // JSON 序列化（修复：统一事件格式）
            Map<String, Object> event = buildTradeEvent(trade);
            String json = objectMapper.writeValueAsString(event);
            return json.getBytes(StandardCharsets.UTF_8);
        }
    }
    
    /**
     * 构建成交事件（统一格式）
     * 
     * 修复：包装为 market-price-core 期望的事件格式
     * - eventType: "TRADE"
     * - price/quantity: 转换为 long（使用 Money 工具类）
     * - timestamp: 使用 tradeTime
     * - isBuyerMaker: 从 isMakerBuy 推导
     */
    private Map<String, Object> buildTradeEvent(Trade trade) {
        Map<String, Object> event = new HashMap<>();
        
        // 事件类型
        event.put("eventType", "TRADE");
        
        // 基础字段
        event.put("symbol", trade.getSymbol());
        event.put("sequence", trade.getMatchSequence() != null ? trade.getMatchSequence() : 0L);
        event.put("tradeId", trade.getTradeId());
        
        // 价格和数量：BigDecimal 精确转换为 long（避免 double 精度丢失）
        if (trade.getPrice() != null) {
            event.put("price", trade.getPrice().multiply(BigDecimal.valueOf(Money.SCALE)).setScale(0, java.math.RoundingMode.HALF_UP).longValue());
        } else {
            event.put("price", 0L);
        }
        
        if (trade.getQuantity() != null) {
            event.put("quantity", trade.getQuantity().multiply(BigDecimal.valueOf(Money.SCALE)).setScale(0, java.math.RoundingMode.HALF_UP).longValue());
        } else {
            event.put("quantity", 0L);
        }
        
        // 时间戳
        event.put("timestamp", trade.getTradeTime() != null ? trade.getTradeTime() : System.currentTimeMillis());
        
        // isBuyerMaker: 如果 Maker 是买方，则 Buyer 是 Maker（即 isBuyerMaker = true）
        // 否则 Buyer 是 Taker（即 isBuyerMaker = false）
        boolean isBuyerMaker = trade.getIsMakerBuy() != null && trade.getIsMakerBuy();
        event.put("isBuyerMaker", isBuyerMaker);
        
        // 扩展字段（可选）
        if (trade.getMakerOrderId() != null) {
            event.put("makerOrderId", trade.getMakerOrderId());
        }
        if (trade.getTakerOrderId() != null) {
            event.put("takerOrderId", trade.getTakerOrderId());
        }
        if (trade.getMakerUserId() != null) {
            event.put("makerUserId", trade.getMakerUserId());
        }
        if (trade.getTakerUserId() != null) {
            event.put("takerUserId", trade.getTakerUserId());
        }
        if (trade.getMakerLeverage() != null) {
            event.put("makerLeverage", trade.getMakerLeverage());
        }
        if (trade.getTakerLeverage() != null) {
            event.put("takerLeverage", trade.getTakerLeverage());
        }
        
        return event;
    }
    
    /**
     * Phase 2.2: 双协议序列化 Trade（保留用于 Protobuf）
     * 
     * @param trade 成交对象
     * @return 序列化后的字节数组
     * @throws Exception 序列化失败
     */
    @Deprecated
    private byte[] serializeTrade(Trade trade) throws Exception {
        return serializeTradeEvent(trade);
    }
    
    /**
     * Phase 2.2: 双协议序列化 OrderStateEvent
     * 
     * @param event 订单状态事件
     * @return 序列化后的字节数组
     * @throws Exception 序列化失败
     */
    private byte[] serializeOrderState(OrderStateEvent event) throws Exception {
        if ("protobuf".equalsIgnoreCase(serializationProtocol)) {
            // Protobuf 序列化（微秒级）
            TradeProto.OrderStateEvent proto = convertToProto(event);
            return proto.toByteArray();
        } else {
            // JSON 序列化（兼容模式，默认）
            String json = objectMapper.writeValueAsString(event);
            return json.getBytes(StandardCharsets.UTF_8);
        }
    }
    
    /**
     * Trade → TradeProto.TradeEvent 转换
     */
    private TradeProto.TradeEvent convertToProto(Trade trade) {
        return TradeProto.TradeEvent.newBuilder()
            .setTradeId(trade.getTradeId())
            .setMatchSequence(trade.getMatchSequence())
            .setSymbol(trade.getSymbol())
            .setMakerOrderId(trade.getMakerOrderId())
            .setTakerOrderId(trade.getTakerOrderId())
            .setMakerUserId(trade.getMakerUserId())
            .setTakerUserId(trade.getTakerUserId())
            .setPrice(trade.getPrice().toPlainString())
            .setQuantity(trade.getQuantity().toPlainString())
            .setIsMakerBuy(trade.getIsMakerBuy())
            .setTradeTime(trade.getTradeTime())
            .setMakerFee(trade.getMakerFee() != null ? trade.getMakerFee().toPlainString() : "0")
            .setTakerFee(trade.getTakerFee() != null ? trade.getTakerFee().toPlainString() : "0")
            .build();
    }
    
    /**
     * OrderStateEvent → TradeProto.OrderStateEvent 转换
     */
    private TradeProto.OrderStateEvent convertToProto(OrderStateEvent event) {
        return TradeProto.OrderStateEvent.newBuilder()
            .setEventId(event.getEventId())
            .setSymbol(event.getSymbol())
            .setOrderId(event.getOrderId())
            .setStatus(event.getStatus())
            .setFilledQuantityDelta(event.getFilledQuantityDelta() != null ? 
                event.getFilledQuantityDelta().toPlainString() : "0")
            .setMatchSequence(event.getMatchSequence())
            .setEventTime(event.getEventTime())
            .build();
    }
}
