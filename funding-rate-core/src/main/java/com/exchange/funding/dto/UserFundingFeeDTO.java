package com.exchange.funding.dto;

import lombok.Data;

/**
 * 用户资金费用DTO
 */
@Data
public class UserFundingFeeDTO {
    
    private String symbol;
    private Long fundingTime;
    private String side;
    private Long positionQty;
    private Long fundingRate;
    private Long fundingFee;
    private String marginMode;
}
