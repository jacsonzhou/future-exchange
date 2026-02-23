package com.exchange.marketmaker.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 费率流水响应
 */
@Data
public class FeeLogResponse {

    private String totalRebate;  // 总返佣
    private List<FeeLogItem> logs;

    @Data
    public static class FeeLogItem {
        private Long tradeId;
        private String symbol;
        private String side;
        private String price;
        private String quantity;
        private String feeRate;
        private String feeAmount;  // 负数表示返佣
        private Long time;
    }
}
