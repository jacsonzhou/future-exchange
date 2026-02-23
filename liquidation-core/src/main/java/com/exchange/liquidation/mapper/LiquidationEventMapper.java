package com.exchange.liquidation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.liquidation.entity.LiquidationEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 本地事件表 Mapper
 */
@Mapper
public interface LiquidationEventMapper extends BaseMapper<LiquidationEvent> {

    /**
     * 查询需要重试的事件
     * 条件: 状态=PENDING, 重试次数<最大次数, 当前时间>=下次重试时间
     */
    @Select("SELECT * FROM t_liquidation_event " +
            "WHERE send_status = 'PENDING' " +
            "AND retry_count < max_retry " +
            "AND (next_retry_time IS NULL OR next_retry_time <= #{now}) " +
            "ORDER BY created_at ASC " +
            "LIMIT #{limit}")
    List<LiquidationEvent> selectPendingEvents(@Param("now") Long now, @Param("limit") Integer limit);

    /**
     * 查询失败的事件（达到最大重试次数）
     */
    @Select("SELECT * FROM t_liquidation_event " +
            "WHERE send_status = 'PENDING' " +
            "AND retry_count >= max_retry " +
            "ORDER BY created_at DESC " +
            "LIMIT #{limit}")
    List<LiquidationEvent> selectFailedEvents(@Param("limit") Integer limit);
}
