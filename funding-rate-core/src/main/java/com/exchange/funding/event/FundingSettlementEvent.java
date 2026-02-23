package com.exchange.funding.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 资金费用结算完成事件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FundingSettlementEvent {

    private String eventType = "FUNDING_FEE_SETTLED";
    private Long eventTime;
    private SettlementData data;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SettlementData {
        private String symbol;
        private Long fundingTime;
        private Integer totalUsers;            // 参与结算的总用户数
        private Long totalSettlementAmount;    // 结算总金额（绝对值）
        private Integer longPayersCount;       // 支付方（多头）用户数
        private Integer shortPayersCount;      // 收取方（空头）用户数
        private Long settlementDuration;       // 结算耗时（毫秒）
    }
}
