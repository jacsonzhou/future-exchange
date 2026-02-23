package com.exchange.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资金调整记录
 * 
 * 用于记录初始资金注入、运营调账等操作
 */
@Data
@TableName("t_funding_adjustment")
public class FundingAdjustment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 账户ID
     */
    private Long accountId;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 资产类型
     */
    private String asset;

    /**
     * 调整金额 (正数增加，负数减少)
     */
    private BigDecimal amount;

    /**
     * 调整前余额
     */
    private BigDecimal balanceBefore;

    /**
     * 调整后余额
     */
    private BigDecimal balanceAfter;

    /**
     * 调整原因: INITIAL_FUNDING-初始资金 MANUAL_ADJUST-手动调整
     */
    private String reason;

    /**
     * 操作人ID (系统操作为null)
     */
    private Long operatorId;

    /**
     * 操作人备注
     */
    private String remark;

    /**
     * 关联的ledger entry ID
     */
    private String ledgerEntryId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
