package com.exchange.adl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * ADL Ranking Queue - ADL排名队列实体
 * 
 * 🔥 核心职责：
 * 1. 维护每个用户的ADL排名信息
 * 2. 按ADL Score排序，确定减仓优先级
 * 3. 每symbol独立队列
 * 
 * 🔥 ADL排名规则（对标Binance/OKX）：
 * 1. 盈利越多，排名越靠前（优先被ADL）
 * 2. 杠杆越高，排名越靠前
 * 3. 同分情况下按时间戳排序（先开仓者优先）
 * 
 * 🔥 ADL Score计算公式：
 * adlScore = (abs(unrealizedPnl) / positionValue) * effectiveLeverage * 10000
 * 
 * 其中：
 * - positionValue = abs(positionSize) * markPrice
 * - effectiveLeverage = positionValue / marginBalance
 * - pnlRatio = unrealizedPnl / marginBalance
 */
@Data
@TableName("adl_ranking_queue")
public class AdlRankingQueue {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    // ==================== 基本信息 ====================
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 交易对（symbol）
     * 每symbol独立ADL队列
     */
    private String symbol;
    
    /**
     * 持仓ID
     */
    private Long positionId;
    
    // ==================== 持仓信息 ====================
    
    /**
     * 持仓方向
     * LONG = 多头
     * SHORT = 空头
     */
    private String side;
    
    /**
     * 持仓数量（绝对值）
     */
    private BigDecimal positionSize;
    
    /**
     * 持仓均价
     */
    private BigDecimal entryPrice;
    
    /**
     * 标记价格（用于计算ADL排名）
     */
    private BigDecimal markPrice;
    
    // ==================== 保证金信息 ====================
    
    /**
     * 保证金余额
     */
    private BigDecimal marginBalance;
    
    /**
     * 维持保证金
     */
    private BigDecimal maintenanceMargin;
    
    /**
     * 实际杠杆倍数
     * effectiveLeverage = positionValue / marginBalance
     */
    private BigDecimal effectiveLeverage;
    
    // ==================== 盈亏信息 ====================
    
    /**
     * 未实现盈亏
     */
    private BigDecimal unrealizedPnl;
    
    /**
     * 盈亏比例（PnL Ratio）
     * pnlRatio = unrealizedPnl / marginBalance
     */
    private BigDecimal pnlRatio;
    
    // ==================== ADL排名信息 ====================
    
    /**
     * ADL得分（核心排序字段）
     * 得分越高，排名越靠前，越优先被ADL
     * 
     * 计算逻辑：
     * - 盈利仓位：adlScore = pnlRatio * effectiveLeverage * 10000
     * - 亏损仓位：adlScore = 0（不会被ADL）
     */
    private BigDecimal adlScore;
    
    /**
     * ADL排名（1开始，1为最高优先级）
     * 每symbol独立排名，定时刷新
     */
    private Integer adlRank;
    
    /**
     * 风险等级（1-5）
     * 1 = 低风险（盈利少，杠杆低）
     * 5 = 高风险（盈利多，杠杆高，最优先被ADL）
     */
    private Integer riskLevel;
    
    // ==================== 时间戳 ====================
    
    /**
     * 持仓创建时间（用于同分排序）
     */
    private Long positionCreatedAt;
    
    /**
     * 排名最后更新时间
     */
    private Long rankUpdatedAt;
    
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
     * 是否多头
     */
    public boolean isLong() {
        return "LONG".equalsIgnoreCase(side);
    }
    
    /**
     * 是否空头
     */
    public boolean isShort() {
        return "SHORT".equalsIgnoreCase(side);
    }
    
    /**
     * 是否有持仓
     */
    public boolean hasPosition() {
        return positionSize != null && positionSize.compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * 是否盈利
     */
    public boolean isProfitable() {
        return unrealizedPnl != null && unrealizedPnl.compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * 是否会被ADL（盈利仓位才会被选）
     */
    public boolean isAdlCandidate() {
        return isProfitable() && hasPosition() && adlScore != null && adlScore.compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * 持仓名义价值
     */
    public BigDecimal getPositionValue() {
        if (positionSize == null || markPrice == null) {
            return BigDecimal.ZERO;
        }
        return positionSize.multiply(markPrice);
    }
    
    /**
     * 计算ADL得分
     */
    public void calculateAdlScore() {
        if (!isProfitable() || marginBalance == null || marginBalance.compareTo(BigDecimal.ZERO) == 0) {
            this.adlScore = BigDecimal.ZERO;
            this.riskLevel = 1;
            return;
        }
        
        // 盈亏比例
        BigDecimal pnlRatio = unrealizedPnl.divide(marginBalance, 8, BigDecimal.ROUND_HALF_UP);
        
        // 实际杠杆
        BigDecimal positionValue = getPositionValue();
        BigDecimal leverage = effectiveLeverage != null ? effectiveLeverage : 
            (marginBalance.compareTo(BigDecimal.ZERO) > 0 ? 
                positionValue.divide(marginBalance, 8, BigDecimal.ROUND_HALF_UP) : BigDecimal.ZERO);
        
        // ADL Score = PnL Ratio × Leverage × 10000
        this.adlScore = pnlRatio.multiply(leverage).multiply(new BigDecimal("10000"));
        
        // 计算风险等级（1-5）
        calculateRiskLevel();
    }
    
    /**
     * 计算风险等级
     */
    private void calculateRiskLevel() {
        if (adlScore == null || adlScore.compareTo(BigDecimal.ZERO) <= 0) {
            this.riskLevel = 1;
            return;
        }
        
        // 根据得分划分风险等级
        BigDecimal score = adlScore;
        if (score.compareTo(new BigDecimal("5000")) >= 0) {
            this.riskLevel = 5; // 最高风险
        } else if (score.compareTo(new BigDecimal("3000")) >= 0) {
            this.riskLevel = 4;
        } else if (score.compareTo(new BigDecimal("1500")) >= 0) {
            this.riskLevel = 3;
        } else if (score.compareTo(new BigDecimal("500")) >= 0) {
            this.riskLevel = 2;
        } else {
            this.riskLevel = 1; // 最低风险
        }
    }
}
