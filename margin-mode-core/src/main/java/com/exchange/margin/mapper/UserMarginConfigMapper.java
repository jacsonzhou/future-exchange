package com.exchange.margin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.margin.entity.UserMarginConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户保证金配置Mapper
 */
@Mapper
public interface UserMarginConfigMapper extends BaseMapper<UserMarginConfig> {

    /**
     * 根据用户ID和交易对查询配置
     */
    @Select("SELECT * FROM t_user_margin_config WHERE user_id = #{userId} AND symbol = #{symbol} LIMIT 1")
    UserMarginConfig selectByUserIdAndSymbol(@Param("userId") Long userId, @Param("symbol") String symbol);

    /**
     * 根据用户ID查询所有配置
     */
    @Select("SELECT * FROM t_user_margin_config WHERE user_id = #{userId}")
    java.util.List<UserMarginConfig> selectByUserId(@Param("userId") Long userId);
}
