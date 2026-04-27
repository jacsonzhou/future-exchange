package com.exchange.tpsl.dto;

import lombok.Data;

import jakarta.validation.constraints.NotNull;

/**
 * 批量撤销TP/SL订单请求
 */
@Data
public class BatchCancelRequest {

    @NotNull(message = "userId不能为空")
    private Long userId;

    private String symbol;

    /**
     * 持仓ID (可选，不填则撤销该symbol下所有)
     */
    private Long positionId;
}
