package com.exchange.marketmaker.service;

import com.exchange.marketmaker.entity.MarketMaker;
import com.exchange.marketmaker.entity.MmPerformance;

import java.util.List;

/**
 * 做市商服务接口
 * 
 * 职责：
 * - 做市商信息管理（申请、审核、等级调整）
 * - 做市商考核指标计算
 * - 做市商费率配置
 * - 奖励结算
 */
public interface MarketMakerService {
    
    /**
     * 申请成为做市商
     * 
     * @param marketMaker 做市商信息
     * @return 申请结果
     */
    MarketMaker applyMarketMaker(MarketMaker marketMaker);
    
    /**
     * 审核做市商申请
     * 
     * @param userId 用户ID
     * @param approved 是否通过
     * @param level 分配的等级
     * @return 审核后的做市商信息
     */
    MarketMaker approveMarketMaker(Long userId, boolean approved, Integer level);
    
    /**
     * 获取做市商信息
     * 
     * @param userId 用户ID
     * @return 做市商信息
     */
    MarketMaker getMarketMaker(Long userId);
    
    /**
     * 更新做市商状态
     * 
     * @param userId 用户ID
     * @param newStatus 新状态
     * @return 更新结果
     */
    boolean updateStatus(Long userId, Integer newStatus);
    
    /**
     * 调整做市商等级
     * 
     * @param userId 用户ID
     * @param newLevel 新等级
     * @return 更新后的做市商信息
     */
    MarketMaker adjustLevel(Long userId, Integer newLevel);
    
    /**
     * 获取所有活跃做市商
     * 
     * @return 做市商列表
     */
    List<MarketMaker> getAllActiveMakers();
    
    /**
     * 获取交易对的做市商列表
     * 
     * @param symbol 交易对
     * @return 做市商列表
     */
    List<MarketMaker> getMakersBySymbol(String symbol);
    
    /**
     * 计算并保存考核指标
     * 
     * @param userId 用户ID
     * @param symbol 交易对
     * @param periodDate 考核日期
     * @return 考核指标
     */
    MmPerformance calculatePerformance(Long userId, String symbol, Integer periodDate);
    
    /**
     * 获取做市商考核指标
     * 
     * @param userId 用户ID
     * @param symbol 交易对
     * @param periodDate 考核日期
     * @return 考核指标
     */
    MmPerformance getPerformance(Long userId, String symbol, Integer periodDate);
    
    /**
     * 获取做市商历史考核记录
     * 
     * @param userId 用户ID
     * @param limit 查询条数
     * @return 考核记录列表
     */
    List<MmPerformance> getPerformanceHistory(Long userId, Integer limit);
    
    /**
     * 结算做市商奖励
     * 
     * @param userId 用户ID
     * @param periodDate 考核日期
     * @return 结算金额
     */
    Long settleReward(Long userId, Integer periodDate);
    
    /**
     * 检查做市商是否达标
     * 
     * @param userId 用户ID
     * @param symbol 交易对
     * @param periodDate 考核日期
     * @return 达标状态（0-不达标，1-达标，2-优秀）
     */
    Integer checkQualification(Long userId, String symbol, Integer periodDate);
    
    /**
     * 更新做市商费率
     * 
     * @param userId 用户ID
     * @param makerFeeRate Maker费率
     * @param takerFeeRate Taker费率
     * @return 更新结果
     */
    boolean updateFeeRate(Long userId, Long makerFeeRate, Long takerFeeRate);
    
    /**
     * 批量计算日终考核指标
     * 
     * @param periodDate 考核日期
     * @return 处理数量
     */
    int batchCalculateDailyPerformance(Integer periodDate);
}
