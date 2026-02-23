package com.exchange.funding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.funding.entity.UserFundingFee;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户资金费用Mapper
 */
@Mapper
public interface UserFundingFeeMapper extends BaseMapper<UserFundingFee> {
    
    /**
     * 查询用户资金费用记录
     */
    @Select("<script>" +
            "SELECT * FROM t_user_funding_fee WHERE user_id = #{userId} " +
            "<if test='symbol != null'>AND symbol = #{symbol}</if> " +
            "<if test='startTime != null'>AND funding_time &gt;= #{startTime}</if> " +
            "<if test='endTime != null'>AND funding_time &lt;= #{endTime}</if> " +
            "ORDER BY funding_time DESC" +
            "</script>")
    List<UserFundingFee> selectByUserAndTime(@Param("userId") Long userId, 
                                              @Param("symbol") String symbol,
                                              @Param("startTime") Long startTime, 
                                              @Param("endTime") Long endTime);
}
