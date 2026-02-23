package com.exchange.margin.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 强平触发事件
 *
 * 发布到 liquidation-trigger-topic，供 Liquidation Service 消费
 */
@Data
public class LiquidationTriggerEvent implements Serializable {

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
     * 触发类型：
     * - MARGIN_RATIO: 保证金率过低
     * - MARK_PRICE: 标记价格触发强平价
     * - BANKRUPTCY: 破产价触发
     */
    private String triggerType;

    /**
     * 当前保证金率（万分比：10000 = 100%）
     */
    private Long marginRatio;

    /**
     * 强平阈值（万分比，默认1000 = 10%）
     */
    private Long liquidationThreshold;

    /**
     * 当前标记价格（精度8位）
     */
    private Long markPrice;

    /**
     * 强平价格（精度8位）
     */
    private Long liquidationPrice;

    /**
     * 破产价格（精度8位）
     */
    private Long bankruptcyPrice;

    /**
     * 仓位方向：1=多仓, 2=空仓
     */
    private Integer positionSide;

    /**
     * 仓位数量（精度8位）
     */
    private Long positionQty;

    /**
     * 开仓均价（精度8位）
     */
    private Long entryPrice;

    /**
     * 当前保证金（精度8位）
     */
    private Long currentMargin;

    /**
     * 维持保证金（精度8位）
     */
    private Long maintenanceMargin;

    /**
     * 未实现盈亏（精度8位）
     */
    private Long unrealizedPnl;

    /**
     * 杠杆倍数
     */
    private Integer leverage;

    /**
     * 优先级：1=最高, 5=最低（保证金率越低优先级越高）
     */
    private Integer priority;

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
