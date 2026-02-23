package com.exchange.common.proto.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 账户快照
 */
@Data
public class AccountSnapshot implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 资产类型（如：USDT）
     */
    private String asset;
    
    /**
     * 可用余额
     */
    private Long available;
    
    /**
     * 冻结余额
     */
    private Long frozen;
    
    /**
     * 总余额 = available + frozen
     */
    public Long getTotal() {
        return available + frozen;
    }
    
    /**
     * 更新时间
     */
    private Long updateTime;
}

