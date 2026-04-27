package com.exchange.position.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Position Snapshot 持仓快照（生产级 - 双向持仓模式Hedge Mode）
 * 
 * 🔥 核心变化（双向持仓模式）：
 * 1. 同一个用户可以在同一交易对上同时持有多头和空头
 * 2. 新增 positionSide 字段标识持仓方向（1=LONG, 2=SHORT）
 * 3. size 字段始终为正数，方向由 positionSide 决定
 * 4. 查询时需要同时指定 userId + symbol + positionSide
 * 
 * 🔥 核心职责：
 * 1. 维护持仓状态（size, entryPrice, positionSide）
 * 2. 计算未实现盈亏（unrealizedPnl）
 * 3. 计算保证金率（marginRatio）
 * 4. 计算强平价（liquidationPrice）
 * 
 * 对标：Binance Hedge Mode / OKX 双向持仓
 */
@Data
@TableName("position_snapshot")
public class PositionSnapshot {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 交易对
     */
    private String symbol;
    
    // ==================== 🔥 双向持仓模式核心字段 ====================
    
    /**
     * 持仓方向
     * 1 = LONG（多头）
     * 2 = SHORT（空头）
     * 
     * 🔥 双向持仓模式：同一个用户可以在同一交易对上同时持有多头和空头
     * 示例：用户1可以同时持有 BTCUSDT LONG 0.5 和 BTCUSDT SHORT 0.3
     */
    private Integer positionSide;
    
    // ==================== 🔥 持仓信息 ====================
    
    /**
     * 持仓数量（始终为正数）
     * 方向由 positionSide 字段决定
     * 0 = 该方向无持仓
     */
    private BigDecimal size;

    /**
     * 可平仓数量
     */
    private BigDecimal availableSize;

    /**
     * 持仓均价（开仓价）
     * 多次开仓时为加权平均价
     */
    private BigDecimal entryPrice;

    /**
     * 标记价格
     */
    private BigDecimal markPrice;

    /**
     * 指数价格
     */
    private BigDecimal indexPrice;

    // ==================== 🔥 盈亏信息 ====================

    /**
     * 未实现盈亏（浮动盈亏）
     *
     * 多头：(markPrice - entryPrice) * size
     * 空头：(entryPrice - markPrice) * abs(size)
     */
    private BigDecimal unrealizedPnl;

    /**
     * 已实现盈亏（累计）
     * 平仓时累加
     */
    private BigDecimal realizedPnl;

    /**
     * 盈亏比例
     */
    private BigDecimal pnlRatio;

    // ==================== 🔥 风控指标 ====================

    /**
     * 保证金率
     *
     * marginRatio = equity / maintenanceMargin
     *
     * 强平条件：marginRatio < 1.0
     */
    private BigDecimal marginRatio;

    /**
     * 强平价格
     *
     * 多头：entryPrice - (equity - maintenanceMargin) / size
     * 空头：entryPrice + (equity - maintenanceMargin) / abs(size)
     */
    private BigDecimal liquidationPrice;

    /**
     * 破产价格
     */
    private BigDecimal bankruptcyPrice;

    // ==================== 🔥 保证金信息 ====================

    /**
     * 仓位保证金
     */
    private BigDecimal positionMargin;

    /**
     * 维持保证金
     */
    private BigDecimal maintenanceMargin;

    /**
     * 维持保证金率
     */
    private BigDecimal maintenanceMarginRate;

    /**
     * 杠杆倍数
     */
    private Integer leverage;

    /**
     * 保证金模式: CROSS/ISOLATED
     */
    private String marginMode;

    /**
     * 风险等级: 1=SAFE 2=WARNING 3=DANGER 4=LIQUIDATION
     */
    private Integer riskLevel;

    // ==================== 🔥 自动减仓排名 ====================

    /**
     * ADL得分
     */
    private BigDecimal adlScore;

    /**
     * ADL排名
     */
    private Integer adlRank;
    
    // ==================== 🔥 同步位点（幂等性）====================
    
    /**
     * 最后处理的tradeId
     */
    private String lastTradeId;
    
    /**
     * 最后处理的markPriceId
     */
    private String lastMarkPriceId;
    
    /**
     * 最后更新序列号（全局递增）
     */
    private Long lastUpdateSeq;
    
    // ==================== 🔥 并发控制 ====================
    
    /**
     * 版本号（乐观锁）
     */
    private Integer version;
    
    // ==================== 时间戳 ====================
    
    /**
     * 创建时间
     */
    private Long createdAt;
    
    /**
     * 更新时间
     */
    private Long updatedAt;
    
    // ==================== 辅助方法（双向持仓模式）====================
    
    /**
     * 是否多头
     * 双向持仓模式：通过 positionSide 判断
     */
    public boolean isLong() {
        return positionSide != null && positionSide == 1;
    }
    
    /**
     * 是否空头
     * 双向持仓模式：通过 positionSide 判断
     */
    public boolean isShort() {
        return positionSide != null && positionSide == 2;
    }
    
    /**
     * 是否有持仓
     * 双向持仓模式：size > 0 表示该方向有持仓
     */
    public boolean hasPosition() {
        return size != null && size.compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * 获取持仓数量（带符号）
     * 多头返回正数，空头返回负数
     */
    public BigDecimal getSignedSize() {
        if (size == null) return BigDecimal.ZERO;
        return isLong() ? size : size.negate();
    }
    
    /**
     * 获取持仓绝对值
     */
    public BigDecimal getAbsSize() {
        return size != null ? size : BigDecimal.ZERO;
    }
    
    /**
     * 获取持仓方向名称
     */
    public String getPositionSideName() {
        if (isLong()) return "LONG";
        if (isShort()) return "SHORT";
        return "UNKNOWN";
    }
    
    /**
     * 是否触发强平
     */
    public boolean shouldLiquidate() {
        return marginRatio != null && marginRatio.compareTo(BigDecimal.ONE) < 0;
    }
    
    /**
     * 是否保证金预警
     */
    public boolean isMarginWarning() {
        return marginRatio != null && marginRatio.compareTo(new BigDecimal("1.2")) < 0;
    }
}

