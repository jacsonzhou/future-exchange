package com.exchange.adl.service;

import com.exchange.adl.entity.AdlRanking;

import java.util.List;

/**
 * ADL服务接口
 */
public interface AdlService {
    
    /**
     * 处理强平完成事件，检查是否需要ADL
     */
    void onLiquidationCompleted(String liquidationId, Long userId, String symbol, String side,
                                 Long bankruptPrice, Long bankruptQty, Long bankruptLoss);
    
    /**
     * 计算ADL排名队列
     */
    void calculateAdlRanking(String symbol, String side);
    
    /**
     * 获取ADL排名列表
     */
    List<AdlRanking> getAdlRankings(String symbol, String side, int limit);
    
    /**
     * 获取用户ADL排名
     */
    AdlRanking getUserAdlRank(Long userId, String symbol);
    
    /**
     * 执行ADL
     */
    void executeAdl(String symbol, String oppositeSide, Long requiredQty, String sourceLiquidationId, Long sourceUserId);
    
    /**
     * 获取保险基金余额
     */
    Long getInsuranceFundBalance(String symbol, String currency);
    
    /**
     * 检查是否在ADL危险区
     */
    boolean isInAdlZone(Long userId, String symbol);
}
