package com.exchange.marketmaker.dto.request;

import lombok.Data;

import java.util.List;

/**
 * 批量下单请求
 */
@Data
public class BatchOrderRequest {

    private String batchId;  // 批次ID(幂等)
    private List<SingleOrderRequest> orders;

    @Data
    public static class SingleOrderRequest {
        private String symbol;
        private String side;  // BUY/SELL
        private String orderType;  // LIMIT/MARKET
        private String price;
        private String quantity;
        private String timeInForce;  // GTC/IOC/FOK
        private Boolean postOnly;  // 仅做Maker
        private String clientOrderId;  // 客户端订单ID(可选)
    }
}
