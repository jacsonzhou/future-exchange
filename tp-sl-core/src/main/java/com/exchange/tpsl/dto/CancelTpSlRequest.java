package com.exchange.tpsl.dto;

import lombok.Data;

import jakarta.validation.constraints.NotNull;

/**
 * 撤销TP/SL订单请求
 */
@Data
public class CancelTpSlRequest {

    @NotNull(message = "userId不能为空")
    private Long userId;

    @NotNull(message = "tpSlOrderId不能为空")
    private Long tpSlOrderId;
}
