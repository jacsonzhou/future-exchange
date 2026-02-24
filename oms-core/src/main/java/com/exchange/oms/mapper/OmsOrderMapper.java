package com.exchange.oms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.oms.entity.OmsOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * OMS订单Mapper
 */
@Mapper
public interface OmsOrderMapper extends BaseMapper<OmsOrder> {
    
    /**
     * 根据用户ID和客户端订单ID查询
     */
    @Select("SELECT * FROM t_order WHERE user_id = #{userId} AND client_order_id = #{clientOrderId}")
    OmsOrder selectByUserIdAndClientOrderId(
        @Param("userId") Long userId, 
        @Param("clientOrderId") String clientOrderId
    );
    
    /**
     * 更新订单状态（乐观锁）
     */
    @Update("UPDATE t_order SET status = #{newStatus}, updated_at = #{updatedAt}, version = version + 1 " +
            "WHERE id = #{orderId} AND status = #{oldStatus} AND version = #{version}")
    int updateStatus(
        @Param("orderId") Long orderId,
        @Param("oldStatus") Integer oldStatus,
        @Param("newStatus") Integer newStatus,
        @Param("updatedAt") Long updatedAt,
        @Param("version") Integer version
    );
    
    /**
     * 更新已成交数量
     */
    @Update("UPDATE t_order SET filled_quantity = filled_quantity + #{delta}, " +
            "status = #{newStatus}, updated_at = #{updatedAt}, version = version + 1 " +
            "WHERE id = #{orderId} AND version = #{version}")
    int updateFilledQuantity(
        @Param("orderId") Long orderId,
        @Param("delta") BigDecimal delta,
        @Param("newStatus") Integer newStatus,
        @Param("updatedAt") Long updatedAt,
        @Param("version") Integer version
    );
    
    /**
     * 查询用户的活跃订单
     */
    @Select("SELECT * FROM t_order WHERE user_id = #{userId} AND status IN (0,1,2,3) " +
            "ORDER BY created_at DESC LIMIT #{limit}")
    List<OmsOrder> selectActiveOrders(
        @Param("userId") Long userId,
        @Param("limit") Integer limit
    );
    
    /**
     * 查询用户的历史订单
     */
    @Select("SELECT * FROM t_order WHERE user_id = #{userId} " +
            "ORDER BY created_at DESC LIMIT #{offset}, #{limit}")
    List<OmsOrder> selectUserOrders(
        @Param("userId") Long userId,
        @Param("offset") Integer offset,
        @Param("limit") Integer limit
    );
    
    /**
     * 统计用户订单数
     */
    @Select("SELECT COUNT(*) FROM t_order WHERE user_id = #{userId}")
    Long countUserOrders(@Param("userId") Long userId);
    
    /**
     * 根据用户ID和状态列表查询订单（活跃订单）
     */
    @Select("SELECT * FROM t_order WHERE user_id = #{userId} " +
            "AND status IN (0, 1, 2, 3) " +
            "ORDER BY created_at DESC LIMIT #{limit}")
    List<OmsOrder> selectActiveOrdersWithLimit(
        @Param("userId") Long userId,
        @Param("limit") Integer limit
    );
    
    /**
     * 根据用户ID查询历史订单（已成交/已取消）
     */
    @Select("SELECT * FROM t_order WHERE user_id = #{userId} " +
            "AND status IN (4, 5, 6) " +
            "ORDER BY created_at DESC LIMIT #{limit}")
    List<OmsOrder> selectHistoryOrdersWithLimit(
        @Param("userId") Long userId,
        @Param("limit") Integer limit
    );

    /**
     * 根据状态列表查询订单（用于启动恢复等场景）
     */
    @Select("<script>" +
            "SELECT * FROM t_order WHERE status IN " +
            "<foreach collection='statuses' item='status' open='(' separator=',' close=')'>" +
            "#{status}" +
            "</foreach>" +
            "</script>")
    List<OmsOrder> selectByStatuses(@Param("statuses") List<Integer> statuses);
}


