package com.exchange.oms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.common.core.enums.Side;
import com.exchange.oms.config.typehandler.SideTypeHandler;
import com.exchange.oms.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 订单Mapper
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
    
    /**
     * 根据状态列表查询订单
     * 
     * @param statuses 状态列表
     * @return 订单列表
     */
    @Select("<script>" +
            "SELECT * FROM t_order WHERE status IN " +
            "<foreach collection='statuses' item='status' open='(' separator=',' close=')'>" +
            "#{status}" +
            "</foreach>" +
            "</script>")
    @Results({
        @Result(column = "side", property = "side", javaType = Side.class, typeHandler = SideTypeHandler.class)
    })
    List<Order> selectByStatuses(@Param("statuses") List<String> statuses);
}






