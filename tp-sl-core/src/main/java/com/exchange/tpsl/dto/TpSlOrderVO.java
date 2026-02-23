package com.exchange.tpsl.dto;

import lombok.Data;

/**
 * TP/SL订单视图对象
 */
@Data
public class TpSlOrderVO {

    private Long tpSlOrderId;
    private Long userId;
    private String symbol;
    private Long positionId;

    private String type; // TP/SL/TRAILING
    private String triggerType; // MARK/LAST/INDEX
    private Long triggerPrice;
    private String triggerSide; // LONG/SHORT

    private String execType; // MARKET/LIMIT
    private Long execPrice;
    private Long quantity;

    // 移动止损
    private Long trailingPercent;
    private Long trailingOffset;
    private Long highestPrice;

    private String status;
    private Long triggerTime;
    private Long execOrderId;
    private Long createdAt;
    private Long updatedAt;
}
