package com.exchange.liquidation.dto;

import lombok.Data;

/**
 * 仓位信息
 */
@Data
public class PositionInfo {
    
    private Long positionId;
    private Long userId;
    private String symbol;
    private String side;
    private String marginMode;
    private Long quantity;
    private Long entryPrice;
    private Long markPrice;
    private Long liquidationPrice;
    private Long bankruptcyPrice;
    private Integer leverage;
}
