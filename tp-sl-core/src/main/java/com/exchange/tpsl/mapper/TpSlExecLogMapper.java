package com.exchange.tpsl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.tpsl.entity.TpSlExecLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * TP/SL执行日志Mapper
 */
@Mapper
public interface TpSlExecLogMapper extends BaseMapper<TpSlExecLog> {

    /**
     * 根据TP/SL订单ID查询执行日志
     */
    @Select("SELECT * FROM t_tp_sl_exec_log WHERE tp_sl_order_id = #{tpSlOrderId} ORDER BY create_time DESC")
    List<TpSlExecLog> selectByTpSlOrderId(@Param("tpSlOrderId") Long tpSlOrderId);

    /**
     * 根据用户ID查询执行日志
     */
    @Select("SELECT * FROM t_tp_sl_exec_log WHERE user_id = #{userId} " +
            "ORDER BY create_time DESC LIMIT #{limit}")
    List<TpSlExecLog> selectByUserId(@Param("userId") Long userId, @Param("limit") Integer limit);

    /**
     * 统计失败次数
     */
    @Select("SELECT COUNT(*) FROM t_tp_sl_exec_log WHERE tp_sl_order_id = #{tpSlOrderId} " +
            "AND exec_result = 'FAIL'")
    Long countFailsByOrderId(@Param("tpSlOrderId") Long tpSlOrderId);
}
