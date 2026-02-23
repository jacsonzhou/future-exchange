package com.exchange.funding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.funding.entity.FundingRateHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 资金费率历史Mapper
 */
@Mapper
public interface FundingRateHistoryMapper extends BaseMapper<FundingRateHistory> {
    
    /**
     * 根据交易对和时间查询
     */
    @Select("SELECT * FROM t_funding_rate_history WHERE symbol = #{symbol} AND funding_time = #{fundingTime} LIMIT 1")
    FundingRateHistory selectBySymbolAndTime(@Param("symbol") String symbol, @Param("fundingTime") long fundingTime);
    
    /**
     * 查询交易对的历史记录
     */
    @Select("SELECT * FROM t_funding_rate_history WHERE symbol = #{symbol} ORDER BY funding_time DESC LIMIT #{limit}")
    List<FundingRateHistory> selectBySymbolLimit(@Param("symbol") String symbol, @Param("limit") int limit);
}
