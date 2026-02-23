package com.exchange.marketmaker.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 做市商考核指标实体
 * 
 * 对应表：t_mm_performance
 * 存储做市商每日/每周期在各交易对的考核表现数据
 */
@Data
@TableName("t_mm_performance")
public class MmPerformance {
    
    /**
     * 自增主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 用户ID（做市商用户ID）
     */
    private Long userId;
    
    /**
     * 交易对（如：BTCUSDT）
     */
    private String symbol;
    
    /**
     * 考核周期日期（yyyyMMdd格式）
     */
    private Integer periodDate;
    
    /**
     * 考核周期类型（1-日，2-周，3-月）
     */
    private Integer periodType;
    
    /**
     * 挂单时长（秒）
     */
    private Long quoteDuration;
    
    /**
     * 市场总时长（秒）
     */
    private Long marketDuration;
    
    /**
     * 挂单时间占比（8位精度，如0.85000000表示85%）
     */
    private Long quoteTimeRatio;
    
    /**
     * 平均买卖价差（8位精度，相对于中间价的百分比）
     */
    private Long avgSpread;
    
    /**
     * 最小价差（8位精度）
     */
    private Long minSpread;
    
    /**
     * 最大价差（8位精度）
     */
    private Long maxSpread;
    
    /**
     * 平均挂单深度（USDT，8位精度）
     */
    private Long avgDepth;
    
    /**
     * 最小挂单深度（USDT）
     */
    private Long minDepth;
    
    /**
     * 订单成交率（8位精度，成交订单数/总订单数）
     */
    private Long fillRate;
    
    /**
     * 总下单数量
     */
    private Integer totalOrders;
    
    /**
     * 已成交订单数量
     */
    private Integer filledOrders;
    
    /**
     * Maker成交量（标的资产数量）
     */
    private Long makerVolume;
    
    /**
     * Taker成交量（标的资产数量）
     */
    private Long takerVolume;
    
    /**
     * 总成交量（标的资产数量）
     */
    private Long totalVolume;
    
    /**
     * 订单簿偏离次数（买卖价差超过阈值次数）
     */
    private Integer spreadViolationCount;
    
    /**
     * 深度不足次数
     */
    private Integer depthViolationCount;
    
    /**
     * 连续报价中断最大时长（秒）
     */
    private Integer maxQuoteBreakSeconds;
    
    /**
     * 报价中断总次数
     */
    private Integer quoteBreakCount;
    
    /**
     * 挂单时间评分（0-100）
     */
    private Integer quoteTimeScore;
    
    /**
     * 价差评分（0-100）
     */
    private Integer spreadScore;
    
    /**
     * 深度评分（0-100）
     */
    private Integer depthScore;
    
    /**
     * 稳定性评分（0-100）
     */
    private Integer stabilityScore;
    
    /**
     * 成交量评分（0-100）
     */
    private Integer volumeScore;
    
    /**
     * 综合评分（0-100）
     */
    private Integer totalScore;
    
    /**
     * 是否达标（0-不达标，1-达标，2-优秀）
     */
    private Integer isQualified;
    
    /**
     * 结算状态（0-未结算，1-已结算）
     */
    private Integer settlementStatus;
    
    /**
     * 奖励金额（USDT）
     */
    private Long rewardAmount;
    
    /**
     * 返还手续费金额（USDT）
     */
    private Long rebateAmount;
    
    /**
     * 备注说明
     */
    private String remark;
    
    /**
     * 创建时间
     */
    private Long createTime;
    
    /**
     * 更新时间
     */
    private Long updateTime;
}
