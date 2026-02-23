package com.exchange.oms.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 订单列表查询请求
 */
@Data
public class OrderListRequest implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 交易对（可选，不传则查询所有）
     */
    private String symbol;
    
    /**
     * 订单状态（可选，不传则查询所有）
     * 多个状态用逗号分隔，如：NEW,PARTIALLY_FILLED
     */
    private String status;
    
    /**
     * 分页偏移
     */
    private Integer offset = 0;
    
    /**
     * 分页大小
     */
    private Integer limit = 20;
}
