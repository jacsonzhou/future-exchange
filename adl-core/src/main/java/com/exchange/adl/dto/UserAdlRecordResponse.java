package com.exchange.adl.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用户ADL记录查询响应
 */
@Data
public class UserAdlRecordResponse {

    /**
     * ADL记录列表
     */
    private List<UserAdlRecordItem> items;

    /**
     * 总数
     */
    private Integer total;

    @Data
    public static class UserAdlRecordItem {
        /**
         * ADL执行ID
         */
        private String adlExecutionId;

        /**
         * 交易对
         */
        private String symbol;

        /**
         * 方向
         */
        private String side;

        /**
         * ADL价格
         */
        private BigDecimal adlPrice;

        /**
         * ADL数量
         */
        private BigDecimal adlQty;

        /**
         * 盈亏变化
         */
        private BigDecimal pnlChange;

        /**
         * 执行时间
         */
        private Long executedAt;
    }
}
