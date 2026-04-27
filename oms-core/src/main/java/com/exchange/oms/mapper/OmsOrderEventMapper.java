package com.exchange.oms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.oms.entity.OmsOrderEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 订单事件Mapper
 */
@Mapper
public interface OmsOrderEventMapper extends BaseMapper<OmsOrderEvent> {
    
    /**
     * 查询订单的所有事件
     */
    @Select("SELECT * FROM t_order_event WHERE order_id = #{orderId} ORDER BY id ASC")
    List<OmsOrderEvent> selectByOrderId(@Param("orderId") Long orderId);
    
    /**
     * 查询交易对的事件流（用于重放）
     */
    @Select("SELECT * FROM t_order_event WHERE symbol = #{symbol} " +
            "AND created_at >= #{startTime} ORDER BY id ASC LIMIT #{limit}")
    List<OmsOrderEvent> selectBySymbolForReplay(
        @Param("symbol") String symbol,
        @Param("startTime") Long startTime,
        @Param("limit") Integer limit
    );
}






