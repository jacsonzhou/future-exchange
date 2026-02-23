package com.exchange.funding.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 资金费率计算完成事件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FundingRateCalcEvent {

    private String eventType = "FUNDING_RATE_CALCULATED";
    private Long eventTime;
    private FundingRateData data;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FundingRateData {
        private String symbol;
        private Long fundingTime;
        private Long fundingRate;     // 资金费率（精度：10000 = 1%）
        private Long markPrice;       // 标记价格
        private Long indexPrice;      // 指数价格
        private Long premiumIndex;    // 溢价指数
        private Long totalLongQty;    // 多头总持仓量
        private Long totalShortQty;   // 空头总持仓量
    }
}
