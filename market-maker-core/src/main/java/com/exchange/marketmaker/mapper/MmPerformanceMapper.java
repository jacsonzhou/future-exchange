package com.exchange.marketmaker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.marketmaker.entity.MmPerformance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 做市商考核指标Mapper
 */
@Mapper
public interface MmPerformanceMapper extends BaseMapper<MmPerformance> {
    
    /**
     * 根据用户ID和日期查询考核记录
     */
    @Select("SELECT * FROM t_mm_performance WHERE user_id = #{userId} AND period_date = #{periodDate} AND period_type = #{periodType}")
    MmPerformance selectByUserIdAndDate(@Param("userId") Long userId,
                                        @Param("periodDate") Integer periodDate,
                                        @Param("periodType") Integer periodType);
    
    /**
     * 查询指定交易对的考核记录
     */
    @Select("SELECT * FROM t_mm_performance WHERE user_id = #{userId} AND symbol = #{symbol} AND period_date = #{periodDate}")
    MmPerformance selectByUserSymbolAndDate(@Param("userId") Long userId,
                                            @Param("symbol") String symbol,
                                            @Param("periodDate") Integer periodDate);
    
    /**
     * 查询用户指定时间范围内的考核记录
     */
    @Select("SELECT * FROM t_mm_performance WHERE user_id = #{userId} AND symbol = #{symbol} " +
            "AND period_date >= #{startDate} AND period_date <= #{endDate} AND period_type = #{periodType} " +
            "ORDER BY period_date DESC")
    List<MmPerformance> selectByDateRange(@Param("userId") Long userId,
                                          @Param("symbol") String symbol,
                                          @Param("startDate") Integer startDate,
                                          @Param("endDate") Integer endDate,
                                          @Param("periodType") Integer periodType);
    
    /**
     * 查询日期的所有做市商考核数据
     */
    @Select("SELECT * FROM t_mm_performance WHERE period_date = #{periodDate} AND period_type = #{periodType} " +
            "ORDER BY total_score DESC")
    List<MmPerformance> selectByPeriodDate(@Param("periodDate") Integer periodDate,
                                           @Param("periodType") Integer periodType);
    
    /**
     * 查询日期的所有做市商考核数据（按交易对筛选）
     */
    @Select("SELECT * FROM t_mm_performance WHERE period_date = #{periodDate} AND symbol = #{symbol} " +
            "AND period_type = #{periodType} ORDER BY total_score DESC")
    List<MmPerformance> selectByPeriodDateAndSymbol(@Param("periodDate") Integer periodDate,
                                                    @Param("symbol") String symbol,
                                                    @Param("periodType") Integer periodType);
    
    /**
     * 更新考核评分
     */
    @Update("UPDATE t_mm_performance SET quote_time_score = #{quoteTimeScore}, spread_score = #{spreadScore}, " +
            "depth_score = #{depthScore}, stability_score = #{stabilityScore}, volume_score = #{volumeScore}, " +
            "total_score = #{totalScore}, is_qualified = #{isQualified}, update_time = #{updateTime} " +
            "WHERE id = #{id}")
    int updateScores(@Param("id") Long id,
                     @Param("quoteTimeScore") Integer quoteTimeScore,
                     @Param("spreadScore") Integer spreadScore,
                     @Param("depthScore") Integer depthScore,
                     @Param("stabilityScore") Integer stabilityScore,
                     @Param("volumeScore") Integer volumeScore,
                     @Param("totalScore") Integer totalScore,
                     @Param("isQualified") Integer isQualified,
                     @Param("updateTime") Long updateTime);
    
    /**
     * 更新结算信息
     */
    @Update("UPDATE t_mm_performance SET settlement_status = 1, reward_amount = #{rewardAmount}, " +
            "rebate_amount = #{rebateAmount}, update_time = #{updateTime} WHERE id = #{id}")
    int updateSettlement(@Param("id") Long id,
                         @Param("rewardAmount") Long rewardAmount,
                         @Param("rebateAmount") Long rebateAmount,
                         @Param("updateTime") Long updateTime);
    
    /**
     * 统计指定日期的达标做市商数量
     */
    @Select("SELECT COUNT(*) FROM t_mm_performance WHERE period_date = #{periodDate} " +
            "AND period_type = #{periodType} AND is_qualified IN (1, 2)")
    Long countQualifiedByDate(@Param("periodDate") Integer periodDate,
                              @Param("periodType") Integer periodType);
    
    /**
     * 查询用户最近一条考核记录
     */
    @Select("SELECT * FROM t_mm_performance WHERE user_id = #{userId} AND symbol = #{symbol} " +
            "ORDER BY period_date DESC LIMIT 1")
    MmPerformance selectLatestByUserAndSymbol(@Param("userId") Long userId,
                                              @Param("symbol") String symbol);
    
    /**
     * 批量更新结算状态
     */
    @Update("UPDATE t_mm_performance SET settlement_status = 1, update_time = #{updateTime} " +
            "WHERE period_date = #{periodDate} AND period_type = #{periodType} AND settlement_status = 0")
    int batchUpdateSettlementStatus(@Param("periodDate") Integer periodDate,
                                    @Param("periodType") Integer periodType,
                                    @Param("updateTime") Long updateTime);
}
