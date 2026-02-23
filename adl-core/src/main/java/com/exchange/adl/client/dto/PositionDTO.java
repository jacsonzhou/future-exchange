package com.exchange.adl.client.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 持仓DTO
 */
@Data
public class PositionDTO {

    /**
     * 持仓ID
     */
    private Long positionId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 方向：LONG/SHORT
     */
    private String side;

    /**
     * 持仓数量
     */
    private BigDecimal positionSize;

    /**
     * 开仓均价
     */
    private BigDecimal entryPrice;

    /**
     * 标记价格
     */
    private BigDecimal markPrice;

    /**
     * 强平价格
     */
    private BigDecimal liquidationPrice;

    /**
     * 保证金余额
     */
    private BigDecimal marginBalance;

    /**
     * 维持保证金
     */
    private BigDecimal maintenanceMargin;

    /**
     * 未实现盈亏
     */
    private BigDecimal unrealizedPnl;

    /**
     * 已实现盈亏
     */
    private BigDecimal realizedPnl;

    /**
     * 杠杆倍数
     */
    private Integer leverage;

    /**
     * 状态：ACTIVE/FROZEN/CLOSED
     */
    private String status;

    /**
     * 创建时间
     */
    private Long createdAt;

    /**
     * 更新时间
     */
    private Long updatedAt;

    /**
     * 计算有效杠杆
     */
    public BigDecimal getEffectiveLeverage() {
        if (marginBalance == null || marginBalance.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal positionValue = positionSize.multiply(markPrice);
        return positionValue.divide(marginBalance, 2, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * 计算盈亏比例
     */
    public BigDecimal getPnlRatio() {
        if (marginBalance == null || marginBalance.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        if (unrealizedPnl == null) {
            return BigDecimal.ZERO;
        }
        return unrealizedPnl.divide(marginBalance, 4, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * 是否盈利
     */
    public boolean isProfitable() {
        return unrealizedPnl != null && unrealizedPnl.compareTo(BigDecimal.ZERO) > 0;
    }
}
