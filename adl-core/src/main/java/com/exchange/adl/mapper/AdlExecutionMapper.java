package com.exchange.adl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.adl.entity.AdlExecution;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * ADL Execution Mapper
 * 
 * 🔥 核心职责：
 * 1. 管理ADL执行记录的数据访问
 * 2. 提供按各种条件查询ADL历史
 * 3. 支持统计查询
 */
public interface AdlExecutionMapper extends BaseMapper<AdlExecution> {
    
    /**
     * 根据ADL执行ID查询
     * 
     * @param adlExecutionId ADL执行ID
     * @return ADL执行记录
     */
    @Select("SELECT * FROM adl_execution WHERE adl_execution_id = #{adlExecutionId}")
    AdlExecution selectByAdlExecutionId(@Param("adlExecutionId") String adlExecutionId);
    
    /**
     * 根据强平ID查询关联的ADL执行
     * 
     * @param liquidationId 强平ID
     * @return ADL执行记录列表
     */
    @Select("SELECT * FROM adl_execution WHERE liquidation_id = #{liquidationId} ORDER BY execution_sequence ASC")
    List<AdlExecution> selectByLiquidationId(@Param("liquidationId") String liquidationId);
    
    /**
     * 根据被ADL用户ID查询历史
     * 
     * @param targetUserId 被ADL用户ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return ADL执行记录列表
     */
    @Select("SELECT * FROM adl_execution WHERE target_user_id = #{targetUserId} " +
            "AND created_at >= #{startTime} AND created_at <= #{endTime} " +
            "ORDER BY created_at DESC")
    List<AdlExecution> selectByTargetUserIdAndTimeRange(@Param("targetUserId") Long targetUserId,
                                                         @Param("startTime") Long startTime,
                                                         @Param("endTime") Long endTime);
    
    /**
     * 根据触发ADL用户ID查询历史
     * 
     * @param sourceUserId 触发ADL用户ID
     * @return ADL执行记录列表
     */
    @Select("SELECT * FROM adl_execution WHERE source_user_id = #{sourceUserId} ORDER BY created_at DESC")
    List<AdlExecution> selectBySourceUserId(@Param("sourceUserId") Long sourceUserId);
    
    /**
     * 根据交易对查询ADL执行
     * 
     * @param symbol 交易对
     * @return ADL执行记录列表
     */
    @Select("SELECT * FROM adl_execution WHERE symbol = #{symbol} ORDER BY created_at DESC LIMIT 100")
    List<AdlExecution> selectBySymbol(@Param("symbol") String symbol);
    
    /**
     * 统计指定交易对的ADL次数
     * 
     * @param symbol 交易对
     * @return ADL次数
     */
    @Select("SELECT COUNT(*) FROM adl_execution WHERE symbol = #{symbol} AND status = 'SUCCESS'")
    int countBySymbol(@Param("symbol") String symbol);
    
    /**
     * 统计指定交易对的总ADL数量
     * 
     * @param symbol 交易对
     * @return 总ADL数量
     */
    @Select("SELECT COALESCE(SUM(adl_qty), 0) FROM adl_execution WHERE symbol = #{symbol} AND status = 'SUCCESS'")
    BigDecimal sumAdlQuantityBySymbol(@Param("symbol") String symbol);
    
    /**
     * 统计指定交易对的总ADL价值
     * 
     * @param symbol 交易对
     * @return 总ADL价值
     */
    @Select("SELECT COALESCE(SUM(adl_value), 0) FROM adl_execution WHERE symbol = #{symbol} AND status = 'SUCCESS'")
    BigDecimal sumAdlValueBySymbol(@Param("symbol") String symbol);
    
    /**
     * 统计指定交易对被ADL的唯一用户数
     * 
     * @param symbol 交易对
     * @return 唯一用户数
     */
    @Select("SELECT COUNT(DISTINCT target_user_id) FROM adl_execution WHERE symbol = #{symbol} AND status = 'SUCCESS'")
    int countUniqueTargetUsersBySymbol(@Param("symbol") String symbol);
    
    /**
     * 查询指定交易对最后一次ADL时间
     * 
     * @param symbol 交易对
     * @return 最后一次ADL时间
     */
    @Select("SELECT MAX(created_at) FROM adl_execution WHERE symbol = #{symbol} AND status = 'SUCCESS'")
    Long selectLastAdlTimeBySymbol(@Param("symbol") String symbol);
    
    /**
     * 根据状态查询ADL执行
     * 
     * @param status 状态
     * @return ADL执行记录列表
     */
    @Select("SELECT * FROM adl_execution WHERE status = #{status} ORDER BY created_at ASC")
    List<AdlExecution> selectByStatus(@Param("status") String status);
    
    /**
     * 查询需要重试的失败ADL
     * 
     * @param maxRetryCount 最大重试次数
     * @return ADL执行记录列表
     */
    @Select("SELECT * FROM adl_execution WHERE status = 'FAILED' AND retry_count < #{maxRetryCount} ORDER BY created_at ASC")
    List<AdlExecution> selectFailedForRetry(@Param("maxRetryCount") int maxRetryCount);
    
    /**
     * 根据业务序列号查询（幂等性检查）
     * 
     * @param bizSeq 业务序列号
     * @return ADL执行记录
     */
    @Select("SELECT * FROM adl_execution WHERE biz_seq = #{bizSeq}")
    AdlExecution selectByBizSeq(@Param("bizSeq") String bizSeq);
}
