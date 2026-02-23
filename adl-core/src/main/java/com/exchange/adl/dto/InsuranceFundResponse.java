package com.exchange.adl.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 保险基金查询响应
 */
@Data
public class InsuranceFundResponse {

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 币种
     */
    private String currency;

    /**
     * 当前余额
     */
    private BigDecimal balance;

    /**
     * 可用余额
     */
    private BigDecimal availableBalance;

    /**
     * 累计收入
     */
    private BigDecimal totalIncome;

    /**
     * 累计支出
     */
    private BigDecimal totalExpense;

    /**
     * 今日收入
     */
    private BigDecimal todayIncome;

    /**
     * 今日支出
     */
    private BigDecimal todayExpense;

    /**
     * 赔付次数
     */
    private Integer coverCount;

    /**
     * ADL触发次数
     */
    private Integer adlTriggerCount;

    /**
     * 当前状态
     */
    private String status;

    /**
     * 最后更新时间
     */
    private Long updatedAt;
}
