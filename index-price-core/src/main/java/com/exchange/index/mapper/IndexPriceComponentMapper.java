package com.exchange.index.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.index.entity.IndexPriceComponent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 指数价格成分Mapper
 */
@Mapper
public interface IndexPriceComponentMapper extends BaseMapper<IndexPriceComponent> {

    /**
     * 查询指定时间窗口的成分数据
     */
    @Select("SELECT * FROM t_index_price_component " +
            "WHERE symbol = #{symbol} AND timestamp >= #{startTime} AND timestamp <= #{endTime} " +
            "ORDER BY timestamp DESC")
    List<IndexPriceComponent> selectByTimeWindow(@Param("symbol") String symbol,
                                                  @Param("startTime") Long startTime,
                                                  @Param("endTime") Long endTime);
}
