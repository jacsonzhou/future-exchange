package com.exchange.index.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.index.entity.IndexPrice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 指数价格Mapper
 */
@Mapper
public interface IndexPriceMapper extends BaseMapper<IndexPrice> {

    /**
     * 查询最新的指数价格
     */
    @Select("SELECT * FROM t_index_price WHERE symbol = #{symbol} ORDER BY timestamp DESC LIMIT 1")
    IndexPrice selectLatestBySymbol(@Param("symbol") String symbol);

    /**
     * 查询所有最新的指数价格
     */
    @Select("SELECT t1.* FROM t_index_price t1 " +
            "INNER JOIN (SELECT symbol, MAX(timestamp) as max_ts FROM t_index_price GROUP BY symbol) t2 " +
            "ON t1.symbol = t2.symbol AND t1.timestamp = t2.max_ts")
    List<IndexPrice> selectAllLatest();
}
