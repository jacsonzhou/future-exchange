package com.exchange.funding.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户资金费用结算事件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserFundingFeeEvent {

    private String eventType = "USER_FUNDING_FEE";
    private Long eventTime;
    private UserFundingFeeData data;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserFundingFeeData {
        private Long userId;
        private String symbol;
        private Long fundingTime;
        private String side;           // LONG / SHORT
        private Long positionQty;      // 持仓数量
        private Long fundingRate;      // 资金费率
        private Long fundingFee;       // 资金费用（正数=支付，负数=收取）
        private String marginMode;     // ISOLATED / CROSS
    }
}
