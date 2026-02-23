package com.exchange.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.risk.entity.RiskPositionSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 持仓快照Mapper
 */
@Mapper
public interface RiskPositionSnapshotMapper extends BaseMapper<RiskPositionSnapshot> {
    
    /**
     * 根据用户ID和交易对查询持仓
     */
    @Select("SELECT * FROM risk_position_snapshot WHERE user_id = #{userId} AND symbol = #{symbol} LIMIT 1")
    RiskPositionSnapshot selectByUserIdAndSymbol(
        @Param("userId") Long userId,
        @Param("symbol") String symbol
    );
}

