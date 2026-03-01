package com.exchange.match.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 成交事件
 * 
 * 🔥 钱的唯一来源
 * 🔥 Ledger 唯一事实
 * 🔥 Clearing 唯一事实
 * 🔥 Position 更新唯一依据
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Trade {
    
    /**
     * 成交ID
     */
    private String tradeId;
    
    /**
     * 撮合序列号
     */
    private Long matchSequence;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * Maker订单ID
     */
    private Long makerOrderId;
    
    /**
     * Taker订单ID
     */
    private Long takerOrderId;
    
    /**
     * Maker用户ID
     */
    private Long makerUserId;
    
    /**
     * Taker用户ID
     */
    private Long takerUserId;
    
    /**
     * 成交价格
     */
    private BigDecimal price;
    
    /**
     * 成交数量
     */
    private BigDecimal quantity;
    
    /**
     * Maker手续费
     */
    private BigDecimal makerFee;
    
    /**
     * Taker手续费
     */
    private BigDecimal takerFee;
    
    /**
     * Maker是否买方
     */
    private Boolean isMakerBuy;

    /**
     * Maker 杠杆倍数
     */
    private Integer makerLeverage;

    /**
     * Taker 杠杆倍数
     */
    private Integer takerLeverage;
    
    /**
     * 成交时间
     */
    private Long tradeTime;
    
    /**
     * 扩展字段
     */
    private String ext;
}
