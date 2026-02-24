package com.exchange.match.orderbook;

import com.exchange.match.model.Order;
import com.exchange.match.model.Trade;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单簿（OrderBook）
 * 
 * 🔥 核心设计（交易所级性能）：
 * 1. Price-Time Priority（价格优先，时间优先）
 * 2. Long2ObjectOpenHashMap（避免TreeMap的CPU Cache Miss）
 * 3. 手动维护bestBidPrice/bestAskPrice（O(1)访问）
 * 4. 单线程访问（无锁）
 * 5. 支持限价单/市价单
 * 
 * 为什么不用TreeMap：
 * - 红黑树：指针跳转，CPU Cache Miss
 * - Entry对象：GC压力大
 * - 无法支撑百万级QPS
 * 
 * 为什么用Long2ObjectOpenHashMap：
 * - 数组实现：Cache Friendly
 * - 无装箱：long直接作为key
 * - 低GC：无额外对象
 * - 支持百万级QPS
 */
@Slf4j
public class OrderBook {
    
    /**
     * 交易对
     */
    private final String symbol;
    
    /**
     * 买单簿（price -> PriceLevel）
     * 🔥 使用Long2ObjectOpenHashMap而非TreeMap
     */
    private final Long2ObjectOpenHashMap<PriceLevel> bidBook;
    
    /**
     * 卖单簿（price -> PriceLevel）
     * 🔥 使用Long2ObjectOpenHashMap而非TreeMap
     */
    private final Long2ObjectOpenHashMap<PriceLevel> askBook;
    
    /**
     * 🔥 手动维护最优买价（避免TreeMap.firstKey()的O(logN)）
     * 买单：bestBidPrice是最高价
     */
    private long bestBidPrice = 0L;
    
    /**
     * 🔥 手动维护最优卖价（避免TreeMap.firstKey()的O(logN)）
     * 卖单：bestAskPrice是最低价
     */
    private long bestAskPrice = Long.MAX_VALUE;
    
    /**
     * 订单映射（orderId -> Order）
     */
    private final Map<Long, Order> orderMap;
    
    /**
     * 撮合序列号
     */
    private final AtomicLong matchSequence;
    
    /**
     * 价格精度（放大倍数）
     */
    private static final long PRICE_SCALE = 100_000_000L;
    
    public OrderBook(String symbol) {
        this.symbol = symbol;
        this.bidBook = new Long2ObjectOpenHashMap<>();
        this.askBook = new Long2ObjectOpenHashMap<>();
        this.orderMap = new HashMap<>();
        this.matchSequence = new AtomicLong(0);
    }
    
    /**
     * 添加订单（并尝试撮合）
     */
    public List<Trade> addOrder(Order order) {
        log.info("[OrderBook] Add order, orderId={}, symbol={}, side={}, price={}, qty={}",
            order.getOrderId(), symbol, order.getSide(), order.getPrice(), order.getQuantity());
        
        // 设置缩放价格
        if (order.getPrice() != null) {
            order.setPriceScaled(scalePrice(order.getPrice()));
        }
        
        // 初始化剩余数量
        order.setRemainingQuantity(order.getQuantity());
        order.setFilledQuantity(BigDecimal.ZERO);
        
        List<Trade> trades = new ArrayList<>();
        
        // 尝试撮合
        if (order.isBuy()) {
            trades = matchBuyOrder(order);
        } else {
            trades = matchSellOrder(order);
        }
        
        // 如果是限价单且有剩余，加入订单簿
        if (order.isLimit() && !order.isFullyFilled()) {
            addToOrderBook(order);
        }
        
        return trades;
    }
    
    /**
     * 撤单
     * 
     * 🔥 撤单后需要更新bestBidPrice/bestAskPrice
     */
    public boolean cancelOrder(Long orderId) {
        log.info("[OrderBook] Cancel order, orderId={}", orderId);
        
        Order order = orderMap.remove(orderId);
        if (order == null) {
            log.warn("[OrderBook] Order not found for cancel, orderId={}", orderId);
            return false;
        }
        
        long priceScaled = order.getPriceScaled();
        
        // 从订单簿移除
        if (order.isBuy()) {
            PriceLevel level = bidBook.get(priceScaled);
            if (level != null) {
                level.removeOrder(orderId);
                if (level.isEmpty()) {
                    bidBook.remove(priceScaled);
                    // 🔥 如果移除的是最优价，需要更新bestBidPrice
                    if (priceScaled == bestBidPrice) {
                        updateBestBidPrice();
                    }
                }
            }
        } else {
            PriceLevel level = askBook.get(priceScaled);
            if (level != null) {
                level.removeOrder(orderId);
                if (level.isEmpty()) {
                    askBook.remove(priceScaled);
                    // 🔥 如果移除的是最优价，需要更新bestAskPrice
                    if (priceScaled == bestAskPrice) {
                        updateBestAskPrice();
                    }
                }
            }
        }
        
        return true;
    }
    
    /**
     * 撮合买单
     * 
     * 🔥 使用手动维护的bestAskPrice（O(1)访问）
     */
    private List<Trade> matchBuyOrder(Order buyOrder) {
        List<Trade> trades = new ArrayList<>();
        
        // 市价单或限价单都可以吃单
        while (!buyOrder.isFullyFilled() && bestAskPrice != Long.MAX_VALUE) {
            // 🔥 O(1)访问bestAskPrice（不是TreeMap.firstKey()）
            long currentBestAskPrice = bestAskPrice;
            
            // 限价单：检查价格
            if (buyOrder.isLimit() && currentBestAskPrice > buyOrder.getPriceScaled()) {
                break;
            }
            
            PriceLevel askLevel = askBook.get(currentBestAskPrice);
            if (askLevel == null || askLevel.isEmpty()) {
                // 这个price level已经空了，更新bestAskPrice
                askBook.remove(currentBestAskPrice);
                updateBestAskPrice();
                continue;
            }
            
            Order sellOrder = askLevel.peekOrder();
            if (sellOrder == null) {
                askBook.remove(currentBestAskPrice);
                updateBestAskPrice();
                continue;
            }
            
            // 计算成交数量
            BigDecimal tradeQty = buyOrder.getRemainingQuantity()
                .min(sellOrder.getRemainingQuantity());
            
            // 生成成交
            Trade trade = createTrade(sellOrder, buyOrder, tradeQty, sellOrder.getPrice());
            trades.add(trade);
            
            // 更新订单
            buyOrder.updateFilled(tradeQty);
            sellOrder.updateFilled(tradeQty);
            askLevel.reduceByTradeQuantity(tradeQty);
            
            // 如果卖单完全成交，从订单簿移除
            if (sellOrder.isFullyFilled()) {
                askLevel.pollOrder();
                orderMap.remove(sellOrder.getOrderId());
                
                if (askLevel.isEmpty()) {
                    askBook.remove(currentBestAskPrice);
                    // 🔥 更新bestAskPrice
                    updateBestAskPrice();
                }
            }
        }
        
        return trades;
    }
    
    /**
     * 撮合卖单
     * 
     * 🔥 使用手动维护的bestBidPrice（O(1)访问）
     */
    private List<Trade> matchSellOrder(Order sellOrder) {
        List<Trade> trades = new ArrayList<>();
        
        // 市价单或限价单都可以吃单
        while (!sellOrder.isFullyFilled() && bestBidPrice != 0L) {
            // 🔥 O(1)访问bestBidPrice（不是TreeMap.firstKey()）
            long currentBestBidPrice = bestBidPrice;
            
            // 限价单：检查价格
            if (sellOrder.isLimit() && currentBestBidPrice < sellOrder.getPriceScaled()) {
                break;
            }
            
            PriceLevel bidLevel = bidBook.get(currentBestBidPrice);
            if (bidLevel == null || bidLevel.isEmpty()) {
                // 这个price level已经空了，更新bestBidPrice
                bidBook.remove(currentBestBidPrice);
                updateBestBidPrice();
                continue;
            }
            
            Order buyOrder = bidLevel.peekOrder();
            if (buyOrder == null) {
                bidBook.remove(currentBestBidPrice);
                updateBestBidPrice();
                continue;
            }
            
            // 计算成交数量
            BigDecimal tradeQty = sellOrder.getRemainingQuantity()
                .min(buyOrder.getRemainingQuantity());
            
            // 生成成交
            Trade trade = createTrade(buyOrder, sellOrder, tradeQty, buyOrder.getPrice());
            trades.add(trade);
            
            // 更新订单
            sellOrder.updateFilled(tradeQty);
            buyOrder.updateFilled(tradeQty);
            bidLevel.reduceByTradeQuantity(tradeQty);
            
            // 如果买单完全成交，从订单簿移除
            if (buyOrder.isFullyFilled()) {
                bidLevel.pollOrder();
                orderMap.remove(buyOrder.getOrderId());
                
                if (bidLevel.isEmpty()) {
                    bidBook.remove(currentBestBidPrice);
                    // 🔥 更新bestBidPrice
                    updateBestBidPrice();
                }
            }
        }
        
        return trades;
    }
    
    /**
     * 添加到订单簿
     * 
     * 🔥 手动更新bestBidPrice/bestAskPrice
     */
    private void addToOrderBook(Order order) {
        orderMap.put(order.getOrderId(), order);
        
        long priceScaled = order.getPriceScaled();
        
        if (order.isBuy()) {
            PriceLevel level = bidBook.get(priceScaled);
            if (level == null) {
                level = new PriceLevel(priceScaled);
                bidBook.put(priceScaled, level);
            }
            level.addOrder(order);
            
            // 🔥 更新bestBidPrice（买单取最高价）
            if (priceScaled > bestBidPrice) {
                bestBidPrice = priceScaled;
            }
        } else {
            PriceLevel level = askBook.get(priceScaled);
            if (level == null) {
                level = new PriceLevel(priceScaled);
                askBook.put(priceScaled, level);
            }
            level.addOrder(order);
            
            // 🔥 更新bestAskPrice（卖单取最低价）
            if (priceScaled < bestAskPrice) {
                bestAskPrice = priceScaled;
            }
        }
    }
    
    /**
     * 创建成交记录
     */
    private Trade createTrade(Order makerOrder, Order takerOrder, 
                             BigDecimal quantity, BigDecimal price) {
        long seq = matchSequence.incrementAndGet();
        
        Trade trade = new Trade();
        trade.setTradeId(generateTradeId(seq));
        trade.setMatchSequence(seq);
        trade.setSymbol(symbol);
        trade.setMakerOrderId(makerOrder.getOrderId());
        trade.setTakerOrderId(takerOrder.getOrderId());
        trade.setMakerUserId(makerOrder.getUserId());
        trade.setTakerUserId(takerOrder.getUserId());
        trade.setPrice(price);
        trade.setQuantity(quantity);
        trade.setIsMakerBuy(makerOrder.isBuy());
        trade.setTradeTime(System.currentTimeMillis());
        
        // 计算手续费（示例：0.1%）
        BigDecimal feeRate = new BigDecimal("0.001");
        BigDecimal tradeValue = price.multiply(quantity);
        trade.setMakerFee(tradeValue.multiply(feeRate));
        trade.setTakerFee(tradeValue.multiply(feeRate));
        
        log.info("[OrderBook] Trade generated, tradeId={}, price={}, qty={}, maker={}, taker={}",
            trade.getTradeId(), price, quantity, makerOrder.getOrderId(), takerOrder.getOrderId());
        
        return trade;
    }
    
    /**
     * 获取最优买价
     * 
     * 🔥 O(1)访问（不是TreeMap.firstKey()的O(logN)）
     */
    public Long getBestBidPrice() {
        return bestBidPrice == 0L ? null : bestBidPrice;
    }
    
    /**
     * 获取最优卖价
     * 
     * 🔥 O(1)访问（不是TreeMap.firstKey()的O(logN)）
     */
    public Long getBestAskPrice() {
        return bestAskPrice == Long.MAX_VALUE ? null : bestAskPrice;
    }

    public String getSymbol() {
        return symbol;
    }
    
    /**
     * 🔥 更新最优买价（遍历HashMap找最高价）
     * 
     * 只在删除bestBidPrice对应的price level时调用
     * 频率低，性能影响小
     */
    private void updateBestBidPrice() {
        bestBidPrice = 0L;
        
        // 遍历bidBook找最高价
        for (long price : bidBook.keySet()) {
            if (price > bestBidPrice) {
                bestBidPrice = price;
            }
        }
    }
    
    /**
     * 🔥 更新最优卖价（遍历HashMap找最低价）
     * 
     * 只在删除bestAskPrice对应的price level时调用
     * 频率低，性能影响小
     */
    private void updateBestAskPrice() {
        bestAskPrice = Long.MAX_VALUE;
        
        // 遍历askBook找最低价
        for (long price : askBook.keySet()) {
            if (price < bestAskPrice) {
                bestAskPrice = price;
            }
        }
    }
    
    /**
     * 获取订单簿深度
     */
    public int getDepth() {
        return bidBook.size() + askBook.size();
    }
    
    /**
     * 获取订单数量
     */
    public int getOrderCount() {
        return orderMap.size();
    }
    
    /**
     * 🔥 获取订单簿深度数据（供前端展示）
     * 
     * @param depth 深度层级（如 20 表示买卖各20档）
     * @return 深度数据 {bids: [[price, qty], ...], asks: [[price, qty], ...]}
     */
    public Map<String, List<List<String>>> getDepthData(int depth) {
        Map<String, List<List<String>>> result = new HashMap<>();
        
        // 获取买盘（从高到低排序）
        List<List<String>> bids = new ArrayList<>();
        List<Long> bidPrices = new ArrayList<>(bidBook.keySet());
        bidPrices.sort(Comparator.reverseOrder());
        
        for (Long price : bidPrices) {
            if (bids.size() >= depth) break;
            PriceLevel level = bidBook.get(price);
            if (level != null && !level.isEmpty()) {
                BigDecimal priceValue = new BigDecimal(price).divide(new BigDecimal(PRICE_SCALE), 8, RoundingMode.HALF_UP);
                // 🔥 FIX: totalQuantity 现在是缩放后的值，需要除以 PRICE_SCALE
                BigDecimal qtyValue = new BigDecimal(level.getTotalQuantity())
                    .divide(new BigDecimal(PRICE_SCALE), 8, RoundingMode.HALF_UP);
                bids.add(Arrays.asList(
                    priceValue.toPlainString(),
                    qtyValue.toPlainString()
                ));
            }
        }
        
        // 获取卖盘（从低到高排序）
        List<List<String>> asks = new ArrayList<>();
        List<Long> askPrices = new ArrayList<>(askBook.keySet());
        askPrices.sort(Comparator.naturalOrder());
        
        for (Long price : askPrices) {
            if (asks.size() >= depth) break;
            PriceLevel level = askBook.get(price);
            if (level != null && !level.isEmpty()) {
                BigDecimal priceValue = new BigDecimal(price).divide(new BigDecimal(PRICE_SCALE), 8, RoundingMode.HALF_UP);
                // 🔥 FIX: totalQuantity 是缩放后的值，需要除以 PRICE_SCALE
                BigDecimal qtyValue = new BigDecimal(level.getTotalQuantity())
                    .divide(new BigDecimal(PRICE_SCALE), 8, RoundingMode.HALF_UP);
                asks.add(Arrays.asList(
                    priceValue.toPlainString(),
                    qtyValue.toPlainString()
                ));
            }
        }
        
        result.put("bids", bids);
        result.put("asks", asks);
        return result;
    }
    
    /**
     * 获取用户的挂单列表
     * 
     * @param userId 用户ID
     * @return 用户的挂单列表
     */
    public List<Map<String, Object>> getUserOrders(Long userId) {
        List<Map<String, Object>> userOrders = new ArrayList<>();
        
        for (Order order : orderMap.values()) {
            if (order.getUserId().equals(userId)) {
                Map<String, Object> orderInfo = new HashMap<>();
                orderInfo.put("orderId", order.getOrderId());
                orderInfo.put("symbol", symbol);
                orderInfo.put("side", order.isBuy() ? "BUY" : "SELL");
                orderInfo.put("price", order.getPrice().toPlainString());
                orderInfo.put("quantity", order.getQuantity().toPlainString());
                orderInfo.put("filledQuantity", order.getFilledQuantity().toPlainString());
                orderInfo.put("remainingQuantity", order.getRemainingQuantity().toPlainString());
                userOrders.add(orderInfo);
            }
        }
        
        return userOrders;
    }
    
    /**
     * 价格缩放
     */
    private Long scalePrice(BigDecimal price) {
        return price.multiply(new BigDecimal(PRICE_SCALE)).longValue();
    }
    
    /**
     * 生成成交ID
     */
    private String generateTradeId(long sequence) {
        return symbol + "-" + System.currentTimeMillis() + "-" + sequence;
    }

    /**
     * 导出当前未完成挂单快照（用于重启恢复）
     */
    public synchronized List<Order> snapshotOpenOrders() {
        List<Order> snapshot = new ArrayList<>(orderMap.size());
        for (Order order : orderMap.values()) {
            Order copy = new Order();
            copy.setOrderId(order.getOrderId());
            copy.setUserId(order.getUserId());
            copy.setSymbol(order.getSymbol());
            copy.setSide(order.getSide());
            copy.setType(order.getType());
            copy.setPriceScaled(order.getPriceScaled());
            copy.setPrice(order.getPrice());
            copy.setQuantity(order.getQuantity());
            copy.setRemainingQuantity(order.getRemainingQuantity());
            copy.setFilledQuantity(order.getFilledQuantity());
            copy.setSequence(order.getSequence());
            copy.setCreateTimeNano(order.getCreateTimeNano());
            snapshot.add(copy);
        }
        snapshot.sort(Comparator.comparing(Order::getCreateTimeNano, Comparator.nullsLast(Long::compareTo)));
        return snapshot;
    }

    /**
     * 使用快照恢复订单簿（会清空当前内存态）
     */
    public synchronized void restoreOpenOrders(List<Order> orders) {
        clearOrderBook();
        if (orders == null || orders.isEmpty()) {
            return;
        }
        for (Order order : orders) {
            if (order == null || order.getOrderId() == null || order.getPrice() == null
                || order.getRemainingQuantity() == null || order.getRemainingQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (order.getPriceScaled() == null) {
                order.setPriceScaled(scalePrice(order.getPrice()));
            }
            addToOrderBook(order);
        }
    }

    /**
     * 清空订单簿（恢复前使用）
     */
    public synchronized void clearOrderBook() {
        bidBook.clear();
        askBook.clear();
        orderMap.clear();
        bestBidPrice = 0L;
        bestAskPrice = Long.MAX_VALUE;
    }
}
