package com.exchange.ledger.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Ledger Entry 双录分录（生产级）
 * 
 * 🔥 核心设计原则：
 * 1. 双录记账：每笔业务生成2条分录（借+贷）
 * 2. 借贷平衡：SUM(debit) = SUM(credit)
 * 3. 只追加：不可修改，不可删除（审计要求）
 * 4. 全局序列：biz_seq保证Replay顺序
 * 5. 幂等性：idempotent_key防重复
 * 
 * 对标：Binance / OKX / Bybit级别
 */
@Data
@TableName("t_ledger_entry")
public class LedgerEntry {
    
    /**
     * 分录ID（全局唯一）
     * 使用Snowflake生成
     */
    @TableId(type = IdType.INPUT)
    private Long entryId;
    
    /**
     * 用户ID
     * 分库键：user_id % 128
     */
    private Long userId;
    
    /**
     * 账户类型
     * @see com.exchange.ledger.enums.AccountType
     */
    private Integer accountType;
    
    /**
     * 币种（如：USDT, BTC）
     */
    private String currency;
    
    // ==================== 🔥 双录字段 ====================
    
    /**
     * 借方金额（资产增加/负债减少）
     * debit = 0 表示该分录是贷方
     */
    private BigDecimal debit;
    
    /**
     * 贷方金额（资产减少/负债增加）
     * credit = 0 表示该分录是借方
     */
    private BigDecimal credit;
    
    // ==================== 🔥 余额快照（性能优化）====================
    
    /**
     * 分录前余额
     * 用于快速验证和回溯
     */
    private BigDecimal balanceBefore;
    
    /**
     * 分录后余额
     * balanceAfter = balanceBefore + debit - credit
     */
    private BigDecimal balanceAfter;
    
    // ==================== 🔥 业务关联 ====================
    
    /**
     * 业务类型
     * @see com.exchange.ledger.enums.BusinessType
     */
    private String businessType;
    
    /**
     * 关联成交ID
     */
    private String refTradeId;
    
    /**
     * 关联订单ID
     */
    private Long refOrderId;
    
    /**
     * 关联事件ID（通用）
     */
    private String refEventId;
    
    // ==================== 🔥 成对分录 ====================
    
    /**
     * 成对分录ID
     * 双录必成对：每笔业务的借方和贷方互相关联
     */
    private Long pairEntryId;
    
    // ==================== 🔥 全局序列（Replay核心）====================
    
    /**
     * 全局业务序列号
     * 
     * 🔥 核心用途：
     * 1. Replay排序：SELECT * ORDER BY biz_seq
     * 2. 跨库聚合：分库分表后仍能全局排序
     * 3. 增量同步：WHERE biz_seq > last_seq
     * 
     * 生成方式：
     * - Redis INCR（推荐）
     * - Snowflake（分布式ID）
     * - 数据库序列（单点）
     */
    private Long bizSeq;
    
    // ==================== 🔥 幂等性保证 ====================
    
    /**
     * 幂等键（业务唯一）
     * 
     * 格式示例：
     * - TRADE:tradeId:userId:accountType
     * - FUNDING:fundingId:userId:accountType
     * - LIQUIDATION:liquidationId:userId:accountType
     * 
     * 用途：防止重复消费Kafka消息
     */
    private String idempotentKey;
    
    // ==================== 时间戳 ====================
    
    /**
     * 创建时间（毫秒）
     */
    private Long createdAt;
    
    // ==================== 辅助方法 ====================
    
    /**
     * 是否为借方分录
     * 注意：方法名不能为 isDebit()，否则会与 getDebit() 冲突，导致 MyBatis 反射错误
     */
    public boolean isDebitEntry() {
        return debit != null && debit.compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * 是否为贷方分录
     * 注意：方法名不能为 isCredit()，否则会与 getCredit() 冲突，导致 MyBatis 反射错误
     */
    public boolean isCreditEntry() {
        return credit != null && credit.compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * 获取分录金额（绝对值）
     */
    public BigDecimal getAmount() {
        return isDebitEntry() ? debit : credit;
    }
    
    /**
     * 校验借贷平衡
     * 单条分录：debit = 0 或 credit = 0
     */
    public boolean validateSingleEntry() {
        boolean debitIsZero = debit == null || debit.compareTo(BigDecimal.ZERO) == 0;
        boolean creditIsZero = credit == null || credit.compareTo(BigDecimal.ZERO) == 0;
        
        // 有且仅有一个为0
        return debitIsZero ^ creditIsZero;
    }
}


