package com.exchange.marketmaker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.marketmaker.entity.MarketMakerApplication;
import org.apache.ibatis.annotations.Mapper;

/**
 * 做市商申请Mapper
 */
@Mapper
public interface MarketMakerApplicationMapper extends BaseMapper<MarketMakerApplication> {
}
