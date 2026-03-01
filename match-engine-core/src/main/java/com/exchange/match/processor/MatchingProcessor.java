package com.exchange.match.processor;

import com.exchange.match.event.MatchEvent;
import com.exchange.match.model.Order;
import com.exchange.match.model.OrderStateEvent;
import com.exchange.match.model.Trade;
import com.exchange.match.orderbook.OrderBook;
import com.exchange.match.pool.OrderPool;
import com.exchange.match.publisher.TradePublisher;
import com.exchange.match.recovery.OrderBookRecoveryManager;
import com.exchange.match.wal.MatchWAL;
import com.lmax.disruptor.EventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    private static final BigDecimal MONEY_SCALE = BigDecimal.valueOf(100_000_000L);

    @Autowired
    private OrderBook orderBook;

    @Autowired
    private TradePublisher tradePublisher;
    
    @Autowired
    private com.exchange.match.publisher.DepthPublisher depthPublisher;

    @Autowired(required = false)
    private OrderBookRecoveryManager recoveryManager;

    @Autowired(required = false)
    private MatchWAL matchWAL;

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
     * 最近处理的事件序列号（用于深度心跳快照）
     */
    private AtomicLong lastProcessedSequence = new AtomicLong(0);

    /**
     * 全局序列基线（用于进程重启后延续序列，避免恢复场景序号回退）
     */
    private final AtomicLong sequenceBase = new AtomicLong(0);
    
    /**
     * 每N个事件发送一次深度数据
     */
    @Value("${match.depth.publish-interval:10}")
    private int depthPublishInterval;

    @PostConstruct
    public void init() {
        if (recoveryManager != null) {
            OrderBookRecoveryManager.RecoveryStats stats = recoveryManager.recover(orderBook);
            log.info("[MatchingProcessor] Recovery stats: snapshotLoaded={}, replayed={}, recoveredOrders={}, durationMs={}",
                stats.isSnapshotLoaded(), stats.getReplayCommands(), stats.getRecoveredOrderCount(), stats.getDurationMs());

            long recoveredBase = stats.getSnapshotSequence() + stats.getReplayCommands();
            sequenceBase.set(recoveredBase);
            lastProcessedSequence.set(recoveredBase);
            lastDepthPublishSequence.set(recoveredBase);
            recoveryManager.markAppliedSequence(recoveredBase);
            log.info("[MatchingProcessor] Sequence base initialized, baseSeq={}", recoveredBase);
        }

        if (orderPoolEnabled) {
            this.orderPool = new OrderPool(orderPoolSize);
            log.info("[MatchingProcessor] Order pool enabled, size={}", orderPoolSize);
        } else {
            log.info("[MatchingProcessor] Order pool disabled");
        }
    }

    @PreDestroy
    public void shutdown() {
        if (recoveryManager != null) {
            recoveryManager.persistSnapshot(orderBook);
        }
    }
    
    @Override
    public void onEvent(MatchEvent event, long sequence, boolean endOfBatch) {
        try {
            if (event.getOrderCommand() == null) {
                return;
            }
            
            String eventType = event.getOrderCommand().getEventType();
            long globalSequence = sequenceBase.get() + sequence + 1;
            
            log.debug("[MatchingProcessor] Process event, type={}, seq={}, globalSeq={}, orderId={}",
                eventType, sequence, globalSequence, event.getOrderCommand().getOrderId());
            
            switch (eventType) {
                case "ORDER_SUBMIT":
                    handleSubmit(event.getOrderCommand(), globalSequence);
                    break;
                case "ORDER_CANCEL":
                    handleCancel(event.getOrderCommand(), globalSequence);
                    break;
                case "ORDER_FORCE_CANCEL":
                    handleForceCancel(event.getOrderCommand(), globalSequence);
                    break;
                default:
                    log.warn("[MatchingProcessor] Unknown event type: {}", eventType);
            }

            lastProcessedSequence.accumulateAndGet(globalSequence, Math::max);
            if (recoveryManager != null) {
                recoveryManager.markAppliedSequence(globalSequence);
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

        // 写入WAL（恢复链路的唯一事实来源）
        if (matchWAL != null) {
            matchWAL.append(command, trades, sequence);
        }

        // 发布成交事件
        for (Trade trade : trades) {
            tradePublisher.publishTrade(trade);
        }

        // 发布订单状态事件（taker + makers）
        publishOrderStates(order, trades);
        
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
            // 撤单也需要落WAL，保证恢复一致性
            if (matchWAL != null) {
                matchWAL.append(command, java.util.Collections.emptyList(), sequence);
            }

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
     * 周期性发布深度快照，确保 market-price-core 重启后也能自动恢复盘口。
     */
    @Scheduled(fixedDelayString = "${match.depth.heartbeat-interval-ms:1000}")
    public void publishDepthHeartbeat() {
        if (depthPublisher == null) {
            return;
        }
        long seq = lastProcessedSequence.get();
        depthPublisher.publishDepth(orderBook.getSymbol(), orderBook, seq);
        lastDepthPublishSequence.set(seq);
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
            order.setPrice(normalizeFromCommand(command.getPrice()));
        }
        order.setQuantity(normalizeFromCommand(command.getQuantity()));
        order.setLeverage(resolveLeverage(command.getLeverage()));

        // 初始化成交相关字段
        order.setRemainingQuantity(order.getQuantity());
        order.setFilledQuantity(BigDecimal.ZERO);

        return order;
    }

    private Integer resolveLeverage(Integer leverage) {
        if (leverage == null || leverage <= 0) {
            return 10;
        }
        return leverage;
    }

    /**
     * 兼容两种上游格式：
     * 1) 已缩放 long 字符串（如 5000000000000, 10000000）
     * 2) 未缩放十进制字符串（如 50000.00000000, 0.10000000）
     *
     * 关键修复：
     * - 对整数字符串，统一按 8 位精度缩放值处理（/1e8）
     * - 避免 10000000（0.10）被误解释为 10000000，导致成交数量和百分比异常
     */
    private BigDecimal normalizeFromCommand(String raw) {
        String valueText = raw == null ? "" : raw.trim();
        BigDecimal value = new BigDecimal(valueText);
        int dotIndex = valueText.indexOf('.');
        if (dotIndex < 0) {
            return value.divide(MONEY_SCALE, 8, RoundingMode.HALF_UP);
        }

        String fraction = valueText.substring(dotIndex + 1);
        boolean fractionAllZero = !fraction.isEmpty() && fraction.chars().allMatch(ch -> ch == '0');
        if (fractionAllZero && value.abs().compareTo(MONEY_SCALE) >= 0) {
            // 兼容异常格式：已缩放整数被序列化成 xx.0000000000000000
            return value.divide(MONEY_SCALE, 8, RoundingMode.HALF_UP);
        }

        return value;
    }
    
    /**
     * 发布订单状态
     */
    private void publishOrderStates(Order takerOrder, List<Trade> trades) {
        Map<Long, BigDecimal> filledDeltaByOrder = new HashMap<>();
        // taker 一定要发状态（即便无成交也要发 NEW）
        filledDeltaByOrder.put(takerOrder.getOrderId(), BigDecimal.ZERO);

        // 聚合本次撮合中每个订单的成交增量（maker/taker 都统计）
        for (Trade trade : trades) {
            if (trade.getTakerOrderId() != null) {
                filledDeltaByOrder.merge(trade.getTakerOrderId(), trade.getQuantity(), BigDecimal::add);
            }
            if (trade.getMakerOrderId() != null) {
                filledDeltaByOrder.merge(trade.getMakerOrderId(), trade.getQuantity(), BigDecimal::add);
            }
        }

        long eventTime = System.currentTimeMillis();
        for (Map.Entry<Long, BigDecimal> entry : filledDeltaByOrder.entrySet()) {
            Long orderId = entry.getKey();
            BigDecimal filledDelta = entry.getValue() != null ? entry.getValue() : BigDecimal.ZERO;

            OrderStateEvent stateEvent = new OrderStateEvent();
            stateEvent.setEventId(generateEventId(takerOrder.getSequence()) + "-" + orderId);
            stateEvent.setSymbol(takerOrder.getSymbol());
            stateEvent.setOrderId(orderId);
            stateEvent.setFilledQuantityDelta(filledDelta);
            stateEvent.setMatchSequence(takerOrder.getSequence());
            stateEvent.setEventTime(eventTime);

            if (orderId.equals(takerOrder.getOrderId())) {
                if (takerOrder.isFullyFilled()) {
                    stateEvent.setStatus("FILLED");
                } else if (filledDelta.compareTo(BigDecimal.ZERO) > 0) {
                    stateEvent.setStatus("PARTIALLY_FILLED");
                } else {
                    stateEvent.setStatus("NEW");
                }
            } else {
                // maker 订单：不存在于 orderBook 说明本轮已被完全吃掉
                Order makerOrder = orderBook.getOrder(orderId);
                if (filledDelta.compareTo(BigDecimal.ZERO) <= 0) {
                    stateEvent.setStatus("NEW");
                } else if (makerOrder == null) {
                    stateEvent.setStatus("FILLED");
                } else {
                    stateEvent.setStatus("PARTIALLY_FILLED");
                }
            }

            tradePublisher.publishOrderState(stateEvent);
        }
    }
    
    /**
     * 生成事件ID
     */
    private String generateEventId(long sequence) {
        return "EVT-" + System.currentTimeMillis() + "-" + sequence;
    }
}
