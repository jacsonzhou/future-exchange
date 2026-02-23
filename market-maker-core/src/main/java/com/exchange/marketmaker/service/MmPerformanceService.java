package com.exchange.marketmaker.service;

import com.exchange.marketmaker.dto.response.MmPerformanceResponse;
import com.exchange.marketmaker.entity.MmPerformance;

import java.util.List;

/**
 * 做市商考核服务接口
 */
public interface MmPerformanceService {

    /**
     * 计算并保存每日考核指标
     */
    MmPerformance calculateDailyPerformance(Long userId, String symbol, String periodDate);

    /**
     * 查询考核指标
     */
    MmPerformanceResponse getPerformance(Long userId, String symbol, String startDate, String endDate);

    /**
     * 批量计算日终考核指标
     */
    Integer batchCalculateDailyPerformance(String periodDate);

    /**
     * 获取做市商历史考核记录
     */
    List<MmPerformance> getPerformanceHistory(Long userId, Integer limit);
}
