package com.exchange.tpsl.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 创建TP/SL订单请求
 */
@Data
public class CreateTpSlRequest {

    @NotNull(message = "userId不能为空")
    private Long userId;

    @NotBlank(message = "symbol不能为空")
    private String symbol;

    @NotNull(message = "positionId不能为空")
    private Long positionId;

    /**
     * 订单类型: TP/SL/TRAILING
     */
    @NotBlank(message = "type不能为空")
    private String type;

    /**
     * 触发价格
     */
    @NotNull(message = "triggerPrice不能为空")
    @Positive(message = "triggerPrice必须大于0")
    private Long triggerPrice;

    /**
     * 执行类型: MARKET/LIMIT
     */
    @NotBlank(message = "execType不能为空")
    private String execType;

    /**
     * 执行限价 (当execType=LIMIT时必填)
     */
    private Long execPrice;

    /**
     * 平仓数量
     */
    @NotNull(message = "quantity不能为空")
    @Positive(message = "quantity必须大于0")
    private Long quantity;

    /**
     * 触发类型: MARK/LAST/INDEX
     */
    private String triggerBy = "MARK";

    /**
     * 客户端订单ID (幂等)
     */
    private String clientOrderId;

    /**
     * 过期时间 (可选)
     */
    private Long expireTime;

    // === 移动止损专用字段 ===

    /**
     * 回调比例 (如 500 = 5%)
     */
    private Long trailingPercent;

    /**
     * 回调金额
     */
    private Long trailingOffset;
}
