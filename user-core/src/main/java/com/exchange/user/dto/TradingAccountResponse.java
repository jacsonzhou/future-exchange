package com.exchange.user.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 交易账户响应
 */
@Data
public class TradingAccountResponse {

    private Long accountId;
    private Long userId;
    private String accountType;
    private String marginMode;
    private Integer defaultLeverage;
    private Integer status;
    private Boolean funded;
    private LocalDateTime createdAt;
}
