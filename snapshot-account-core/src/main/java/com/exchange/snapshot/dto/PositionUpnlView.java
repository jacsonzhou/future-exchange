package com.exchange.snapshot.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 持仓未实现盈亏视图（用于账户总 UPNL 回补汇总）。
 */
@Data
public class PositionUpnlView {
    private String symbol;
    private Integer positionSide;
    private BigDecimal quantity;
    private BigDecimal unrealizedPnl;
}
