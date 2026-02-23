package com.exchange.match.processor;

import com.exchange.match.event.MatchEvent;
import com.exchange.match.model.Order;
import com.exchange.match.model.OrderStateEvent;
import com.exchange.match.model.Trade;
import com.exchange.match.orderbook.OrderBook;
import com.exchange.match.pool.OrderPool;
import com.exchange.match.publisher.TradePublisher;
import com.lmax.disruptor.EventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 撮合处理器（核心）
 *
 * 设计要点：
 * 1. 单线程处理（无锁）
 * 2. 顺序确定（可重放）
 * 3. EventHandler模式
 *
 * ============================================
 * Phase 1.2: Object Pool Integration
 * 使用 OrderPool 减少对象分配，降低 GC 压力
 * ============================================
 */
@Slf4j
@Component
public class MatchingProcessor implements EventHandler<MatchEvent> {

    @Autowired
    private OrderBook orderBook;

    @Autowired
    private TradePublisher tradePublisher;
    
    @Autowired
    private com.exchange.match.publisher.DepthPublisher depthPublisher;

    /**
     * Phase 1.2: Order 对象池
     */
    @Value("${match.optimization.order-pool-enabled:true}")
    private boolean orderPoolEnabled;

    @Value("${match.optimization.order-pool-size:10000}")
    private int orderPoolSize;

    private OrderPool orderPool;
    
    /**
     * 上次发送深度数据的序列号（用于控制频率）
     */
    private AtomicLong lastDepthPublishSequence = new AtomicLong(0);
    
    /**
     * 每N个事件发送一次深度数据
     */
    @Value("${match.depth.publish-interval:10}")
    private int depthPublishInterval;

    @PostConstruct
    public void init() {
        if (orderPoolEnabled) {
            this.orderPool = new OrderPool(orderPoolSize);
            log.info("[MatchingProcessor] Order pool enabled, size={}", orderPoolSize);
        } else {
            log.info("[MatchingProcessor] Order pool disabled");
        }
    }
    
    @Override
    public void onEvent(MatchEvent event, long sequence, boolean endOfBatch) {
        try {
            if (event.getOrderCommand() == null) {
                return;
            }
            
            String eventType = event.getOrderCommand().getEventType();
            
            log.debug("[MatchingProcessor] Process event, type={}, seq={}, orderId={}",
                eventType, sequence, event.getOrderCommand().getOrderId());
            
            switch (eventType) {
                case "ORDER_SUBMIT":
                    handleSubmit(event.getOrderCommand(), sequence);
                    break;
                case "ORDER_CANCEL":
                    handleCancel(event.getOrderCommand(), sequence);
                    break;
                case "ORDER_FORCE_CANCEL":
                    handleForceCancel(event.getOrderCommand(), sequence);
                    break;
                default:
                    log.warn("[MatchingProcessor] Unknown event type: {}", eventType);
            }
            
        } catch (Exception e) {
            log.error("[MatchingProcessor] Process event error, seq={}", sequence, e);
        }
    }
    
    /**
     * 处理订单提交
     */
    private void handleSubmit(com.exchange.match.event.OrderCommand command, long sequence) {
        // 转换为内部Order对象（使用对象池）
        Order order = convertToOrder(command);
        order.setSequence(sequence);
        order.setCreateTimeNano(System.nanoTime());

        // 添加到订单簿并撮合
        List<Trade> trades = orderBook.addOrder(order);

        // 发布成交事件
        for (Trade trade : trades) {
            tradePublisher.publishTrade(trade);
        }

        // 发布订单状态事件
        publishOrderState(order, trades);
        
        // 🔥 发布深度数据
        // 策略1：每N个事件发送一次（控制频率）
        // 策略2：订单进入OrderBook后立即发送一次（确保盘口有数据）
        if (depthPublisher != null) {
            boolean shouldPublish = false;
            
            // 如果订单进入OrderBook（未完全成交），立即发布
            if (!order.isFullyFilled()) {
                shouldPublish = true;
                log.debug("[MatchingProcessor] Order added to OrderBook, publish depth immediately, orderId={}", order.getOrderId());
            }
            // 或者达到发布间隔
            else if (sequence - lastDepthPublishSequence.get() >= depthPublishInterval) {
                shouldPublish = true;
            }
            
            if (shouldPublish) {
                depthPublisher.publishDepth(order.getSymbol(), orderBook, sequence);
                lastDepthPublishSequence.set(sequence);
            }
        }

        // Phase 1.2: 如果订单完全成交，归还对象池
        if (orderPoolEnabled && order.isFullyFilled()) {
            orderPool.release(order);
            log.debug("[MatchingProcessor] Order fully filled, returned to pool, orderId={}",
                    order.getOrderId());
        }
    }
    
    /**
     * 处理撤单
     */
    private void handleCancel(com.exchange.match.event.OrderCommand command, long sequence) {
        boolean success = orderBook.cancelOrder(command.getOrderId());

        if (success) {
            // 发布订单状态事件
            OrderStateEvent stateEvent = new OrderStateEvent();
            stateEvent.setEventId(generateEventId(sequence));
            stateEvent.setSymbol(command.getSymbol());
            stateEvent.setOrderId(command.getOrderId());
            stateEvent.setStatus("CANCELED");
            stateEvent.setMatchSequence(sequence);
            stateEvent.setEventTime(System.currentTimeMillis());

            tradePublisher.publishOrderState(stateEvent);
            
            // 🔥 发布深度数据（撤单后盘口变化）
            if (depthPublisher != null) {
                depthPublisher.publishDepth(command.getSymbol(), orderBook, sequence);
                lastDepthPublishSequence.set(sequence);
            }

            log.info("[MatchingProcessor] Order canceled, orderId={}", command.getOrderId());

            // Phase 1.2: 注意 - 取消的订单已从 OrderBook 中移除
            // 实际应用中，需要 OrderBook.cancelOrder 返回 Order 对象才能归还对象池
            // 这里暂时不处理，等待后续优化 OrderBook 接口
        } else {
            log.warn("[MatchingProcessor] Order not found for cancel, orderId={}",
                command.getOrderId());
        }
    }
    
    /**
     * 处理强制撤单
     */
    private void handleForceCancel(com.exchange.match.event.OrderCommand command, long sequence) {
        // 强制撤单逻辑与普通撤单类似，但可能有额外的审计要求
        handleCancel(command, sequence);
    }
    
    /**
     * 转换为内部Order对象
     * Phase 1.2: 使用对象池减少 GC 压力
     */
    private Order convertToOrder(com.exchange.match.event.OrderCommand command) {
        // 从对象池获取或创建新对象
        Order order = orderPoolEnabled ? orderPool.acquire() : new Order();

        order.setOrderId(command.getOrderId());
        order.setUserId(command.getUserId());
        order.setSymbol(command.getSymbol());
        order.setSide("BUY".equals(command.getSide()) ? 0 : 1);
        order.setType("LIMIT".equals(command.getOrderType()) ? 0 : 1);

        if (command.getPrice() != null) {
            order.setPrice(new BigDecimal(command.getPrice()));
        }
        order.setQuantity(new BigDecimal(command.getQuantity()));

        // 初始化成交相关字段
        order.setRemainingQuantity(order.getQuantity());
        order.setFilledQuantity(BigDecimal.ZERO);

        return order;
    }
    
    /**
     * 发布订单状态
     */
    private void publishOrderState(Order order, List<Trade> trades) {
        OrderStateEvent stateEvent = new OrderStateEvent();
        stateEvent.setEventId(generateEventId(order.getSequence()));
        stateEvent.setSymbol(order.getSymbol());
        stateEvent.setOrderId(order.getOrderId());
        
        // 计算成交增量
        BigDecimal filledDelta = BigDecimal.ZERO;
        for (Trade trade : trades) {
            if (trade.getMakerOrderId().equals(order.getOrderId()) ||
                trade.getTakerOrderId().equals(order.getOrderId())) {
                filledDelta = filledDelta.add(trade.getQuantity());
            }
        }
        stateEvent.setFilledQuantityDelta(filledDelta);
        
        // 确定状态
        if (order.isFullyFilled()) {
            stateEvent.setStatus("FILLED");
        } else if (order.getFilledQuantity().compareTo(BigDecimal.ZERO) > 0) {
            stateEvent.setStatus("PARTIALLY_FILLED");
        } else {
            stateEvent.setStatus("NEW");
        }
        
        stateEvent.setMatchSequence(order.getSequence());
        stateEvent.setEventTime(System.currentTimeMillis());
        
        tradePublisher.publishOrderState(stateEvent);
    }
    
    /**
     * 生成事件ID
     */
    private String generateEventId(long sequence) {
        return "EVT-" + System.currentTimeMillis() + "-" + sequence;
    }
}



