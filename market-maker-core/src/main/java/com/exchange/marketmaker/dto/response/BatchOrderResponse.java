package com.exchange.marketmaker.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 批量下单响应
 */
@Data
public class BatchOrderResponse {

    private String batchId;
    private Integer totalCount;
    private Integer successCount;
    private Integer failCount;
    private List<OrderResult> results;

    @Data
    public static class OrderResult {
        private Integer index;
        private Boolean success;
        private Long orderId;
        private String errorMsg;
    }
}
