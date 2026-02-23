package com.exchange.margin.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 账户余额变动事件
 *
 * 从 Clearing Service 的 account-change-topic 消费
 */
@Data
public class AccountBalanceEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 币种
     */
    private String currency;

    /**
     * 变动类型：DEPOSIT, WITHDRAW, TRADE, FEE, FUNDING, LIQUIDATION, ADL, etc.
     */
    private String changeType;

    /**
     * 变动金额（精度8位：1 USDT = 10^8）
     */
    private Long amount;

    /**
     * 变动后总余额（精度8位）
     */
    private Long totalBalance;

    /**
     * 变动后可用余额（精度8位）
     */
    private Long availableBalance;

    /**
     * 变动后冻结余额（精度8位）
     */
    private Long frozenBalance;

    /**
     * 关联的仓位ID（如果有）
     */
    private Long positionId;

    /**
     * 关联的订单ID（如果有）
     */
    private Long orderId;

    /**
     * 关联的交易对（如果有）
     */
    private String symbol;

    /**
     * 事件时间戳
     */
    private Long timestamp;

    /**
     * 事件序列号（用于幂等性检查）
     */
    private Long sequence;

    /**
     * 备注
     */
    private String remark;
}
