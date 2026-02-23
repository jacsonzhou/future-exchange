package com.exchange.match.model;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单实体（撮合引擎内部）
 * 
 * 设计要点：
 * 1. 轻量级设计（避免GC）
 * 2. 仅包含撮合必需字段
 * 3. 不依赖ORM
 */
@Data
public class Order {
    
    /**
     * 订单ID
     */
    private Long orderId;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 买卖方向 0=BUY 1=SELL
     */
    private Integer side;
    
    /**
     * 订单类型 0=LIMIT 1=MARKET
     */
    private Integer type;
    
    /**
     * 价格（已乘以priceScale）
     */
    private Long priceScaled;
    
    /**
     * 原始价格
     */
    private BigDecimal price;
    
    /**
     * 数量
     */
    private BigDecimal quantity;
    
    /**
     * 剩余数量
     */
    private BigDecimal remainingQuantity;
    
    /**
     * 已成交数量
     */
    private BigDecimal filledQuantity;
    
    /**
     * 序列号（用于Replay）
     */
    private Long sequence;
    
    /**
     * 创建时间（纳秒）
     */
    private Long createTimeNano;

    /**
     * ============================================
     * Phase 1.1: Intrusive Linked List Pointers
     * 嵌入式链表指针（用于 PriceLevel 队列）
     * 消除 LinkedList.Node 对象分配，减少 GC 压力
     * ============================================
     */

    /**
     * 前驱节点（链表）
     */
    private Order prev;

    /**
     * 后继节点（链表）
     */
    private Order next;

    /**
     * 清空链表指针（对象复用时调用）
     */
    public void clearListPointers() {
        this.prev = null;
        this.next = null;
    }

    /**
     * 是否买单
     */
    public boolean isBuy() {
        return side != null && side == 0;
    }
    
    /**
     * 是否卖单
     */
    public boolean isSell() {
        return side != null && side == 1;
    }
    
    /**
     * 是否限价单
     */
    public boolean isLimit() {
        return type != null && type == 0;
    }
    
    /**
     * 是否市价单
     */
    public boolean isMarket() {
        return type != null && type == 1;
    }
    
    /**
     * 是否完全成交
     */
    public boolean isFullyFilled() {
        return remainingQuantity.compareTo(BigDecimal.ZERO) <= 0;
    }
    
    /**
     * 更新成交
     */
    public void updateFilled(BigDecimal filledQty) {
        this.filledQuantity = this.filledQuantity.add(filledQty);
        this.remainingQuantity = this.remainingQuantity.subtract(filledQty);
    }

    /**
     * ============================================
     * Phase 1.2: Object Pool Support
     * 清空所有字段，准备复用（对象池归还时调用）
     * ============================================
     */
    public void clear() {
        this.orderId = null;
        this.userId = null;
        this.symbol = null;
        this.side = null;
        this.type = null;
        this.priceScaled = null;
        this.price = null;
        this.quantity = null;
        this.remainingQuantity = null;
        this.filledQuantity = null;
        this.sequence = null;
        this.createTimeNano = null;
        this.clearListPointers();
    }
}

