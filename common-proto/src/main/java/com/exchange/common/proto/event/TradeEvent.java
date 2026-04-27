package com.exchange.common.proto.event;

import com.exchange.common.core.enums.Side;
import lombok.Data;

import java.io.Serializable;

/**
 * 成交事件
 */
@Data
public class TradeEvent implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 成交ID
     */
    private Long tradeId;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 成交价格
     */
    private Long price;
    
    /**
     * 成交数量
     */
    private Long quantity;
    
    /**
     * 买方订单ID
     */
    private Long buyOrderId;
    
    /**
     * 买方用户ID
     */
    private Long buyUserId;
    
    /**
     * 卖方订单ID
     */
    private Long sellOrderId;
    
    /**
     * 卖方用户ID
     */
    private Long sellUserId;
    
    /**
     * 主动方（maker/taker标记）
     */
    private Side takerSide;
    
    /**
     * 成交时间戳
     */
    private Long timestamp;
    
    /**
     * 序列号
     */
    private Long sequence;
}







