package com.exchange.oms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.oms.entity.OmsIdempotentKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 幂等keyMapper
 */
@Mapper
public interface OmsIdempotentKeyMapper extends BaseMapper<OmsIdempotentKey> {
    
    /**
     * 查询幂等key
     */
    @Select("SELECT * FROM t_idempotent_key WHERE user_id = #{userId} AND idem_key = #{idemKey}")
    OmsIdempotentKey selectByUserIdAndKey(
        @Param("userId") Long userId,
        @Param("idemKey") String idemKey
    );
}






