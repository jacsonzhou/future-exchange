package com.exchange.match.consumer;

import com.exchange.match.disruptor.DisruptorEngine;
import com.exchange.match.event.OrderCommand;
import com.exchange.common.proto.OrderProto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 订单事件消费者（OMS → Match Engine）
 * 
 * 🔥 交易所级双通道架构 - 通道1：单向顺序日志
 * 
 * OMS → Kafka: order-event-{symbol} → Match Engine
 * 
 * 核心特性：
 * 1. 单线程消费（保证顺序）
 * 2. 每个Symbol一个消费者实例
 * 3. 消费后提交到Disruptor处理
 * 4. 可重放、可灾备
 * 
 * ============================================
 * Phase 2.2: Dual Protocol Support (JSON + Protobuf)
 * Feature Flag 控制序列化协议
 * 收益：序列化延迟从 ms 级降到 μs 级（10-50倍）
 * ============================================
 */
@Slf4j
@Component
public class OrderEventConsumer {
    
    @Autowired
    private DisruptorEngine disruptorEngine;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Value("${match.symbol:BTCUSDT}")
    private String symbol;
    
    /**
     * Phase 2.2: 序列化协议配置
     * json - 兼容模式（默认）
     * protobuf - 高性能模式
     */
    @Value("${match.serialization.protocol:json}")
    private String serializationProtocol;
    
    /**
     * 消费订单事件（来自OMS）
     * 
     * Topic: order-events 或 order-event-{symbol}
     * 特性：
     * - 单分区：保证顺序
     * - 单线程消费：concurrency = 1
     * - Key = orderId
     * 
     * 事件类型：
     * - ORDER_SUBMIT：新订单
     * - ORDER_CANCEL：撤单
     * - ORDER_FORCE_CANCEL：强制撤单
     */
    @KafkaListener(
        topics = "order-events",
        groupId = "match-engine-group",
        concurrency = "1"  // 🔥 单线程消费，保证顺序！
    )
    public void consumeOrderEvent(
            @Payload byte[] message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String key
    ) {
        log.info("[OrderEventConsumer] ⬇️ Receive order event, topic={}, partition={}, offset={}, key={}",
            topic, partition, offset, key);
        
        try {
            // Phase 2.2: 双协议支持
            OrderCommand command = deserialize(message);
            
            log.info("[MATCH-LINK] <<< Kafka received, orderId={}, type={}, symbol={}, side={}, price={}, qty={}",
                command.getOrderId(), command.getEventType(), command.getSymbol(), command.getSide(), command.getPrice(), command.getQuantity());
            
            // 提交到Disruptor处理
            // Disruptor是单线程处理，保证撮合的确定性和可重放性
            disruptorEngine.submitOrderCommand(command);
            
            log.debug("[OrderEventConsumer] ✅ Order command submitted to Disruptor, orderId={}, offset={}",
                command.getOrderId(), offset);
            
        } catch (Exception e) {
            log.error("[OrderEventConsumer] ❌ Process order event error, topic={}, offset={}",
                topic, offset, e);
            
            // 生产环境：
            // 1. 记录到死信队列
            // 2. 报警
            // 3. 跳过该消息继续处理（或者停机修复）
            throw new RuntimeException("Process order event failed", e);
        }
    }
    
    /**
     * Phase 2.2: 双协议反序列化
     * 
     * @param message Kafka 消息（byte[]）
     * @return OrderCommand 对象
     * @throws Exception 反序列化失败
     */
    private OrderCommand deserialize(byte[] message) throws Exception {
        if ("protobuf".equalsIgnoreCase(serializationProtocol)) {
            // Protobuf 反序列化（微秒级）
            return deserializeProtobuf(message);
        } else {
            // JSON 反序列化（兼容模式，默认）
            return deserializeJson(message);
        }
    }
    
    /**
     * JSON 反序列化（兼容模式）
     */
    private OrderCommand deserializeJson(byte[] message) throws Exception {
        String jsonMessage = new String(message, StandardCharsets.UTF_8);
        return objectMapper.readValue(jsonMessage, OrderCommand.class);
    }
    
    /**
     * Protobuf 反序列化（高性能）
     * Phase 2.2: 序列化延迟从 ms 级降到 μs 级
     */
    private OrderCommand deserializeProtobuf(byte[] message) throws Exception {
        OrderProto.OrderCommand proto = OrderProto.OrderCommand.parseFrom(message);
        return convertFromProto(proto);
    }
    
    /**
     * Protobuf → OrderCommand 转换
     */
    private OrderCommand convertFromProto(OrderProto.OrderCommand proto) {
        OrderCommand command = new OrderCommand();
        command.setOrderId(proto.getOrderId());
        command.setUserId(proto.getUserId());
        command.setSymbol(proto.getSymbol());
        command.setSide(proto.getSide());
        command.setOrderType(proto.getOrderType());
        command.setPrice(proto.getPrice());
        command.setQuantity(proto.getQuantity());
        command.setEventType(proto.getEventType());
        command.setTimestamp(proto.getTimestamp());
        command.setClientOrderId(proto.getClientOrderId());
        return command;
    }
    
    /**
     * 暴露symbol给SpEL表达式
     */
    public String getSymbol() {
        return symbol;
    }
}
