package com.exchange.funding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.funding.entity.FundingRateEstimate;
import org.apache.ibatis.annotations.Mapper;

/**
 * 预估资金费率Mapper
 */
@Mapper
public interface FundingRateEstimateMapper extends BaseMapper<FundingRateEstimate> {
}
