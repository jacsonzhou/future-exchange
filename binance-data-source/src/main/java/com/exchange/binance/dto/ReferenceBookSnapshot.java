package com.exchange.binance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CFD 参考盘口快照
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceBookSnapshot {

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 源事件时间（毫秒）
     */
    private Long eventTime;

    /**
     * 来源 topic
     */
    private String topic;

    /**
     * 来源 offset（此处使用 depthUpdate 的 u 序号）
     */
    private Long offset;

    /**
     * 最优买价
     */
    private String bestBid;

    /**
     * 最优卖价
     */
    private String bestAsk;

    /**
     * 买盘前 N 档
     */
    private List<PriceLevel> bidsTopN;

    /**
     * 卖盘前 N 档
     */
    private List<PriceLevel> asksTopN;

    /**
     * 数据源
     */
    private String source;

    /**
     * 数据陈旧度（毫秒）
     */
    private Long stalenessMs;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceLevel {
        private String price;
        private String quantity;
    }
}
