package com.exchange.funding.dto;

import lombok.Data;

/**
 * 资金费率DTO
 */
@Data
public class FundingRateDTO {
    
    private String symbol;
    private Long fundingTime;
    private Long fundingRate;
    private Long markPrice;
    private Long indexPrice;
    private Long premiumIndex;
}
