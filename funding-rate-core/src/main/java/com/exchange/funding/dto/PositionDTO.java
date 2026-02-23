package com.exchange.funding.dto;

import lombok.Data;

/**
 * 持仓DTO
 */
@Data
public class PositionDTO {
    private Long userId;
    private String symbol;
    private String side;           // LONG / SHORT
    private Long qty;              // 持仓数量（精度8位）
    private Long entryPrice;       // 开仓均价
    private String marginMode;     // ISOLATED / CROSS
    private Long leverage;         // 杠杆倍数
}
