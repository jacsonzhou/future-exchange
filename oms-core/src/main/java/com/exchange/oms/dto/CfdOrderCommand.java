package com.exchange.oms.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * CFD Dealer 指令（OMS -> CFD Dealer）
 */
@Data
public class CfdOrderCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 指令类型：CFD_ORDER_SUBMIT / CFD_CANCEL
     */
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
