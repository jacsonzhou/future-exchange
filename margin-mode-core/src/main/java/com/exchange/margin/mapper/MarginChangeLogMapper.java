package com.exchange.margin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.margin.entity.MarginChangeLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 保证金变动流水Mapper
 */
@Mapper
public interface MarginChangeLogMapper extends BaseMapper<MarginChangeLog> {

    /**
     * 根据用户ID查询流水
     */
    @Select("SELECT * FROM t_margin_change_log WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT #{limit}")
    List<MarginChangeLog> selectByUserId(@Param("userId") Long userId, @Param("limit") Integer limit);

    /**
     * 根据仓位ID查询流水
     */
    @Select("SELECT * FROM t_margin_change_log WHERE position_id = #{positionId} ORDER BY created_at DESC")
    List<MarginChangeLog> selectByPositionId(@Param("positionId") Long positionId);

    /**
     * 根据用户ID和变动类型查询流水
     */
    @Select("SELECT * FROM t_margin_change_log WHERE user_id = #{userId} AND change_type = #{changeType} ORDER BY created_at DESC LIMIT #{limit}")
    List<MarginChangeLog> selectByUserIdAndType(@Param("userId") Long userId,
                                                  @Param("changeType") String changeType,
                                                  @Param("limit") Integer limit);
}
