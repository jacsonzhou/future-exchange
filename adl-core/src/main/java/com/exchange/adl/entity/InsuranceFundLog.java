package com.exchange.adl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Insurance Fund Log - 保险基金流水实体
 *
 * 🔥 核心职责：
 * 1. 记录保险基金的所有收支流水
 * 2. 支持审计和对账
 * 3. 追溯每笔资金变动的来源
 */
@Data
@TableName("insurance_fund_log")
public class InsuranceFundLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    // ==================== 基本信息 ====================

    /**
     * 交易对
     */
    private String symbol;

    /**
     * 币种
     */
    private String currency;

    // ==================== 变动信息 ====================

    /**
     * 变动类型
     * LIQUIDATION_SURPLUS = 强平盈余注入
     * LIQUIDATION_FEE = 强平手续费注入
     * PLATFORM_SUBSIDY = 平台补贴
     * FEE_CONTRIBUTION = 交易手续费贡献
     * COVER_BANKRUPT = 赔付穿仓
     * ADL_COMPENSATION = ADL补偿
     * MANUAL_ADJUSTMENT = 人工调整
     */
    private String changeType;

    /**
     * 变动金额（正=收入，负=支出）
     */
    private BigDecimal amount;

    /**
     * 变动前余额
     */
    private BigDecimal balanceBefore;

    /**
     * 变动后余额
     */
    private BigDecimal balanceAfter;

    // ==================== 关联信息 ====================

    /**
     * 关联ID（强平ID、ADL ID等）
     */
    private String refId;

    /**
     * 关联类型
     */
    private String refType;

    /**
     * 关联用户ID（如有）
     */
    private Long refUserId;

    /**
     * 说明
     */
    private String description;

    // ==================== 操作信息 ====================

    /**
     * 操作人ID（如人工调整）
     */
    private Long operatorId;

    /**
     * 操作人姓名
     */
    private String operatorName;

    /**
     * 操作IP
     */
    private String operatorIp;

    // ==================== 时间戳 ====================

    /**
     * 流水创建时间
     */
    private Long createdAt;

    // ==================== 辅助方法 ====================

    /**
     * 是否收入
     */
    public boolean isIncome() {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * 是否支出
     */
    public boolean isExpense() {
        return amount != null && amount.compareTo(BigDecimal.ZERO) < 0;
    }

    /**
     * 获取绝对金额
     */
    public BigDecimal getAbsAmount() {
        return amount != null ? amount.abs() : BigDecimal.ZERO;
    }

    /**
     * 获取余额变化
     */
    public BigDecimal getBalanceChange() {
        if (balanceAfter != null && balanceBefore != null) {
            return balanceAfter.subtract(balanceBefore);
        }
        return BigDecimal.ZERO;
    }
}
