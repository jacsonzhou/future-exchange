package com.exchange.match.engine;

import com.exchange.match.model.Order;
import com.exchange.match.model.Trade;
import com.exchange.match.orderbook.OrderBook;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 撮合引擎
 * 
 * 特点：
 * 1. 单线程处理（每个symbol一个引擎）
 * 2. 内存OrderBook
 * 3. 高性能撮合
 */
@Slf4j
public class MatchEngine {

    private static final BigDecimal MONEY_SCALE = BigDecimal.valueOf(100_000_000L);
    
    /**
     * 每个交易对对应一个OrderBook
     */
    private final Map<String, OrderBook> orderBooks = new ConcurrentHashMap<>();
    
    /**
     * 处理订单命令（内部事件）
     */
    public List<Trade> onOrder(com.exchange.match.event.OrderCommand command) {
        if (command == null) {
            return new ArrayList<>();
        }
        
        log.debug("Processing order command: type={}, orderId={}, symbol={}", 
            command.getEventType(), command.getOrderId(), command.getSymbol());
        
        switch (command.getEventType()) {
            case "ORDER_SUBMIT":
                return handleNewOrder(command);
            case "ORDER_CANCEL":
            case "ORDER_FORCE_CANCEL":
                return handleCancelOrder(command);
            default:
                log.warn("Unknown command type: {}", command.getEventType());
                return new ArrayList<>();
        }
    }
    
    /**
     * 处理新订单
     */
    private List<Trade> handleNewOrder(com.exchange.match.event.OrderCommand command) {
        // 获取或创建OrderBook
        OrderBook orderBook = orderBooks.computeIfAbsent(
            command.getSymbol(), 
            k -> new OrderBook(command.getSymbol())
        );
        
        // 创建订单
        Order order = new Order();
        order.setOrderId(command.getOrderId());
        order.setUserId(command.getUserId());
        order.setSymbol(command.getSymbol());
        order.setSide("BUY".equals(command.getSide()) ? 0 : 1);
        order.setType("LIMIT".equals(command.getOrderType()) ? 0 : 1);
        if (command.getPrice() != null) {
            order.setPrice(normalizeFromCommand(command.getPrice()));
        }
        order.setQuantity(normalizeFromCommand(command.getQuantity()));
        order.setFilledQuantity(BigDecimal.ZERO);
        order.setCreateTimeNano(System.nanoTime());
        
        // 撮合
        List<Trade> trades = orderBook.addOrder(order);
        
        log.info("Order matched: orderId={}, trades={}", order.getOrderId(), trades.size());
        
        return trades;
    }

    private BigDecimal normalizeFromCommand(String raw) {
        BigDecimal value = new BigDecimal(raw);
        if (raw.indexOf('.') < 0 && value.abs().compareTo(MONEY_SCALE) >= 0) {
            return value.divide(MONEY_SCALE, 8, RoundingMode.HALF_UP);
        }
        return value;
    }
    
    /**
     * 处理撤单
     */
    private List<Trade> handleCancelOrder(com.exchange.match.event.OrderCommand command) {
        OrderBook orderBook = orderBooks.get(command.getSymbol());
        if (orderBook == null) {
            log.warn("OrderBook not found: symbol={}", command.getSymbol());
            return new ArrayList<>();
        }
        
        boolean canceled = orderBook.cancelOrder(command.getOrderId());
        
        if (canceled) {
            log.info("Order canceled: orderId={}", command.getOrderId());
        } else {
            log.warn("Failed to cancel order: orderId={}", command.getOrderId());
        }
        
        return new ArrayList<>();
    }
    
    /**
     * 获取OrderBook（用于查询深度）
     */
    public OrderBook getOrderBook(String symbol) {
        return orderBooks.get(symbol);
    }
}
