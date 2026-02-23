package com.exchange.funding.service;

import com.exchange.funding.entity.FundingRateHistory;
import com.exchange.funding.entity.UserFundingFee;

import java.util.List;

/**
 * 资金费率服务接口
 */
public interface FundingRateService {
    
    /**
     * 计算并保存资金费率
     * 
     * @param symbol 交易对
     * @param fundingTime 结算时间
     * @return 计算后的资金费率
     */
    FundingRateHistory calculateFundingRate(String symbol, long fundingTime);
    
    /**
     * 执行资金费用结算
     * 
     * @param symbol 交易对
     * @param fundingTime 结算时间
     */
    void settleFundingFee(String symbol, long fundingTime);
    
    /**
     * 获取资金费率历史
     * 
     * @param symbol 交易对
     * @param limit 限制条数
     * @return 历史记录列表
     */
    List<FundingRateHistory> getFundingRateHistory(String symbol, int limit);
    
    /**
     * 获取预估资金费率
     * 
     * @param symbol 交易对
     * @return 预估费率
     */
    Long getEstimatedFundingRate(String symbol);
    
    /**
     * 获取用户资金费用记录
     * 
     * @param userId 用户ID
     * @param symbol 交易对(可选)
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 费用记录列表
     */
    List<UserFundingFee> getUserFundingFees(Long userId, String symbol, Long startTime, Long endTime);
    
    /**
     * 获取下次结算时间
     *
     * @param symbol 交易对
     * @return 下次结算时间戳
     */
    Long getNextFundingTime(String symbol);

    /**
     * 更新预估资金费率
     *
     * @param symbol 交易对
     * @param estimatedRate 预估费率
     * @param nextFundingTime 下次结算时间
     */
    void updateEstimatedRate(String symbol, Long estimatedRate, Long nextFundingTime);
}
