package com.exchange.marketmaker.service;

import com.exchange.marketmaker.dto.response.FeeLogResponse;
import com.exchange.marketmaker.entity.MmFeeLog;

/**
 * 做市商费率服务接口
 */
public interface MmFeeService {

    /**
     * 记录费率流水
     */
    void recordFeeLog(MmFeeLog feeLog);

    /**
     * 查询费率优惠记录
     */
    FeeLogResponse queryFeeLog(Long userId, String startTime, String endTime);

    /**
     * 计算做市商费率
     */
    Long calculateMakerFeeRate(Long userId);

    /**
     * 计算做市商Taker费率
     */
    Long calculateTakerFeeRate(Long userId);
}
