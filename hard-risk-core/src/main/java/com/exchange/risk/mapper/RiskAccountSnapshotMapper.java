package com.exchange.risk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.risk.entity.RiskAccountSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 账户快照Mapper
 */
@Mapper
public interface RiskAccountSnapshotMapper extends BaseMapper<RiskAccountSnapshot> {
    
    /**
     * 根据用户ID查询账户快照
     */
    @Select("SELECT * FROM risk_account_snapshot WHERE user_id = #{userId} LIMIT 1")
    RiskAccountSnapshot selectByUserId(@Param("userId") Long userId);
}

