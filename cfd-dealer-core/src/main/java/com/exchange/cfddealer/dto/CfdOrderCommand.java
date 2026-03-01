package com.exchange.cfddealer.dto;

import lombok.Data;

@Data
public class CfdOrderCommand {

    private String eventType;
    private Long orderId;
    private Long userId;
    private String clientOrderId;
    private String symbol;
    private String side;
    private String orderType;
    private String price;
    private String quantity;
    private Integer leverage;
    private String timeInForce;

    private String executionMode;
    private String liquiditySource;
    private String referenceTopic;
    private Long referenceOffset;
    private Long referenceEventTime;
    private String referenceBestBid;
    private String referenceBestAsk;
    private String referenceVwapPrice;
    private Integer slippageBps;

    private Long eventTime;
}
