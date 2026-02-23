package com.exchange.adl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.adl.entity.InsuranceFundLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 保险基金流水Mapper
 */
@Mapper
public interface InsuranceFundLogMapper extends BaseMapper<InsuranceFundLog> {

    /**
     * 查询保险基金流水
     */
    List<InsuranceFundLog> selectBySymbol(@Param("symbol") String symbol, @Param("limit") int limit);

    /**
     * 根据关联ID查询流水
     */
    List<InsuranceFundLog> selectByRefId(@Param("refId") String refId);

    /**
     * 查询最近的流水记录
     */
    List<InsuranceFundLog> selectRecent(@Param("limit") int limit);

    /**
     * 查询指定时间范围的流水
     */
    List<InsuranceFundLog> selectByTimeRange(
            @Param("symbol") String symbol,
            @Param("startTime") Long startTime,
            @Param("endTime") Long endTime
    );
}
