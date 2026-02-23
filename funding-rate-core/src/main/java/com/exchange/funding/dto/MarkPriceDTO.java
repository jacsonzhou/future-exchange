package com.exchange.funding.dto;

import lombok.Data;

/**
 * 标记价格DTO
 */
@Data
public class MarkPriceDTO {
    private String symbol;
    private Long price;        // 标记价格（精度8位）
    private Long updateTime;   // 更新时间戳
}
