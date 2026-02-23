package com.exchange.marketmaker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.marketmaker.entity.MarketMaker;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 做市商信息Mapper
 */
@Mapper
public interface MarketMakerMapper extends BaseMapper<MarketMaker> {
    
    /**
     * 根据用户ID查询做市商信息
     */
    @Select("SELECT * FROM t_market_maker WHERE user_id = #{userId}")
    MarketMaker selectByUserId(@Param("userId") Long userId);
    
    /**
     * 根据状态查询做市商列表
     */
    @Select("SELECT * FROM t_market_maker WHERE status = #{status} ORDER BY level DESC, create_time DESC")
    List<MarketMaker> selectByStatus(@Param("status") Integer status);
    
    /**
     * 查询指定等级的做市商列表
     */
    @Select("SELECT * FROM t_market_maker WHERE level = #{level} AND status = 1 ORDER BY create_time DESC")
    List<MarketMaker> selectByLevel(@Param("level") Integer level);
    
    /**
     * 查询交易对的所有活跃做市商
     */
    @Select("SELECT * FROM t_market_maker WHERE status = 1 AND symbols LIKE CONCAT('%', #{symbol}, '%')")
    List<MarketMaker> selectActiveMakersBySymbol(@Param("symbol") String symbol);
    
    /**
     * 更新做市商状态
     */
    @Update("UPDATE t_market_maker SET status = #{newStatus}, update_time = #{updateTime}, version = version + 1 " +
            "WHERE id = #{id} AND status = #{oldStatus} AND version = #{version}")
    int updateStatus(@Param("id") Long id,
                     @Param("oldStatus") Integer oldStatus,
                     @Param("newStatus") Integer newStatus,
                     @Param("updateTime") Long updateTime,
                     @Param("version") Integer version);
    
    /**
     * 更新做市商等级
     */
    @Update("UPDATE t_market_maker SET level = #{level}, maker_fee_rate = #{makerFeeRate}, " +
            "taker_fee_rate = #{takerFeeRate}, update_time = #{updateTime} " +
            "WHERE id = #{id} AND version = #{version}")
    int updateLevelAndFee(@Param("id") Long id,
                          @Param("level") Integer level,
                          @Param("makerFeeRate") Long makerFeeRate,
                          @Param("takerFeeRate") Long takerFeeRate,
                          @Param("updateTime") Long updateTime,
                          @Param("version") Integer version);
    
    /**
     * 查询所有活跃做市商
     */
    @Select("SELECT * FROM t_market_maker WHERE status = 1 ORDER BY level DESC, margin_balance DESC")
    List<MarketMaker> selectAllActive();
    
    /**
     * 统计指定等级的做市商数量
     */
    @Select("SELECT COUNT(*) FROM t_market_maker WHERE level = #{level} AND status = #{status}")
    Long countByLevelAndStatus(@Param("level") Integer level, @Param("status") Integer status);
    
    /**
     * 更新保证金余额
     */
    @Update("UPDATE t_market_maker SET margin_balance = margin_balance + #{delta}, update_time = #{updateTime} " +
            "WHERE id = #{id}")
    int updateMarginBalance(@Param("id") Long id,
                            @Param("delta") Long delta,
                            @Param("updateTime") Long updateTime);
    
    /**
     * 更新上月评分
     */
    @Update("UPDATE t_market_maker SET last_month_score = #{score}, total_mak_days = total_mak_days + 1, " +
            "update_time = #{updateTime} WHERE user_id = #{userId}")
    int updateLastMonthScore(@Param("userId") Long userId,
                             @Param("score") Integer score,
                             @Param("updateTime") Long updateTime);
}
