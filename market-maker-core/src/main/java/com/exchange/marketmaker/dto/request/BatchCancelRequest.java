package com.exchange.marketmaker.dto.request;

import lombok.Data;

import java.util.List;

/**
 * 批量撤单请求
 */
@Data
public class BatchCancelRequest {

    private String batchId;  // 批次ID(幂等)
    private List<Long> orderIds;  // 订单ID列表

    // 或按条件撤销
    private String symbol;  // 可选
    private String side;    // 可选
}
