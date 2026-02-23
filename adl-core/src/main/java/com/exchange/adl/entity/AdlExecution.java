package com.exchange.adl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * ADL Execution Record - ADL执行记录实体
 * 
 * 🔥 核心职责：
 * 1. 记录每次ADL执行的详细信息
 * 2. 用于审计、对账和纠纷处理
 * 3. 支持重放和回滚
 * 
 * 🔥 ADL执行流程：
 * 1. 强平引擎触发ADL请求
 * 2. 选择ADL排名最高的盈利仓位
 * 3. 执行减仓（与强平仓位成交）
 * 4. 记录执行结果
 * 5. 更新双方持仓和账本
 * 
 * 🔥 字段说明：
 * - targetUserId: 被ADL的用户（盈利方）
 * - sourceUserId: 触发ADL的用户（被强平方）
 * - adlPrice: ADL执行价格
 * - adlQty: ADL执行数量
 * - targetPnl: 被ADL用户的盈亏变化
 */
@Data
@TableName("adl_execution")
public class AdlExecution {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    // ==================== 执行标识 ====================
    
    /**
     * ADL执行ID（全局唯一）
     * 格式：ADL_{timestamp}_{sequence}
     */
    private String adlExecutionId;
    
    /**
     * 关联的强平事件ID
     */
    private String liquidationId;
    
    /**
     * 关联的成交ID
     */
    private String tradeId;
    
    // ==================== 交易对信息 ====================
    
    /**
     * 交易对
     */
    private String symbol;
    
    // ==================== 被ADL方（Target - 盈利方）====================
    
    /**
     * 被ADL用户ID（盈利仓位持有者）
     */
    private Long targetUserId;
    
    /**
     * 被ADL持仓ID
     */
    private Long targetPositionId;
    
    /**
     * 被ADL方原持仓方向
     */
    private String targetSide;
    
    /**
     * 被ADL方原持仓数量
     */
    private BigDecimal targetPositionSizeBefore;
    
    /**
     * 被ADL方ADL后持仓数量
     */
    private BigDecimal targetPositionSizeAfter;
    
    /**
     * 被ADL方原未实现盈亏
     */
    private BigDecimal targetUnrealizedPnlBefore;
    
    /**
     * 被ADL方原已实现盈亏
     */
    private BigDecimal targetRealizedPnlBefore;
    
    /**
     * 被ADL方ADL后已实现盈亏
     */
    private BigDecimal targetRealizedPnlAfter;
    
    /**
     * 被ADL方ADL导致的盈亏变化
     * 正值 = 盈利增加，负值 = 盈利减少
     */
    private BigDecimal targetPnlChange;
    
    /**
     * 被ADL方ADL时的ADL排名
     */
    private Integer targetAdlRank;
    
    /**
     * 被ADL方ADL时的ADL得分
     */
    private BigDecimal targetAdlScore;
    
    // ==================== 触发ADL方（Source - 被强平方）====================
    
    /**
     * 触发ADL用户ID（被强平用户）
     */
    private Long sourceUserId;
    
    /**
     * 触发ADL方原持仓方向
     */
    private String sourceSide;
    
    /**
     * 触发ADL方原持仓数量
     */
    private BigDecimal sourcePositionSizeBefore;
    
    /**
     * 触发ADL方剩余未平仓数量
     */
    private BigDecimal sourceRemainingSize;
    
    // ==================== ADL执行详情 ====================
    
    /**
     * ADL执行价格
     * 通常等于当时的标记价格
     */
    private BigDecimal adlPrice;
    
    /**
     * ADL执行数量
     */
    private BigDecimal adlQty;
    
    /**
     * ADL执行金额（名义价值）
     * adlValue = adlQty * adlPrice
     */
    private BigDecimal adlValue;
    
    /**
     * 是否完全平仓
     * true = 被ADL方完全平仓
     * false = 部分减仓
     */
    private Boolean isFullyClosed;
    
    /**
     * 执行顺序（同一liquidationId下的第几次ADL）
     */
    private Integer executionSequence;
    
    // ==================== 保险基金相关信息 ====================
    
    /**
     * 本次ADL是否涉及保险基金赔付
     */
    private Boolean insuranceFundInvolved;
    
    /**
     * 保险基金赔付金额（如有）
     */
    private BigDecimal insuranceFundCompensation;
    
    /**
     * ADL前保险基金余额
     */
    private BigDecimal insuranceFundBalanceBefore;
    
    /**
     * ADL后保险基金余额
     */
    private BigDecimal insuranceFundBalanceAfter;
    
    // ==================== 状态信息 ====================
    
    /**
     * ADL执行状态
     * PENDING = 待处理
     * PROCESSING = 处理中
     * SUCCESS = 成功
     * FAILED = 失败
     * ROLLED_BACK = 已回滚
     */
    private String status;
    
    /**
     * 失败原因（如失败）
     */
    private String failReason;
    
    /**
     * 重试次数
     */
    private Integer retryCount;
    
    // ==================== 时间戳 ====================
    
    /**
     * 执行触发时间
     */
    private Long triggeredAt;
    
    /**
     * 开始执行时间
     */
    private Long executedAt;
    
    /**
     * 执行完成时间
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
    
    // ==================== 幂等性字段 ====================
    
    /**
     * 业务序列号（幂等性）
     * 格式：ADL_{liquidationId}_{targetUserId}_{executionSequence}
     */
    private String bizSeq;
    
    // ==================== 辅助方法 ====================
    
    /**
     * 是否成功
     */
    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(status);
    }
    
    /**
     * 是否失败
     */
    public boolean isFailed() {
        return "FAILED".equalsIgnoreCase(status);
    }
    
    /**
     * 是否处理中
     */
    public boolean isProcessing() {
        return "PROCESSING".equalsIgnoreCase(status);
    }
    
    /**
     * 计算ADL执行金额
     */
    public void calculateAdlValue() {
        if (adlQty != null && adlPrice != null) {
            this.adlValue = adlQty.multiply(adlPrice);
        }
    }
    
    /**
     * 获取执行耗时（毫秒）
     */
    public Long getExecutionTimeMs() {
        if (executedAt != null && completedAt != null) {
            return completedAt - executedAt;
        }
        return null;
    }
}
