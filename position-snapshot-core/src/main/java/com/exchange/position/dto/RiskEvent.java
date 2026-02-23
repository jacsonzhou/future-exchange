package com.exchange.position.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Risk Event DTO（风险事件）
 */
@Data
public class RiskEvent {
    
    private String riskEventId;
    private Long userId;
    private String symbol;
    private String eventType;  // MARGIN_WARNING / LIQUIDATION_ALERT
    private BigDecimal marginRatio;
    private BigDecimal liquidationPrice;
    private BigDecimal unrealizedPnl;
    private Long timestamp;
}

