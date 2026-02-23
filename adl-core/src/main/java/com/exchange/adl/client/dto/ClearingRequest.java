package com.exchange.adl.client.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 清算记账请求
 */
@Data
public class ClearingRequest {

    /**
     * 业务类型：ADL/TRADE/LIQUIDATION/FUNDING_FEE等
     */
    private String bizType;

    /**
     * 业务序列号（幂等性标识）
     */
    private String bizSeq;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 分录列表
     */
    private List<LedgerEntry> entries;

    /**
     * 附加信息
     */
    private Map<String, Object> metadata;

    /**
     * 时间戳
     */
    private Long timestamp;

    /**
     * 账本分录
     */
    @Data
    public static class LedgerEntry {

        /**
         * 用户ID
         */
        private Long userId;

        /**
         * 账户类型：POSITION/MARGIN/REALIZED_PNL/DEBT/INSURANCE_FUND等
         */
        private String accountType;

        /**
         * 币种
         */
        private String currency;

        /**
         * 金额（正=增加，负=减少）
         */
        private BigDecimal amount;

        /**
         * 方向：DEBIT（借方）/CREDIT（贷方）
         */
        private String direction;

        /**
         * 描述
         */
        private String description;

        /**
         * 关联持仓ID（如适用）
         */
        private Long positionId;
    }
}
