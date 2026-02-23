package com.exchange.match.event;

import lombok.Data;

/**
 * Disruptor 事件
 */
@Data
public class OrderCommandEvent {
    
    /**
     * 订单命令
     */
    private com.exchange.match.event.OrderCommand command;
    
    /**
     * 清空事件数据
     */
    public void clear() {
        this.command = null;
    }
}

