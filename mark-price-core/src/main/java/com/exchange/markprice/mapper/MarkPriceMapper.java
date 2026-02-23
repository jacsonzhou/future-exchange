package com.exchange.markprice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.markprice.entity.MarkPrice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 标记价格Mapper
 */
@Mapper
public interface MarkPriceMapper extends BaseMapper<MarkPrice> {

    /**
     * 查询最新的标记价格
     */
    @Select("SELECT * FROM t_mark_price WHERE symbol = #{symbol} ORDER BY timestamp DESC LIMIT 1")
    MarkPrice selectLatestBySymbol(@Param("symbol") String symbol);

    /**
     * 查询所有最新的标记价格
     */
    @Select("SELECT t1.* FROM t_mark_price t1 " +
            "INNER JOIN (SELECT symbol, MAX(timestamp) as max_ts FROM t_mark_price GROUP BY symbol) t2 " +
            "ON t1.symbol = t2.symbol AND t1.timestamp = t2.max_ts")
    List<MarkPrice> selectAllLatest();
}
