package com.exchange.margin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.margin.entity.CrossMarginSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 全仓账户风险快照 Mapper（生产级）
 * 
 * 核心功能：
 * 1. 全仓账户快照CRUD
 * 2. 风险等级查询
 * 3. 批量查询高风险账户
 * 4. 快照更新（乐观锁）
 */
@Mapper
public interface CrossMarginSnapshotMapper extends BaseMapper<CrossMarginSnapshot> {
    
    /**
     * 查询所有快照
     */
    @Select("SELECT * FROM t_cross_margin_snapshot ORDER BY user_id")
    List<CrossMarginSnapshot> selectAll();

    /**
     * 根据用户ID查询
     */
    @Select("SELECT * FROM t_cross_margin_snapshot WHERE user_id = #{userId}")
    CrossMarginSnapshot selectByUserId(@Param("userId") Long userId);
    
    /**
     * 根据风险等级查询
     */
    @Select("SELECT * FROM t_cross_margin_snapshot WHERE risk_level = #{riskLevel} ORDER BY margin_ratio ASC")
    List<CrossMarginSnapshot> selectByRiskLevel(@Param("riskLevel") Integer riskLevel);
    
    /**
     * 查询高风险账户（保证金率低于阈值）
     */
    @Select("SELECT * FROM t_cross_margin_snapshot WHERE margin_ratio < #{threshold} ORDER BY margin_ratio ASC")
    List<CrossMarginSnapshot> selectHighRiskUsers(@Param("threshold") Long threshold);
    
    /**
     * 查询需要强平的账户（保证金率 <= 10%）
     */
    @Select("SELECT * FROM t_cross_margin_snapshot WHERE margin_ratio <= 1000 AND (liquidation_status = 0 OR liquidation_status IS NULL)")
    List<CrossMarginSnapshot> selectLiquidationCandidates();
    
    /**
     * 查询正在强平的账户
     */
    @Select("SELECT * FROM t_cross_margin_snapshot WHERE liquidation_status = 1")
    List<CrossMarginSnapshot> selectLiquidatingUsers();
    
    /**
     * 更新全仓快照（乐观锁）
     */
    @Update("UPDATE t_cross_margin_snapshot SET wallet_balance = #{walletBalance}, available_balance = #{availableBalance}, " +
            "frozen_balance = #{frozenBalance}, used_margin = #{usedMargin}, total_position_value = #{totalPositionValue}, " +
            "long_position_value = #{longPositionValue}, short_position_value = #{shortPositionValue}, position_count = #{positionCount}, " +
            "total_unrealized_pnl = #{totalUnrealizedPnl}, today_realized_pnl = #{todayRealizedPnl}, " +
            "total_maintenance_margin = #{totalMaintenanceMargin}, initial_margin_requirement = #{initialMarginRequirement}, " +
            "maintenance_margin_rate = #{maintenanceMarginRate}, margin_balance = #{marginBalance}, margin_ratio = #{marginRatio}, " +
            "available_margin = #{availableMargin}, max_open_position_value = #{maxOpenPositionValue}, " +
            "risk_level = #{riskLevel}, risk_level_name = #{riskLevelName}, can_trade = #{canTrade}, can_withdraw = #{canWithdraw}, " +
            "estimated_liquidation_price = #{estimatedLiquidationPrice}, liquidation_gap = #{liquidationGap}, " +
            "updated_at = #{updatedAt}, version = version + 1 " +
            "WHERE user_id = #{userId} AND version = #{version}")
    int updateSnapshot(@Param("userId") Long userId,
                       @Param("walletBalance") Long walletBalance,
                       @Param("availableBalance") Long availableBalance,
                       @Param("frozenBalance") Long frozenBalance,
                       @Param("usedMargin") Long usedMargin,
                       @Param("totalPositionValue") Long totalPositionValue,
                       @Param("longPositionValue") Long longPositionValue,
                       @Param("shortPositionValue") Long shortPositionValue,
                       @Param("positionCount") Integer positionCount,
                       @Param("totalUnrealizedPnl") Long totalUnrealizedPnl,
                       @Param("todayRealizedPnl") Long todayRealizedPnl,
                       @Param("totalMaintenanceMargin") Long totalMaintenanceMargin,
                       @Param("initialMarginRequirement") Long initialMarginRequirement,
                       @Param("maintenanceMarginRate") Integer maintenanceMarginRate,
                       @Param("marginBalance") Long marginBalance,
                       @Param("marginRatio") Long marginRatio,
                       @Param("availableMargin") Long availableMargin,
                       @Param("maxOpenPositionValue") Long maxOpenPositionValue,
                       @Param("riskLevel") Integer riskLevel,
                       @Param("riskLevelName") String riskLevelName,
                       @Param("canTrade") Boolean canTrade,
                       @Param("canWithdraw") Boolean canWithdraw,
                       @Param("estimatedLiquidationPrice") Long estimatedLiquidationPrice,
                       @Param("liquidationGap") Long liquidationGap,
                       @Param("updatedAt") Long updatedAt,
                       @Param("version") Integer version);
    
    /**
     * 更新风险等级
     */
    @Update("UPDATE t_cross_margin_snapshot SET risk_level = #{riskLevel}, risk_level_name = #{riskLevelName}, " +
            "can_trade = #{canTrade}, can_withdraw = #{canWithdraw}, updated_at = #{updatedAt}, version = version + 1 " +
            "WHERE user_id = #{userId} AND version = #{version}")
    int updateRiskLevel(@Param("userId") Long userId,
                        @Param("riskLevel") Integer riskLevel,
                        @Param("riskLevelName") String riskLevelName,
                        @Param("canTrade") Boolean canTrade,
                        @Param("canWithdraw") Boolean canWithdraw,
                        @Param("updatedAt") Long updatedAt,
                        @Param("version") Integer version);
    
    /**
     * 更新强平状态
     */
    @Update("UPDATE t_cross_margin_snapshot SET liquidation_status = #{liquidationStatus}, " +
            "liquidation_start_time = #{liquidationStartTime}, updated_at = #{updatedAt}, version = version + 1 " +
            "WHERE user_id = #{userId} AND version = #{version}")
    int updateLiquidationStatus(@Param("userId") Long userId,
                                @Param("liquidationStatus") Integer liquidationStatus,
                                @Param("liquidationStartTime") Long liquidationStartTime,
                                @Param("updatedAt") Long updatedAt,
                                @Param("version") Integer version);
    
    /**
     * 统计各风险等级的用户数量
     */
    @Select("SELECT risk_level, COUNT(*) as count FROM t_cross_margin_snapshot GROUP BY risk_level")
    List<java.util.Map<String, Object>> countByRiskLevel();
    
    /**
     * 查询有持仓的用户
     */
    @Select("SELECT * FROM t_cross_margin_snapshot WHERE position_count > 0 ORDER BY margin_ratio ASC")
    List<CrossMarginSnapshot> selectUsersWithPosition();
    
    /**
     * 批量查询
     */
    List<CrossMarginSnapshot> selectBatchByUserIds(@Param("userIds") List<Long> userIds);
}
