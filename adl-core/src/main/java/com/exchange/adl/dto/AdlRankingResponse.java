package com.exchange.adl.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * ADL排名查询响应
 */
@Data
public class AdlRankingResponse {

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 仓位方向
     */
    private String side;

    /**
     * 更新时间
     */
    private Long updateTime;

    /**
     * 排名列表
     */
    private List<RankingItem> rankings;

    /**
     * 当前用户排名（如果登录）
     */
    private Integer myRank;

    /**
     * 是否在ADL危险区（前20%）
     */
    private Boolean adlZone;

    /**
     * 风险等级（1-5）
     */
    private Integer riskLevel;

    @Data
    public static class RankingItem {
        /**
         * 排名
         */
        private Integer rank;

        /**
         * 用户ID（脱敏）
         */
        private String userId;

        /**
         * 持仓ID
         */
        private Long positionId;

        /**
         * 持仓数量
         */
        private BigDecimal qty;

        /**
         * 盈亏比例
         */
        private String pnlRatio;

        /**
         * 有效杠杆
         */
        private BigDecimal effectiveLeverage;

        /**
         * ADL得分
         */
        private BigDecimal adlScore;

        /**
         * 风险等级
         */
        private Integer riskLevel;
    }
}
