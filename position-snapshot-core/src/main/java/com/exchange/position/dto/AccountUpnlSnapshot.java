package com.exchange.position.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 账户快照镜像（仅用于持仓服务内 UPNL 联动回写）。
 */
@Data
public class AccountUpnlSnapshot {
    private Long userId;
    private BigDecimal available;
    private BigDecimal frozen;
    private BigDecimal positionMargin;
    private BigDecimal unrealizedPnl;
    private BigDecimal equity;
    private Integer version;
}

