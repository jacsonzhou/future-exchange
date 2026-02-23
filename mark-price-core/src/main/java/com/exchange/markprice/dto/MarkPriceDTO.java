package com.exchange.markprice.dto;

import lombok.Data;

/**
 * 标记价格DTO
 */
@Data
public class MarkPriceDTO {

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 标记价格（8位精度）
     */
    private Long markPrice;

    /**
     * 指数价格（8位精度）
     */
    private Long indexPrice;

    /**
     * 资金费率（8位精度，如0.0001表示0.01%）
     */
    private Long fundingRate;

    /**
     * 下次结算时间
     */
    private Long nextFundingTime;

    /**
     * 数据时间戳
     */
    private Long timestamp;
}
