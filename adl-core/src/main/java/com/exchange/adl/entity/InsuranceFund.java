package com.exchange.adl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Insurance Fund - 保险基金实体
 * 
 * 🔥 核心职责：
 * 1. 管理保险基金余额
 * 2. 记录保险基金的收支明细
 * 3. 在强平无法完全成交时提供资金保障
 * 4. 在ADL时可能涉及赔付
 * 
 * 🔥 保险基金来源：
 * 1. 强平手续费收入
 * 2. 强平盈利注入（当强平盈利时部分注入保险基金）
 * 3. 平台初始注资
 * 
 * 🔥 保险基金用途：
 * 1. 强平亏损赔付（当强平价格不利时）
 * 2. 穿仓损失赔付（当用户权益为负时）
 * 3. ADL时的资金调节
 * 
 * 🔥 保险基金阈值（对标主流交易所）：
 * - 安全阈值：余额 > 100万 USDT
 * - 警告阈值：余额 < 50万 USDT
 * - 危险阈值：余额 < 10万 USDT（触发限制开仓）
 */
@Data
@TableName("insurance_fund")
public class InsuranceFund {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    // ==================== 基本信息 ====================
    
    /**
     * 交易对
     * 每symbol独立保险基金（部分交易所按币种）
     */
    private String symbol;
    
    /**
     * 币种
     * 如：USDT, BTC, ETH
     */
    private String currency;
    
    // ==================== 余额信息 ====================
    
    /**
     * 当前余额
     */
    private BigDecimal balance;
    
    /**
     * 可用余额
     * 考虑冻结金额后的可用部分
     */
    private BigDecimal availableBalance;
    
    /**
     * 冻结金额
     * 已承诺但未完成的赔付
     */
    private BigDecimal frozenAmount;
    
    // ==================== 累计收支 ====================
    
    /**
     * 累计收入
     * 来自强平手续费、强平盈利注入等
     */
    private BigDecimal totalIncome;
    
    /**
     * 累计支出
     * 用于赔付穿仓损失等
     */
    private BigDecimal totalExpense;
    
    /**
     * 累计强平手续费收入
     */
    private BigDecimal totalLiquidationFeeIncome;
    
    /**
     * 累计强平盈利注入
     */
    private BigDecimal totalLiquidationProfitInjection;
    
    /**
     * 累计穿仓赔付支出
     */
    private BigDecimal totalDebtLossExpense;
    
    /**
     * 累计ADL相关赔付
     */
    private BigDecimal totalAdlCompensation;
    
    // ==================== 统计信息 ====================
    
    /**
     * 今日收入
     */
    private BigDecimal todayIncome;
    
    /**
     * 今日支出
     */
    private BigDecimal todayExpense;
    
    /**
     * 今日日期（用于重置日统计）
     * 格式：yyyyMMdd
     */
    private Integer todayDate;
    
    /**
     * 历史最高余额
     */
    private BigDecimal maxBalance;
    
    /**
     * 历史最低余额
     */
    private BigDecimal minBalance;
    
    // ==================== 阈值配置 ====================
    
    /**
     * 安全阈值
     * 高于此值为安全状态
     */
    private BigDecimal safeThreshold;
    
    /**
     * 警告阈值
     * 低于此值触发告警
     */
    private BigDecimal warningThreshold;
    
    /**
     * 危险阈值
     * 低于此值限制开仓
     */
    private BigDecimal dangerThreshold;
    
    /**
     * 当前状态
     * SAFE = 安全
     * WARNING = 警告
     * DANGER = 危险
     */
    private String status;
    
    // ==================== 元信息 ====================
    
    /**
     * 版本号（乐观锁）
     */
    private Integer version;
    
    /**
     * 最后操作类型
     */
    private String lastOperationType;
    
    /**
     * 最后操作金额
     */
    private BigDecimal lastOperationAmount;
    
    /**
     * 最后操作时间
     */
    private Long lastOperationAt;
    
    /**
     * 最后操作关联ID
     */
    private String lastOperationRefId;
    
    // ==================== 时间戳 ====================
    
    /**
     * 记录创建时间
     */
    private Long createdAt;
    
    /**
     * 记录更新时间
     */
    private Long updatedAt;
    
    // ==================== 辅助方法 ====================
    
    /**
     * 是否安全状态
     */
    public boolean isSafe() {
        return "SAFE".equalsIgnoreCase(status) || 
               (balance != null && safeThreshold != null && balance.compareTo(safeThreshold) >= 0);
    }
    
    /**
     * 是否警告状态
     */
    public boolean isWarning() {
        return "WARNING".equalsIgnoreCase(status) ||
               (balance != null && warningThreshold != null && 
                balance.compareTo(warningThreshold) >= 0 && 
                balance.compareTo(safeThreshold) < 0);
    }
    
    /**
     * 是否危险状态
     */
    public boolean isDanger() {
        return "DANGER".equalsIgnoreCase(status) ||
               (balance != null && dangerThreshold != null && balance.compareTo(dangerThreshold) < 0);
    }
    
    /**
     * 收入资金
     * @param amount 金额
     * @param type 类型
     * @param refId 关联ID
     */
    public void income(BigDecimal amount, String type, String refId) {
        this.balance = this.balance.add(amount);
        this.availableBalance = this.availableBalance.add(amount);
        this.totalIncome = this.totalIncome.add(amount);
        this.todayIncome = this.todayIncome.add(amount);
        
        if (amount.compareTo(BigDecimal.ZERO) > 0) {
            if (balance.compareTo(maxBalance) > 0) {
                this.maxBalance = balance;
            }
        }
        
        recordOperation(type, amount, refId);
        updateStatus();
    }
    
    /**
     * 支出资金
     * @param amount 金额
     * @param type 类型
     * @param refId 关联ID
     * @return 是否成功
     */
    public boolean expense(BigDecimal amount, String type, String refId) {
        if (availableBalance.compareTo(amount) < 0) {
            return false;
        }
        
        this.balance = this.balance.subtract(amount);
        this.availableBalance = this.availableBalance.subtract(amount);
        this.totalExpense = this.totalExpense.add(amount);
        this.todayExpense = this.todayExpense.add(amount);
        
        if (amount.compareTo(BigDecimal.ZERO) > 0) {
            if (balance.compareTo(minBalance) < 0) {
                this.minBalance = balance;
            }
        }
        
        recordOperation(type, amount.negate(), refId);
        updateStatus();
        return true;
    }
    
    /**
     * 冻结资金
     * @param amount 金额
     * @return 是否成功
     */
    public boolean freeze(BigDecimal amount) {
        if (availableBalance.compareTo(amount) < 0) {
            return false;
        }
        this.availableBalance = availableBalance.subtract(amount);
        this.frozenAmount = frozenAmount.add(amount);
        return true;
    }
    
    /**
     * 解冻资金
     * @param amount 金额
     */
    public void unfreeze(BigDecimal amount) {
        this.frozenAmount = frozenAmount.subtract(amount);
        this.availableBalance = availableBalance.add(amount);
    }
    
    /**
     * 确认支出（从冻结中扣除）
     * @param amount 金额
     */
    public void confirmExpenseFromFrozen(BigDecimal amount) {
        this.frozenAmount = frozenAmount.subtract(amount);
        this.balance = balance.subtract(amount);
        this.totalExpense = totalExpense.add(amount);
        this.todayExpense = todayExpense.add(amount);
    }
    
    /**
     * 取消冻结
     * @param amount 金额
     */
    public void cancelFreeze(BigDecimal amount) {
        this.frozenAmount = frozenAmount.subtract(amount);
        this.availableBalance = availableBalance.add(amount);
    }
    
    /**
     * 记录操作
     */
    private void recordOperation(String type, BigDecimal amount, String refId) {
        this.lastOperationType = type;
        this.lastOperationAmount = amount;
        this.lastOperationRefId = refId;
        this.lastOperationAt = System.currentTimeMillis();
    }
    
    /**
     * 更新状态
     */
    private void updateStatus() {
        if (isDanger()) {
            this.status = "DANGER";
        } else if (isWarning()) {
            this.status = "WARNING";
        } else {
            this.status = "SAFE";
        }
    }
    
    /**
     * 检查并重置日统计
     * @param currentDate 当前日期（yyyyMMdd）
     */
    public void checkAndResetDailyStats(Integer currentDate) {
        if (!currentDate.equals(todayDate)) {
            this.todayIncome = BigDecimal.ZERO;
            this.todayExpense = BigDecimal.ZERO;
            this.todayDate = currentDate;
        }
    }
    
    /**
     * 获取净盈亏
     */
    public BigDecimal getNetPnl() {
        return totalIncome.subtract(totalExpense);
    }
}
