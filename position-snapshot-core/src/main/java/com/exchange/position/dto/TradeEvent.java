package com.exchange.position.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Trade Event DTO
 */
@Data
public class TradeEvent {
    
    private String tradeId;
    private String symbol;
    private Long makerUserId;
    private Long takerUserId;
    private Long makerOrderId;
    private Long takerOrderId;
    private BigDecimal price;
    private BigDecimal quantity;
    private Boolean isMakerBuy;
    private BigDecimal makerFee;
    private BigDecimal takerFee;
    private Long tradeTime;
    private Long matchSequence;
}

