package com.exchange.tpsl.dto;

import lombok.Data;

/**
 * 持仓关联的TP/SL订单
 */
@Data
public class PositionTpSlVO {

    private Long positionId;

    /**
     * 止盈订单
     */
    private TpSlInfo takeProfit;

    /**
     * 止损订单
     */
    private TpSlInfo stopLoss;

    @Data
    public static class TpSlInfo {
        private Long tpSlOrderId;
        private Long triggerPrice;
        private Long quantity;
        private String execType;
        private String status;
    }
}
