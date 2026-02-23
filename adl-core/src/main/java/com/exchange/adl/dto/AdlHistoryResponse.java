package com.exchange.adl.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * ADL历史查询响应
 */
@Data
public class AdlHistoryResponse {

    /**
     * ADL记录列表
     */
    private List<AdlHistoryItem> items;

    /**
     * 总数
     */
    private Integer total;

    @Data
    public static class AdlHistoryItem {
        /**
         * ADL执行ID
         */
        private String adlExecutionId;

        /**
         * 交易对
         */
        private String symbol;

        /**
         * ADL价格
         */
        private BigDecimal adlPrice;

        /**
         * ADL数量
         */
        private BigDecimal adlQty;

        /**
         * 影响用户数
         */
        private Integer affectedUsers;

        /**
         * 执行时间
         */
        private Long executedAt;
    }
}
