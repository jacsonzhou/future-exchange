package com.exchange.market.model;

import lombok.Data;

/**
 * 价格档位（侵入式链表节点）
 * 
 * 设计目标：
 * - 避免对象分配和GC压力
 * - 支持O(1)的链表操作
 * - Cache友好（字段连续存储）
 * 
 * 内存布局：
 * - price: 8 bytes
 * - quantity: 8 bytes  
 * - orderCount: 4 bytes
 * - updateTime: 8 bytes
 * - prev/next引用: 8/8 bytes (64位JVM)
 * 总计: ~44 bytes/节点
 */
@Data
public class PriceLevel {
    
    /**
     * 价格（long存储，精度由外部管理）
     */
    private long price;
    
    /**
     * 总数量
     */
    private long quantity;
    
    /**
     * 订单数量（L3深度需要）
     */
    private int orderCount;
    
    /**
     * 最后更新时间
     */
    private long updateTime;
    
    /**
     * 前驱节点（侵入式链表）
     */
    private PriceLevel prev;
    
    /**
     * 后继节点（侵入式链表）
     */
    private PriceLevel next;

    public PriceLevel(long price, long quantity) {
        this.price = price;
        this.quantity = quantity;
        this.orderCount = 1;
        this.updateTime = System.currentTimeMillis();
    }
    
    /**
     * 增加数量
     */
    public void addQuantity(long delta) {
        this.quantity += delta;
        this.updateTime = System.currentTimeMillis();
    }
    
    /**
     * 减少数量
     */
    public void subtractQuantity(long delta) {
        this.quantity = Math.max(0, this.quantity - delta);
        this.updateTime = System.currentTimeMillis();
    }
    
    /**
     * 清空链表引用（帮助GC）
     */
    public void clearLinks() {
        this.prev = null;
        this.next = null;
    }
    
    /**
     * 是否为空档位（quantity=0）
     */
    public boolean isEmpty() {
        return quantity <= 0;
    }
    
    @Override
    public String toString() {
        return String.format("PriceLevel[price=%d, qty=%d, orders=%d]", 
                price, quantity, orderCount);
    }
}
