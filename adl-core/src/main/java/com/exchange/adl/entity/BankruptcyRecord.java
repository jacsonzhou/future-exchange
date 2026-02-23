package com.exchange.adl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Bankruptcy Record - 穿仓记录实体
 *
 * 🔥 核心职责：
 * 1. 记录所有穿仓事件
 * 2. 跟踪穿仓损失的处理状态
 * 3. 关联ADL执行记录
 *
 * 🔥 穿仓处理流程：
 * 1. 强平后检测到穿仓（权益为负）
 * 2. 创建穿仓记录
 * 3. 计算穿仓损失
 * 4. 优先使用保险基金赔付
 * 5. 保险基金不足时触发ADL
 * 6. 更新处理状态
 */
@Data
@TableName("bankruptcy_record")
public class BankruptcyRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    // ==================== 关联信息 ====================

    /**
     * 强平记录ID
     */
    private String liquidationId;

    /**
     * 穿仓用户ID
     */
    private Long userId;

    /**
     * 交易对
     */
    private String symbol;

    // ==================== 仓位信息 ====================

    /**
     * 仓位方向
     */
    private String side;

    /**
     * 破产价格（穿仓价格）
     */
    private BigDecimal bankruptPrice;

    /**
     * 标记价格（穿仓时）
     */
    private BigDecimal markPrice;

    /**
     * 穿仓数量
     */
    private BigDecimal bankruptQty;

    /**
     * 穿仓损失金额（绝对值）
     */
    private BigDecimal bankruptLoss;

    /**
     * 原始保证金
     */
    private BigDecimal originalMargin;

    /**
     * 维持保证金
     */
    private BigDecimal maintenanceMargin;

    /**
     * 穿仓时账户权益（负值）
     */
    private BigDecimal accountEquity;

    // ==================== 处理信息 ====================

    /**
     * 处理方式
     * INSURANCE = 保险基金赔付
     * ADL = ADL处理
     * HYBRID = 混合（部分保险基金 + 部分ADL）
     * PENDING = 待处理
     */
    private String handleType;

    /**
     * 保险基金赔付金额
     */
    private BigDecimal insuranceCover;

    /**
     * ADL分摊金额
     */
    private BigDecimal adlCover;

    /**
     * 未覆盖金额（理论上应该为0）
     */
    private BigDecimal uncoveredAmount;

    /**
     * 处理状态
     * PENDING = 待处理
     * PROCESSING = 处理中
     * COMPLETED = 已完成
     * FAILED = 失败
     * PARTIAL = 部分完成
     */
    private String status;

    /**
     * 失败原因
     */
    private String failReason;

    /**
     * 重试次数
     */
    private Integer retryCount;

    // ==================== ADL相关 ====================

    /**
     * 触发的ADL批次数
     */
    private Integer adlBatchCount;

    /**
     * ADL影响的用户数
     */
    private Integer adlAffectedUsers;

    /**
     * ADL执行记录ID列表（逗号分隔）
     */
    private String adlExecutionIds;

    // ==================== 时间戳 ====================

    /**
     * 穿仓发生时间
     */
    private Long bankruptAt;

    /**
     * 开始处理时间
     */
    private Long processingAt;

    /**
     * 完成时间
     */
    private Long completedAt;

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
     * 是否需要ADL
     */
    public boolean needsAdl() {
        return insuranceCover == null ||
               bankruptLoss.compareTo(insuranceCover) > 0;
    }

    /**
     * 获取剩余需要ADL的金额
     */
    public BigDecimal getRemainingAdlAmount() {
        if (bankruptLoss == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal covered = insuranceCover != null ? insuranceCover : BigDecimal.ZERO;
        BigDecimal remaining = bankruptLoss.subtract(covered);
        return remaining.max(BigDecimal.ZERO);
    }

    /**
     * 是否处理完成
     */
    public boolean isCompleted() {
        return "COMPLETED".equalsIgnoreCase(status);
    }

    /**
     * 是否处理中
     */
    public boolean isProcessing() {
        return "PROCESSING".equalsIgnoreCase(status);
    }

    /**
     * 是否失败
     */
    public boolean isFailed() {
        return "FAILED".equalsIgnoreCase(status);
    }

    /**
     * 计算保险基金覆盖率
     */
    public BigDecimal getInsuranceCoverageRatio() {
        if (bankruptLoss == null || bankruptLoss.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal covered = insuranceCover != null ? insuranceCover : BigDecimal.ZERO;
        return covered.divide(bankruptLoss, 4, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * 更新处理进度
     */
    public void updateProgress(BigDecimal insuranceAmount, BigDecimal adlAmount) {
        this.insuranceCover = insuranceAmount;
        this.adlCover = adlAmount;

        BigDecimal totalCovered = insuranceAmount.add(adlAmount);
        this.uncoveredAmount = bankruptLoss.subtract(totalCovered);

        // 更新处理方式
        if (adlAmount.compareTo(BigDecimal.ZERO) > 0 && insuranceAmount.compareTo(BigDecimal.ZERO) > 0) {
            this.handleType = "HYBRID";
        } else if (adlAmount.compareTo(BigDecimal.ZERO) > 0) {
            this.handleType = "ADL";
        } else if (insuranceAmount.compareTo(BigDecimal.ZERO) > 0) {
            this.handleType = "INSURANCE";
        }

        // 更新状态
        if (uncoveredAmount.compareTo(BigDecimal.ZERO) <= 0) {
            this.status = "COMPLETED";
            this.completedAt = System.currentTimeMillis();
        } else if (totalCovered.compareTo(BigDecimal.ZERO) > 0) {
            this.status = "PARTIAL";
        }
    }
}
