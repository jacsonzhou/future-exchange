package com.exchange.tpsl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.tpsl.entity.TpSlOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 止盈止损订单Mapper
 * 
 * 🔥 核心查询：
 * 1. 根据用户和持仓查询TP/SL订单
 * 2. 查询活跃订单（用于价格触发检查）
 * 3. 乐观锁更新状态
 */
@Mapper
public interface TpSlOrderMapper extends BaseMapper<TpSlOrder> {
    
    /**
     * 根据用户ID和客户端订单ID查询
     * 
     * 用途：幂等检查
     */
    @Select("SELECT * FROM t_tp_sl_order WHERE user_id = #{userId} AND client_order_id = #{clientOrderId}")
    TpSlOrder selectByUserIdAndClientOrderId(
        @Param("userId") Long userId, 
        @Param("clientOrderId") String clientOrderId
    );
    
    /**
     * 根据持仓ID查询关联的TP/SL订单
     * 
     * 用途：持仓平仓时自动取消关联的TP/SL订单
     */
    @Select("SELECT * FROM t_tp_sl_order WHERE position_id = #{positionId} AND status IN (1, 2)")
    List<TpSlOrder> selectByPositionId(@Param("positionId") Long positionId);
    
    /**
     * 根据用户ID查询活跃订单
     * 
     * 用途：用户查询自己的TP/SL订单列表
     */
    @Select("SELECT * FROM t_tp_sl_order WHERE user_id = #{userId} AND status IN (1, 2) " +
            "ORDER BY create_time DESC LIMIT #{limit}")
    List<TpSlOrder> selectActiveOrdersByUserId(
        @Param("userId") Long userId,
        @Param("limit") Integer limit
    );
    
    /**
     * 查询所有活跃订单（用于触发检查）
     * 
     * 用途：MarkPriceConsumer批量查询待检查的订单
     */
    @Select("SELECT * FROM t_tp_sl_order WHERE status = 2 AND symbol = #{symbol} " +
            "ORDER BY create_time DESC LIMIT #{limit}")
    List<TpSlOrder> selectActiveOrdersBySymbol(
        @Param("symbol") String symbol,
        @Param("limit") Integer limit
    );
    
    /**
     * 查询所有待激活订单（激活检查）
     */
    @Select("SELECT * FROM t_tp_sl_order WHERE status = 1 LIMIT #{limit}")
    List<TpSlOrder> selectPendingOrders(@Param("limit") Integer limit);
    
    /**
     * 更新订单状态（乐观锁）
     * 
     * 用途：状态流转时的并发控制
     */
    @Update("UPDATE t_tp_sl_order SET status = #{newStatus}, update_time = #{updateTime}, " +
            "version = version + 1 WHERE order_id = #{orderId} AND status = #{oldStatus} AND version = #{version}")
    int updateStatus(
        @Param("orderId") Long orderId,
        @Param("oldStatus") Integer oldStatus,
        @Param("newStatus") Integer newStatus,
        @Param("updateTime") Long updateTime,
        @Param("version") Integer version
    );
    
    /**
     * 更新订单为已触发状态
     * 
     * 用途：价格触发时更新状态
     */
    @Update("UPDATE t_tp_sl_order SET status = 3, triggered_price = #{triggeredPrice}, " +
            "triggered_time = #{triggeredTime}, update_time = #{updateTime}, version = version + 1 " +
            "WHERE order_id = #{orderId} AND status = 2 AND version = #{version}")
    int updateTriggered(
        @Param("orderId") Long orderId,
        @Param("triggeredPrice") Long triggeredPrice,
        @Param("triggeredTime") Long triggeredTime,
        @Param("updateTime") Long updateTime,
        @Param("version") Integer version
    );
    
    /**
     * 更新订单为已执行状态
     * 
     * 用途：平仓订单成交后更新
     */
    @Update("UPDATE t_tp_sl_order SET status = 4, close_order_id = #{closeOrderId}, " +
            "exec_result = #{execResult}, update_time = #{updateTime}, version = version + 1 " +
            "WHERE order_id = #{orderId} AND status = 3 AND version = #{version}")
    int updateExecuted(
        @Param("orderId") Long orderId,
        @Param("closeOrderId") Long closeOrderId,
        @Param("execResult") String execResult,
        @Param("updateTime") Long updateTime,
        @Param("version") Integer version
    );
    
    /**
     * 更新订单为已取消状态
     */
    @Update("UPDATE t_tp_sl_order SET status = 5, exec_result = #{reason}, " +
            "update_time = #{updateTime}, version = version + 1 " +
            "WHERE order_id = #{orderId} AND status IN (1, 2) AND version = #{version}")
    int updateCanceled(
        @Param("orderId") Long orderId,
        @Param("reason") String reason,
        @Param("updateTime") Long updateTime,
        @Param("version") Integer version
    );
    
    /**
     * 更新追踪止损的触发价格
     * 
     * 用途：价格向有利方向移动时调整止损价
     */
    @Update("UPDATE t_tp_sl_order SET trigger_price = #{newTriggerPrice}, " +
            "trailing_active_price = #{trailingActivePrice}, update_time = #{updateTime} " +
            "WHERE order_id = #{orderId} AND order_type = 3")
    int updateTrailingPrice(
        @Param("orderId") Long orderId,
        @Param("newTriggerPrice") Long newTriggerPrice,
        @Param("trailingActivePrice") Long trailingActivePrice,
        @Param("updateTime") Long updateTime
    );
    
    /**
     * 统计用户的活跃订单数
     */
    @Select("SELECT COUNT(*) FROM t_tp_sl_order WHERE user_id = #{userId} AND status IN (1, 2)")
    Long countActiveOrdersByUserId(@Param("userId") Long userId);
    
    /**
     * 统计指定symbol的活跃订单数
     */
    @Select("SELECT COUNT(*) FROM t_tp_sl_order WHERE symbol = #{symbol} AND status = 2")
    Long countActiveOrdersBySymbol(@Param("symbol") String symbol);
    
    /**
     * 查询symbol下所有活跃订单
     */
    @Select("SELECT * FROM t_tp_sl_order WHERE symbol = #{symbol} AND status = 'ACTIVE'")
    List<TpSlOrder> selectActiveBySymbol(@Param("symbol") String symbol);
    
    /**
     * 根据用户和symbol查询
     */
    @Select("<script>SELECT * FROM t_tp_sl_order WHERE user_id = #{userId} " +
            "<if test='symbol != null'>AND symbol = #{symbol}</if> " +
            "<if test='status != null'>AND status = #{status}</if></script>")
    List<TpSlOrder> selectByUserAndSymbol(@Param("userId") Long userId, 
                                          @Param("symbol") String symbol, 
                                          @Param("status") String status);
    
    /**
     * 查询持仓下活跃订单
     */
    @Select("SELECT * FROM t_tp_sl_order WHERE position_id = #{positionId} AND status = 'ACTIVE'")
    List<TpSlOrder> selectActiveByPositionId(@Param("positionId") Long positionId);
    
    /**
     * 查询追踪止损订单
     */
    @Select("SELECT * FROM t_tp_sl_order WHERE symbol = #{symbol} AND order_type = 'TRAILING' AND status = 'ACTIVE'")
    List<TpSlOrder> selectTrailingBySymbol(@Param("symbol") String symbol);
}
