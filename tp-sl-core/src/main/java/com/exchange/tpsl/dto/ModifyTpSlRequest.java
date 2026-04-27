package com.exchange.tpsl.dto;

import lombok.Data;

import jakarta.validation.constraints.NotNull;

/**
 * 修改TP/SL订单请求
 */
@Data
public class ModifyTpSlRequest {

    @NotNull(message = "userId不能为空")
    private Long userId;

    @NotNull(message = "tpSlOrderId不能为空")
    private Long tpSlOrderId;

    /**
     * 新触发价格
     */
    private Long triggerPrice;

    /**
     * 新数量
     */
    private Long quantity;

    /**
     * 新执行限价
     */
    private Long execPrice;
}
