package com.exchange.adl.event;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * ADL Executed Event - ADL执行完成事件
 *
 * 由ADL Service发布，供其他服务消费
 */
@Data
public class AdlExecutedEvent {

    /**
     * ADL执行批次ID
     */
    private String adlBatchId;

    /**
     * 穿仓记录ID
     */
    private String bankruptcyRecordId;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * ADL执行详情列表
     */
    private List<AdlExecutionDetail> executions;

    /**
     * ADL总数量
     */
    private BigDecimal totalQty;

    /**
     * ADL总金额
     */
    private BigDecimal totalAmount;

    /**
     * 影响用户数
     */
    private Integer affectedUsers;

    /**
     * 执行状态
     */
    private String status;

    /**
     * 执行时间
     */
    private Long executedAt;

    /**
     * 事件时间戳
     */
    private Long timestamp;

    @Data
    public static class AdlExecutionDetail {
        /**
         * ADL执行ID
         */
        private String adlExecutionId;

        /**
         * 被ADL用户ID
         */
        private Long targetUserId;

        /**
         * 被ADL持仓ID
         */
        private Long targetPositionId;

        /**
         * ADL价格
         */
        private BigDecimal adlPrice;

        /**
         * ADL数量
         */
        private BigDecimal adlQty;

        /**
         * 被ADL用户盈亏变化
         */
        private BigDecimal targetPnlChange;
    }
}
