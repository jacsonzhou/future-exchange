package com.exchange.match.orderbook;

import com.exchange.match.model.Order;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

/**
 * 价格档位（Price Level）
 *
 * 设计要点：
 * 1. FIFO队列（时间优先）
 * 2. 维护订单ID队列
 * 3. 快速插入/删除
 *
 * ============================================
 * Phase 1.1: Intrusive Linked List Optimization
 * 从 LinkedList<Order> 改为手动双向链表
 * 消除 LinkedList.Node 对象分配，减少 GC 压力 30-50%
 * 内存节约：24 字节/订单
 * ============================================
 */
@Slf4j
public class PriceLevel {

    /**
     * 价格（已缩放）
     */
    private final Long priceScaled;

    /**
     * 链表头节点
     */
    private Order head;

    /**
     * 链表尾节点
     */
    private Order tail;

    /**
     * 订单数量
     */
    private int orderCount;

    /**
     * 总数量
     */
    private long totalQuantity;
    
    /**
     * 价格精度（与 OrderBook 保持一致）
     */
    private static final long PRICE_SCALE = 100_000_000L;

    public PriceLevel(Long priceScaled) {
        this.priceScaled = priceScaled;
        this.head = null;
        this.tail = null;
        this.orderCount = 0;
        this.totalQuantity = 0;
    }
    
    /**
     * 添加订单到队尾 - O(1)
     * 使用嵌入式链表，避免 Node 对象分配
     */
    public void addOrder(Order order) {
        // 清空链表指针，防止脏数据
        order.clearListPointers();

        if (tail == null) {
            // 空队列，设置头尾
            head = tail = order;
        } else {
            // 追加到尾部
            tail.setNext(order);
            order.setPrev(tail);
            tail = order;
        }

        orderCount++;
        // 🔥 FIX: 数量需要乘以 PRICE_SCALE，避免 0.1 -> 0 的截断
        long qtyScaled = order.getRemainingQuantity()
            .multiply(new java.math.BigDecimal(PRICE_SCALE))
            .longValue();
        totalQuantity += qtyScaled;
    }

    /**
     * 获取队首订单 - O(1)
     */
    public Order peekOrder() {
        return head;
    }

    /**
     * 移除队首订单 - O(1)
     */
    public Order pollOrder() {
        if (head == null) {
            return null;
        }

        Order order = head;
        head = head.getNext();

        if (head == null) {
            // 队列变空
            tail = null;
        } else {
            head.setPrev(null);
        }

        // 清空被移除订单的链表指针
        order.clearListPointers();
        orderCount--;
        // 🔥 FIX: 数量需要乘以 PRICE_SCALE，避免 0.1 -> 0 的截断
        long qtyScaled = order.getRemainingQuantity()
            .multiply(new java.math.BigDecimal(PRICE_SCALE))
            .longValue();
        totalQuantity -= qtyScaled;

        return order;
    }

    /**
     * 移除指定订单 - O(n)
     * 注意：此操作仍需遍历，但不产生额外对象分配
     */
    public boolean removeOrder(Long orderId) {
        Order current = head;

        while (current != null) {
            if (current.getOrderId().equals(orderId)) {
                // 找到目标订单，执行移除
                if (current.getPrev() != null) {
                    current.getPrev().setNext(current.getNext());
                } else {
                    // 移除的是头节点
                    head = current.getNext();
                }

                if (current.getNext() != null) {
                    current.getNext().setPrev(current.getPrev());
                } else {
                    // 移除的是尾节点
                    tail = current.getPrev();
                }

                // 清空链表指针
                current.clearListPointers();
                orderCount--;
                // 🔥 FIX: 数量需要乘以 PRICE_SCALE，避免 0.1 -> 0 的截断
                long qtyScaled = current.getRemainingQuantity()
                    .multiply(new java.math.BigDecimal(PRICE_SCALE))
                    .longValue();
                totalQuantity -= qtyScaled;

                return true;
            }
            current = current.getNext();
        }

        return false;
    }

    /**
     * 是否为空
     */
    public boolean isEmpty() {
        return head == null;
    }

    /**
     * 获取订单数量
     */
    public int size() {
        return orderCount;
    }
    
    public Long getPriceScaled() {
        return priceScaled;
    }
    
    public long getTotalQuantity() {
        return totalQuantity;
    }

    /**
     * 按成交量扣减档位总量（部分成交场景）
     */
    public void reduceByTradeQuantity(BigDecimal tradeQuantity) {
        if (tradeQuantity == null || tradeQuantity.signum() <= 0) {
            return;
        }
        long delta = tradeQuantity
            .multiply(BigDecimal.valueOf(PRICE_SCALE))
            .longValue();
        totalQuantity -= delta;
        if (totalQuantity < 0) {
            log.warn("[PriceLevel] totalQuantity below zero, priceScaled={}, totalQuantity={}, delta={}",
                priceScaled, totalQuantity, delta);
            totalQuantity = 0;
        }
    }

    /**
     * ============================================
     * Phase 1.3: Object Pool Support
     * 清空链表，准备复用（对象池归还时调用）
     * ============================================
     */
    public void reset() {
        this.head = null;
        this.tail = null;
        this.orderCount = 0;
        this.totalQuantity = 0;
    }
}


