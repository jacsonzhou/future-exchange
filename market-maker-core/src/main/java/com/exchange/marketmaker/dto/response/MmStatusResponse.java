package com.exchange.marketmaker.dto.response;

import lombok.Data;

/**
 * 做市商状态响应
 */
@Data
public class MmStatusResponse {

    private Boolean isMarketMaker;
    private Integer level;
    private String status;
    private String makerFeeRate;
    private String takerFeeRate;
    private Integer apiLimit;

    private EvalPeriod evalPeriod;

    @Data
    public static class EvalPeriod {
        private String start;
        private String end;
    }
}
