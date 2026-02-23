package com.exchange.funding.dto;

import lombok.Data;

/**
 * 指数价格DTO
 */
@Data
public class IndexPriceDTO {
    private String symbol;
    private Long price;        // 指数价格（精度8位）
    private Long updateTime;   // 更新时间戳
}
