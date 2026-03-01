package com.exchange.markprice.event;

import lombok.Data;

/**
 * 标记价格更新事件
 */
@Data
public class MarkPriceUpdateEvent {

    /**
     * 事件类型
     */
    private String eventType = "MARK_PRICE_UPDATE";

    /**
     * 事件时间戳
     */
    private Long eventTime;

    /**
     * 事件数据
     */
    private MarkPriceData data;

    @Data
    public static class MarkPriceData {
        private String markPriceId;
        private String indexPriceId;
        private String symbol;
        private Long markPrice;
        private Long indexPrice;
        private Long fundingRate;
        private Long nextFundingTime;
        private String source;
        private Long sourceEventTime;
        private String sourceTopic;
        private Long sourceOffset;
        private Long timestamp;
    }
}
