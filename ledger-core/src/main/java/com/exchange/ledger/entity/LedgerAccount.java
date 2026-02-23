package com.exchange.ledger.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * Ledger Account 账户主表（生产级）
 * 
 * 用途：记录用户各类账户的元数据
 */
@Data
@TableName("ledger_account")
public class LedgerAccount {
    
    /**
     * 账户ID（全局唯一）
     */
    @TableId(type = IdType.INPUT)
    private Long accountId;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 账户类型
     * @see com.exchange.ledger.enums.AccountType
     */
    private Integer accountType;
    
    /**
     * 币种
     */
    private String currency;
    
    /**
     * 创建时间（毫秒）
     */
    private Long createdAt;
    
    /**
     * 更新时间（毫秒）
     */
    private Long updatedAt;
}

