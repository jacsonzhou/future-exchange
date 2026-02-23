package com.exchange.marketmaker.dto.request;

import lombok.Data;

/**
 * 一键撤单请求
 */
@Data
public class CancelAllRequest {

    private String symbol;  // 可选，不传则撤销全部
    private String side;    // 可选
}
