package com.exchange.adl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.adl.entity.AdlRanking;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * ADL排名Mapper
 */
@Mapper
public interface AdlRankingMapper extends BaseMapper<AdlRanking> {
    
    /**
     * 根据symbol和side查询ADL排名
     */
    @Select("SELECT * FROM t_adl_ranking_queue WHERE symbol = #{symbol} AND side = #{side} ORDER BY adl_rank ASC LIMIT #{limit}")
    List<AdlRanking> selectBySymbolAndSide(@Param("symbol") String symbol, 
                                           @Param("side") String side, 
                                           @Param("limit") int limit);
    
    /**
     * 根据用户和symbol查询
     */
    @Select("SELECT * FROM t_adl_ranking_queue WHERE user_id = #{userId} AND symbol = #{symbol} LIMIT 1")
    AdlRanking selectByUserAndSymbol(@Param("userId") Long userId, @Param("symbol") String symbol);
}
