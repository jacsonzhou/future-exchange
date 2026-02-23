package com.exchange.liquidation.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 强平触发事件
 * 
 * 消费自 Margin-Mode-Core 发布的 liquidation-trigger-topic
 */
@Data
public class LiquidationTriggerEvent implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private Long userId;
    private Long positionId;
    private String symbol;
    private String marginMode;
    private String triggerType;
    private Long marginRatio;
    private Long liquidationThreshold;
    private Long markPrice;
    private Long liquidationPrice;
    private Long bankruptcyPrice;
    private Integer positionSide;
    private Long positionQty;
    private Long entryPrice;
    private Long currentMargin;
    private Long maintenanceMargin;
    private Long unrealizedPnl;
    private Integer leverage;
    private Integer priority;
    private Long timestamp;
    private Long sequence;
    private String remark;
    
    public String getCloseSide() {
        return positionSide != null && positionSide == 1 ? "SELL" : "BUY";
    }
    
    public String getPositionSideStr() {
        return positionSide != null && positionSide == 1 ? "LONG" : "SHORT";
    }
}
