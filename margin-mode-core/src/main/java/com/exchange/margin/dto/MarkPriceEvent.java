package com.exchange.margin.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 标记价格变动事件
 *
 * 从 MarkPrice Service 的 mark-price-update（兼容旧mark-price-topic）消费
 */
@Data
public class MarkPriceEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 标记价格（精度8位：1 USDT = 10^8）
     */
    private Long markPrice;

    /**
     * 指数价格（精度8位：1 USDT = 10^8）
     */
    private Long indexPrice;

    /**
     * 最新成交价（精度8位：1 USDT = 10^8）
     */
    private Long lastPrice;

    /**
     * 资金费率（万分比：10000 = 100%）
     */
    private Long fundingRate;

    /**
     * 下次资金费率结算时间
     */
    private Long nextFundingTime;

    /**
     * 事件时间戳
     */
    private Long timestamp;

    /**
     * 事件序列号（用于幂等性检查）
     */
    private Long sequence;

    /**
     * 标记价事件ID（新链路）
     */
    private String markPriceId;

    /**
     * 对应指数价事件ID（新链路）
     */
    private String indexPriceId;

    /**
     * 源Topic（追踪）
     */
    private String sourceTopic;

    /**
     * 源offset（追踪）
     */
    private Long sourceOffset;
}
