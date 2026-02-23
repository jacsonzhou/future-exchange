package com.exchange.match.event;

import lombok.Data;

/**
 * Disruptor事件包装器
 * 
 * RingBuffer中的事件对象
 */
@Data
public class MatchEvent {
    
    /**
     * 订单命令
     */
    private com.exchange.match.event.OrderCommand orderCommand;
    
    /**
     * 序列号
     */
    private long sequence;
    
    /**
     * 清空事件（用于重用）
     */
    public void clear() {
        this.orderCommand = null;
        this.sequence = 0;
    }
}



