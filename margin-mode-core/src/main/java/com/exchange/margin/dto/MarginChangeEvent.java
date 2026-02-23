package com.exchange.margin.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 保证金变动事件
 *
 * 发布到 margin-change-topic，供其他服务消费
 */
@Data
public class MarginChangeEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 仓位ID
     */
    private Long positionId;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 保证金模式：CROSS, ISOLATED
     */
    private String marginMode;

    /**
     * 变动类型：
     * - OPEN: 开仓
     * - CLOSE: 平仓
     * - ADD: 追加保证金
     * - REDUCE: 减少保证金
     * - SWITCH: 模式切换
     * - LIQUIDATION: 强平
     * - ADL: 自动减仓
     */
    private String changeType;

    /**
     * 变动金额（精度8位：1 USDT = 10^8）
     * 正数表示增加，负数表示减少
     */
    private Long changeAmount;

    /**
     * 变动前保证金（精度8位）
     */
    private Long beforeMargin;

    /**
     * 变动后保证金（精度8位）
     */
    private Long afterMargin;

    /**
     * 变动前杠杆倍数
     */
    private Integer beforeLeverage;

    /**
     * 变动后杠杆倍数
     */
    private Integer afterLeverage;

    /**
     * 变动前保证金率（万分比）
     */
    private Long beforeMarginRatio;

    /**
     * 变动后保证金率（万分比）
     */
    private Long afterMarginRatio;

    /**
     * 变动前强平价格（精度8位）
     */
    private Long beforeLiquidationPrice;

    /**
     * 变动后强平价格（精度8位）
     */
    private Long afterLiquidationPrice;

    /**
     * 事件时间戳
     */
    private Long timestamp;

    /**
     * 事件序列号
     */
    private Long sequence;

    /**
     * 备注
     */
    private String remark;
}
