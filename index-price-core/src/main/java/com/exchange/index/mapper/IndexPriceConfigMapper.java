package com.exchange.index.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.index.entity.IndexPriceConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 指数价格配置Mapper
 */
@Mapper
public interface IndexPriceConfigMapper extends BaseMapper<IndexPriceConfig> {

    /**
     * 查询启用的配置
     */
    @Select("SELECT * FROM t_index_price_config WHERE status = 1")
    List<IndexPriceConfig> selectActiveConfigs();

    /**
     * 根据symbol查询配置
     */
    @Select("SELECT * FROM t_index_price_config WHERE symbol = #{symbol} AND status = 1")
    IndexPriceConfig selectBySymbol(@Param("symbol") String symbol);
}
