package com.exchange.oms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.oms.entity.OmsOrderStateLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 订单状态日志Mapper
 */
@Mapper
public interface OmsOrderStateLogMapper extends BaseMapper<OmsOrderStateLog> {
    
    /**
     * 查询订单的状态变更历史
     */
    @Select("SELECT * FROM t_order_state_log WHERE order_id = #{orderId} ORDER BY id ASC")
    List<OmsOrderStateLog> selectByOrderId(@Param("orderId") Long orderId);
    
    /**
     * 查询用户的状态变更历史
     */
    @Select("SELECT * FROM t_order_state_log WHERE user_id = #{userId} " +
            "ORDER BY created_at DESC LIMIT #{limit}")
    List<OmsOrderStateLog> selectByUserId(
        @Param("userId") Long userId,
        @Param("limit") Integer limit
    );
}



