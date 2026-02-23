package com.exchange.liquidation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serializable;

/**
 * 强平执行记录实体
 * 
 * 对应数据库表: t_liquidation_execution
 * 
 * 记录每一次强平的完整生命周期，包括：
 * - 触发信息
 * - 订单执行
 * - 盈亏计算
 * - 保险基金赔付
 * - ADL触发标记
 */
@Data
@TableName("t_liquidation_execution")
public class LiquidationExecution implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 强平ID (业务唯一键)
     * 格式: LIQ_{timestamp}_{positionId}
     */
    private String liquidationId;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 仓位ID
     */
    private Long positionId;
    
    /**
     * 交易对
     */
    private String symbol;
    
    /**
     * 保证金模式: ISOLATED(逐仓)/CROSS(全仓)
     */
    private String marginMode;
    
    /**
     * 仓位方向: 1=多仓(LONG), 2=空仓(SHORT)
     */
    private Integer positionSide;
    
    // ==================== 触发信息 ====================
    
    /**
     * 触发类型: MARGIN_RATIO/MARK_PRICE
     */
    private String triggerType;
    
    /**
     * 触发价格 (8位小数)
     */
    private Long triggerPrice;
    
    /**
     * 触发时保证金率 (万分比，1000=10%)
     */
    private Long marginRatio;
    
    // ==================== 订单信息 ====================
    
    /**
     * OMS订单ID
     */
    private Long orderId;
    
    /**
     * 订单类型: MARKET/LIMIT
     */
    private String orderType;
    
    /**
     * 订单方向: BUY/SELL
     */
    private String side;
    
    /**
     * 订单数量 (8位小数)
     */
    private Long quantity;
    
    /**
     * 成交价格 (8位小数)
     */
    private Long executedPrice;
    
    /**
     * 成交数量 (8位小数)
     */
    private Long executedQty;
    
    // ==================== 价格信息 ====================
    
    /**
     * 开仓均价 (8位小数)
     */
    private Long entryPrice;
    
    /**
     * 破产价格 (8位小数)
     */
    private Long bankruptcyPrice;
    
    /**
     * 标记价格 (8位小数)
     */
    private Long markPrice;
    
    // ==================== 盈亏计算 ====================
    
    /**
     * 已实现盈亏 (8位小数，负数为亏损)
     */
    private Long realizedPnl;
    
    /**
     * 穿仓损失 (8位小数，正数表示损失金额)
     */
    private Long bankruptLoss;
    
    /**
     * 初始保证金 (8位小数)
     */
    private Long initialMargin;
    
    /**
     * 维持保证金 (8位小数)
     */
    private Long maintenanceMargin;
    
    // ==================== 保险基金 ====================
    
    /**
     * 保险基金赔付 (8位小数)
     */
    private Long insuranceCover;
    
    /**
     * 剩余穿仓损失 (8位小数)
     */
    private Long remainingLoss;
    
    /**
     * 是否需要ADL: 0=否, 1=是
     */
    private Integer adlRequired;

    // ==================== 部分成交支持 ====================

    /**
     * 剩余仓位数量 (8位小数)
     * 部分成交后，记录未成交的数量
     */
    private Long remainingQty;

    /**
     * 剩余仓位订单ID
     * 部分成交后创建的新订单ID
     */
    private Long remainingOrderId;

    /**
     * 部分成交累计盈亏 (8位小数)
     * 用于增量计算，每次部分成交时累加
     */
    private Long partialPnl;

    /**
     * 部分成交累计穿仓损失 (8位小数)
     * 用于增量计算，每次部分成交时累加
     */
    private Long partialBankruptLoss;

    /**
     * 父强平ID
     * 如果是剩余仓位订单，关联原始强平ID
     * 用于追溯完整的强平链路
     */
    private String parentLiquidationId;

    // ==================== 状态信息 ====================
    
    /**
     * 状态:
     * PENDING - 待处理
     * VALIDATING - 校验中
     * ORDER_CREATING - 创建订单中
     * SUBMITTED - 已提交
     * PARTIALLY_FILLED - 部分成交
     * FILLED - 完全成交
     * INSURANCE_PROCESSING - 保险基金处理中
     * ADL_TRIGGERED - 已触发ADL
     * COMPLETED - 完成
     * FAILED - 失败
     * CANCELLED - 已取消
     */
    private String status;
    
    /**
     * 重试次数
     */
    private Integer retryCount;
    
    /**
     * 错误信息
     */
    private String errorMsg;
    
    // ==================== 时间戳 ====================
    
    /**
     * 触发时间 (毫秒时间戳)
     */
    private Long triggeredAt;
    
    /**
     * 校验完成时间
     */
    private Long validatedAt;
    
    /**
     * 订单提交时间
     */
    private Long submittedAt;
    
    /**
     * 完全成交时间
     */
    private Long filledAt;
    
    /**
     * 完成时间
     */
    private Long completedAt;
    
    /**
     * 创建时间
     */
    private Long createdAt;
    
    /**
     * 更新时间
     */
    private Long updatedAt;

    /**
     * 版本号 (乐观锁)
     *
     * 用于防止并发更新冲突
     * MyBatis-Plus会自动管理：更新时检查版本号，成功后version+1
     */
    @Version
    private Long version;

    // ==================== 辅助方法 ====================
    
    /**
     * 计算穿仓损失
     */
    public void calculateBankruptLoss() {
        if (this.realizedPnl != null && this.initialMargin != null) {
            long total = this.realizedPnl + this.initialMargin;
            this.bankruptLoss = total < 0 ? -total : 0;
        }
    }
    
    /**
     * 检查是否需要ADL
     */
    public boolean needsAdl() {
        if (this.remainingLoss != null && this.remainingLoss > 0) {
            this.adlRequired = 1;
            return true;
        }
        this.adlRequired = 0;
        return false;
    }
    
    /**
     * 是否完全成交
     */
    public boolean isFullyFilled() {
        return "FILLED".equals(this.status) ||
               (this.executedQty != null && this.executedQty.equals(this.quantity));
    }

    /**
     * 是否部分成交
     */
    public boolean isPartiallyFilled() {
        return "PARTIALLY_FILLED".equals(this.status) ||
               (this.executedQty != null && this.quantity != null &&
                this.executedQty > 0 && this.executedQty < this.quantity);
    }

    /**
     * 计算剩余数量
     */
    public Long calculateRemainingQty() {
        if (this.executedQty != null && this.quantity != null) {
            return this.quantity - this.executedQty;
        }
        return 0L;
    }
    
    /**
     * 是否可以重试
     */
    public boolean canRetry(int maxRetry) {
        return this.retryCount < maxRetry && 
               ("FAILED".equals(this.status) || "PENDING".equals(this.status));
    }
}
