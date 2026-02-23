package com.exchange.index.dto;

import lombok.Data;

import java.util.List;

/**
 * 指数价格DTO
 */
@Data
public class IndexPriceDTO {

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 指数价格（8位精度）
     */
    private Long price;

    /**
     * 数据时间戳
     */
    private Long timestamp;

    /**
     * 价格成分列表
     */
    private List<ComponentDTO> components;

    @Data
    public static class ComponentDTO {
        private String exchange;
        private Long price;
        private Integer weight;
        private Boolean valid;
    }
}
