package com.exchange.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.risk.entity.RiskUserList;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 风控黑白名单Mapper
 */
@Mapper
public interface RiskUserListMapper extends BaseMapper<RiskUserList> {
    
    /**
     * 根据用户ID查询名单
     */
    @Select("SELECT * FROM risk_user_list WHERE user_id = #{userId} LIMIT 1")
    RiskUserList selectByUserId(@Param("userId") Long userId);
}

