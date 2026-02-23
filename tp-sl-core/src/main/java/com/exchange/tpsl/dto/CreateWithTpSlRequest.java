package com.exchange.tpsl.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

/**
 * 开仓时同时设置TP/SL
 */
@Data
public class CreateWithTpSlRequest {

    @NotNull(message = "userId不能为空")
    private Long userId;

    @NotBlank(message = "symbol不能为空")
    private String symbol;

    @NotBlank(message = "side不能为空")
    private String side; // BUY/SELL

    @NotBlank(message = "orderType不能为空")
    private String orderType; // LIMIT/MARKET

    private Long price; // 开仓价格

    @NotNull(message = "quantity不能为空")
    @Positive(message = "quantity必须大于0")
    private Long quantity;

    @NotNull(message = "leverage不能为空")
    @Positive(message = "leverage必须大于0")
    private Integer leverage;

    /**
     * TP/SL配置
     */
    private TpSlConfig tpSlConfig;

    @Data
    public static class TpSlConfig {
        /**
         * 止盈触发价
         */
        private Long tpTriggerPrice;

        /**
         * 止盈执行类型: MARKET/LIMIT
         */
        private String tpExecType;

        /**
         * 止盈执行限价
         */
        private Long tpExecPrice;

        /**
         * 止损触发价
         */
        private Long slTriggerPrice;

        /**
         * 止损执行类型: MARKET/LIMIT
         */
        private String slExecType;

        /**
         * 止损执行限价
         */
        private Long slExecPrice;

        /**
         * 触发价格类型: MARK/LAST/INDEX
         */
        private String triggerBy = "MARK";
    }
}
