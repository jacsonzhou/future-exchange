package com.exchange.adl.client.dto;

import lombok.Data;

/**
 * 持仓查询请求
 */
@Data
public class PositionQueryRequest {

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 方向：LONG/SHORT
     */
    private String side;

    /**
     * 只查询盈利仓位
     */
    private Boolean onlyProfitable;

    /**
     * 最小盈亏比例（可选）
     */
    private String minPnlRatio;

    /**
     * 排序方式：ADL_SCORE_DESC（默认）
     */
    private String orderBy;

    /**
     * 返回数量限制
     */
    private Integer limit;
}
