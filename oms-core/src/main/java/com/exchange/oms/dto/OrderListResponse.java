package com.exchange.oms.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 订单列表查询响应
 */
@Data
public class OrderListResponse implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 订单列表
     */
    private List<QueryOrderResponse> orders;
    
    /**
     * 总数
     */
    private Long total;
    
    /**
     * 分页偏移
     */
    private Integer offset;
    
    /**
     * 分页大小
     */
    private Integer limit;
    
    /**
     * 是否还有更多
     */
    private Boolean hasMore;
    
    public static OrderListResponse empty() {
        OrderListResponse response = new OrderListResponse();
        response.setOrders(List.of());
        response.setTotal(0L);
        response.setOffset(0);
        response.setLimit(20);
        response.setHasMore(false);
        return response;
    }
}
