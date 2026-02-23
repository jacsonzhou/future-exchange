package com.exchange.liquidation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.liquidation.entity.LiquidationExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 强平执行记录 Mapper
 */
@Mapper
public interface LiquidationExecutionMapper extends BaseMapper<LiquidationExecution> {
    
    @Select("SELECT * FROM t_liquidation_execution WHERE liquidation_id = #{liquidationId}")
    LiquidationExecution selectByLiquidationId(@Param("liquidationId") String liquidationId);
}
