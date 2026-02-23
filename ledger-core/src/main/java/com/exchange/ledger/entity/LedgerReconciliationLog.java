package com.exchange.ledger.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Ledger Reconciliation Log 对账记录（生产级）
 * 
 * 用途：记录每日对账结果
 */
@Data
@TableName("ledger_reconciliation_log")
public class LedgerReconciliationLog {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 对账日期
     */
    private java.sql.Date checkDate;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 币种
     */
    private String currency;
    
    /**
     * LedgerEntry计算余额
     */
    private BigDecimal ledgerBalance;
    
    /**
     * AccountSnapshot余额
     */
    private BigDecimal snapshotBalance;
    
    /**
     * 差异金额
     */
    private BigDecimal diffAmount;
    
    /**
     * 状态：0=一致,1=差异,2=已修复
     */
    private Integer status;
    
    /**
     * 检查起始biz_seq
     */
    private Long checkStartSeq;
    
    /**
     * 检查结束biz_seq
     */
    private Long checkEndSeq;
    
    /**
     * 创建时间
     */
    private Long createdAt;
}

