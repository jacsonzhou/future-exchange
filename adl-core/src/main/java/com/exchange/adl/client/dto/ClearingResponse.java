package com.exchange.adl.client.dto;

import lombok.Data;

import java.util.List;

/**
 * 清算记账响应
 */
@Data
public class ClearingResponse {

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 业务序列号
     */
    private String bizSeq;

    /**
     * 账本记录ID列表
     */
    private List<String> ledgerIds;

    /**
     * 消息
     */
    private String message;

    /**
     * 错误码（如失败）
     */
    private String errorCode;
}
