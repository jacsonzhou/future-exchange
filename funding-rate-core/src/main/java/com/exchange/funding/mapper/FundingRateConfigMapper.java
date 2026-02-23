package com.exchange.funding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.funding.entity.FundingRateConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 资金费率配置Mapper
 */
@Mapper
public interface FundingRateConfigMapper extends BaseMapper<FundingRateConfig> {
    
    /**
     * 根据交易对查询配置
     */
    @Select("SELECT * FROM t_funding_rate_config WHERE symbol = #{symbol} LIMIT 1")
    FundingRateConfig selectBySymbol(@Param("symbol") String symbol);
    
    /**
     * 查询所有启用的配置
     */
    @Select("SELECT * FROM t_funding_rate_config WHERE status = 1")
    List<FundingRateConfig> selectAllActive();
}
