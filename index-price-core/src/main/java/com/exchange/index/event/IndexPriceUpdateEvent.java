package com.exchange.index.event;

import lombok.Data;

import java.util.List;

/**
 * 指数价格更新事件
 */
@Data
public class IndexPriceUpdateEvent {

    /**
     * 事件类型
     */
    private String eventType = "INDEX_PRICE_UPDATE";

    /**
     * 事件时间戳
     */
    private Long eventTime;

    /**
     * 事件数据
     */
    private IndexPriceData data;

    @Data
    public static class IndexPriceData {
        private String symbol;
        private Long price;
        private Long timestamp;
        private List<ComponentData> components;
    }

    @Data
    public static class ComponentData {
        private String exchange;
        private Long price;
        private Integer weight;
    }
}
