package com.exchange.liquidation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.liquidation.entity.LiquidationExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 强平执行记录 Mapper
 */
@Mapper
public interface LiquidationExecutionMapper extends BaseMapper<LiquidationExecution> {
    
    @Select("SELECT * FROM t_liquidation_execution WHERE liquidation_id = #{liquidationId}")
    LiquidationExecution selectByLiquidationId(@Param("liquidationId") String liquidationId);

    @Select("SELECT * FROM t_liquidation_execution WHERE order_id = #{orderId} ORDER BY id DESC LIMIT 1")
    LiquidationExecution selectLatestByOrderId(@Param("orderId") Long orderId);

    @Update(
        "UPDATE t_liquidation_execution " +
        "SET status = #{status}, error_msg = #{errorMsg}, updated_at = #{updatedAt} " +
        "WHERE liquidation_id = #{liquidationId}"
    )
    int updateFailureStateByLiquidationId(
        @Param("liquidationId") String liquidationId,
        @Param("status") String status,
        @Param("errorMsg") String errorMsg,
        @Param("updatedAt") Long updatedAt
    );
}
