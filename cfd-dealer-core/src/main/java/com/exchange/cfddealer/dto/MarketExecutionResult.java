package com.exchange.cfddealer.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class MarketExecutionResult {

    private Long orderId;
    private Long userId;
    private String symbol;
    private String side;

    private BigDecimal filledQuantity;
    private BigDecimal vwapPrice;
    private BigDecimal bestBid;
    private BigDecimal bestAsk;
    private Integer slippageBps;

    private String referenceTopic;
    private Long referenceOffset;
    private Long referenceEventTime;

    private String executionMode;
    private String liquiditySource;

    private long matchSequence;
    private String tradeId;
    private long tradeTime;
}
