package com.exchange.funding.dto;

import lombok.Data;

/**
 * 预估资金费率DTO
 */
@Data
public class FundingRateEstimateDTO {
    
    private String symbol;
    private Long estimatedRate;
    private Long nextFundingTime;
    private Long markPrice;
    private Long indexPrice;
}
