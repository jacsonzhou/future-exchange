package com.exchange.cfddealer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.cfddealer.entity.CfdWorkingOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface CfdWorkingOrderMapper extends BaseMapper<CfdWorkingOrder> {

    @Select("SELECT * FROM t_cfd_working_order WHERE symbol = #{symbol} AND status = 'WORKING' ORDER BY updated_at ASC LIMIT #{limit}")
    List<CfdWorkingOrder> selectWorkingBySymbol(@Param("symbol") String symbol, @Param("limit") int limit);

    @Update("UPDATE t_cfd_working_order " +
            "SET status=#{toStatus}, remaining_quantity=0, trigger_source=#{triggerSource}, trigger_event_time=#{triggerEventTime}, updated_at=#{updatedAt}, version=version+1 " +
            "WHERE order_id=#{orderId} AND status=#{fromStatus}")
    int updateStatusToFinal(
            @Param("orderId") Long orderId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("triggerSource") String triggerSource,
            @Param("triggerEventTime") Long triggerEventTime,
            @Param("updatedAt") Long updatedAt
    );
}
