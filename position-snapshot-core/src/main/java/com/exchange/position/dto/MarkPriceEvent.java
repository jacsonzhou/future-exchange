package com.exchange.position.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Mark Price Event DTO
 */
@Data
public class MarkPriceEvent {
    
    private String markPriceId;
    private String symbol;
    private BigDecimal markPrice;
    private BigDecimal indexPrice;
    private BigDecimal fundingRate;
    private Long timestamp;
}

