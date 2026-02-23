package com.exchange.marketmaker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.marketmaker.entity.MmFeeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 做市商费率流水Mapper
 */
@Mapper
public interface MmFeeLogMapper extends BaseMapper<MmFeeLog> {

    /**
     * 统计用户在指定时间范围内的总返佣
     */
    @Select("SELECT COALESCE(SUM(fee_amount), 0) FROM t_mm_fee_log " +
            "WHERE user_id = #{userId} " +
            "AND fee_type = 'MAKER_REBATE' " +
            "AND created_at >= #{startTime} " +
            "AND created_at < #{endTime}")
    Long sumRebateByUserIdAndTimeRange(
            @Param("userId") Long userId,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );
}
