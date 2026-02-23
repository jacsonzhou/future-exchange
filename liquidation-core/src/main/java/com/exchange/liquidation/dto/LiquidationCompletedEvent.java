package com.exchange.liquidation.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 强平完成事件
 * 
 * 发布到 liquidation-completed-topic，供ADL服务消费
 */
@Data
@Builder
public class LiquidationCompletedEvent implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private String eventType;
    private String liquidationId;
    private Long userId;
    private Long positionId;
    private String symbol;
    private String side;
    private String marginMode;
    private Boolean isBankrupt;
    private Long bankruptPrice;
    private Long bankruptQty;
    private Long bankruptLoss;
    private Long insuranceCover;
    private Long remainingLoss;
    private Boolean adlRequired;
    private Long executedPrice;
    private Long executedQty;
    private Long realizedPnl;
    private Long timestamp;
    private Long sequence;
}
