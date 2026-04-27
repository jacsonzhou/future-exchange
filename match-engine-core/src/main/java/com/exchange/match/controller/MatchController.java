package com.exchange.match.controller;

import com.exchange.match.disruptor.DisruptorEngine;
import com.exchange.match.engine.MatchEngine;
import com.exchange.match.event.OrderCommand;
import com.exchange.match.orderbook.OrderBook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 撮合引擎对外接口Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/match")
public class MatchController {
    
    @Autowired
    private DisruptorEngine disruptorEngine;

    @Autowired
    private MatchEngine matchEngine;
    
    /**
     * 提交订单命令（测试接口）
     */
    @PostMapping("/order/submit")
    public Map<String, Object> submitOrder(@RequestBody OrderCommand command) {
        log.info("[MatchController] Submit order, orderId={}, symbol={}", 
            command.getOrderId(), command.getSymbol());
        
        command.setEventType("ORDER_SUBMIT");
        command.setEventTime(System.currentTimeMillis());
        
        disruptorEngine.submitOrderCommand(command);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("orderId", command.getOrderId());
        result.put("message", "Order submitted to matching engine");
        return result;
    }
    
    /**
     * 撤单（测试接口）
     */
    @PostMapping("/order/cancel")
    public Map<String, Object> cancelOrder(@RequestParam Long orderId, 
                                           @RequestParam String symbol) {
        log.info("[MatchController] Cancel order, orderId={}", orderId);
        
        OrderCommand command = new OrderCommand();
        command.setEventType("ORDER_CANCEL");
        command.setOrderId(orderId);
        command.setSymbol(symbol);
        command.setEventTime(System.currentTimeMillis());
        
        disruptorEngine.submitOrderCommand(command);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("orderId", orderId);
        result.put("message", "Cancel order submitted");
        return result;
    }
    
    /**
     * 获取最优买卖价
     */
    @GetMapping("/orderbook/best-price")
    public Map<String, Object> getBestPrice(@RequestParam String symbol) {
        OrderBook orderBook = matchEngine.getOrderBook(symbol);
        Map<String, Object> result = new HashMap<>();
        if (orderBook != null) {
            result.put("symbol", symbol);
            result.put("bestBid", orderBook.getBestBidPrice());
            result.put("bestAsk", orderBook.getBestAskPrice());
        } else {
            result.put("symbol", symbol);
            result.put("bestBid", null);
            result.put("bestAsk", null);
        }
        return result;
    }

    /**
     * 获取订单簿统计信息
     */
    @GetMapping("/orderbook/stats")
    public Map<String, Object> getOrderBookStats(@RequestParam String symbol) {
        OrderBook orderBook = matchEngine.getOrderBook(symbol);
        Map<String, Object> result = new HashMap<>();
        if (orderBook != null) {
            result.put("symbol", symbol);
            result.put("depth", orderBook.getDepth());
            result.put("orderCount", orderBook.getOrderCount());
            result.put("bestBid", orderBook.getBestBidPrice());
            result.put("bestAsk", orderBook.getBestAskPrice());
        } else {
            result.put("symbol", symbol);
            result.put("depth", 0);
            result.put("orderCount", 0);
            result.put("bestBid", null);
            result.put("bestAsk", null);
        }
        result.put("ringBufferRemaining", disruptorEngine.getRemainingCapacity());
        result.put("ringBufferSize", disruptorEngine.getBufferSize());
        return result;
    }
    
    /**
     * 🔥 获取订单簿深度（真实盘口数据）
     *
     * @param symbol 交易对
     * @param depth 深度（默认20）
     * @return 买卖盘深度数据
     */
    @GetMapping("/orderbook/depth/{symbol}")
    public Map<String, Object> getOrderBookDepth(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "20") int depth) {

        log.info("[MatchController] Query orderbook depth, symbol={}, depth={}", symbol, depth);

        OrderBook orderBook = matchEngine.getOrderBook(symbol);
        Map<String, Object> result = new HashMap<>();
        result.put("symbol", symbol);
        if (orderBook != null) {
            Map<String, List<List<String>>> depthData = orderBook.getDepthData(depth);
            result.put("bids", depthData.get("bids"));
            result.put("asks", depthData.get("asks"));
        } else {
            result.put("bids", List.of());
            result.put("asks", List.of());
        }
        result.put("timestamp", System.currentTimeMillis());

        return result;
    }

    /**
     * 🔥 获取用户当前挂单（用于在K线显示）
     *
     * @param symbol 交易对
     * @param userId 用户ID
     * @return 用户挂单列表
     */
    @GetMapping("/orderbook/user-orders/{symbol}")
    public Map<String, Object> getUserOrders(
            @PathVariable String symbol,
            @RequestParam Long userId) {

        log.info("[MatchController] Query user orders, symbol={}, userId={}", symbol, userId);

        OrderBook orderBook = matchEngine.getOrderBook(symbol);
        List<Map<String, Object>> userOrders = (orderBook != null)
            ? orderBook.getUserOrders(userId) : List.of();

        Map<String, Object> result = new HashMap<>();
        result.put("symbol", symbol);
        result.put("userId", userId);
        result.put("orders", userOrders);

        return result;
    }
}

