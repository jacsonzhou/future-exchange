package com.exchange.index.dto;

import lombok.Data;

import java.util.List;

/**
 * 指数价格DTO
 */
@Data
public class IndexPriceDTO {

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 指数价格（8位精度）
     */
    private Long price;

    /**
     * 数据时间戳
     */
    private Long timestamp;

    /**
     * 指数价格ID（幂等键）
     */
    private String indexPriceId;

    /**
     * 数据来源
     */
    private String source;

    /**
     * 来源事件时间
     */
    private Long sourceEventTime;

    /**
     * 来源Topic
     */
    private String sourceTopic;

    /**
     * 来源Offset
     */
    private Long sourceOffset;

    /**
     * 最优买价
     */
    private Long bestBid;

    /**
     * 最优卖价
     */
    private Long bestAsk;

    /**
     * 最新成交价
     */
    private Long lastTradePrice;

    /**
     * 价格成分列表
     */
    private List<ComponentDTO> components;

    @Data
    public static class ComponentDTO {
        private String exchange;
        private Long price;
        private Integer weight;
        private Boolean valid;
    }
}
