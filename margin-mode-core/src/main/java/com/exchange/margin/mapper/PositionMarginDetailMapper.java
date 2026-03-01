package com.exchange.margin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.margin.entity.PositionMarginDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 仓位保证金详情 Mapper（生产级）
 * 
 * 核心功能：
 * 1. 仓位保证金详情CRUD
 * 2. 根据用户/仓位查询
 * 3. 保证金模式切换
 * 4. 逐仓保证金调整
 */
@Mapper
public interface PositionMarginDetailMapper extends BaseMapper<PositionMarginDetail> {
    
    /**
     * 根据仓位ID查询
     */
    @Select("SELECT id, position_id, user_id, symbol, side, margin_mode, leverage, " +
            "isolated_margin, added_margin, reduced_margin, position_value, position_margin, " +
            "unrealized_pnl, maintenance_margin AS maintMargin, maintenance_margin_rate AS maintMarginRate, " +
            "quantity AS positionQty, entry_price, mark_price, liquidation_price, bankruptcy_price, " +
            "margin_ratio, liquidation_distance, max_add_position_qty, last_price, created_at, updated_at, version " +
            "FROM t_position_margin_detail WHERE position_id = #{positionId}")
    PositionMarginDetail selectByPositionId(@Param("positionId") Long positionId);
    
    /**
     * 根据用户ID查询所有仓位保证金详情
     */
    @Select("SELECT id, position_id, user_id, symbol, side, margin_mode, leverage, " +
            "isolated_margin, added_margin, reduced_margin, position_value, position_margin, " +
            "unrealized_pnl, maintenance_margin AS maintMargin, maintenance_margin_rate AS maintMarginRate, " +
            "quantity AS positionQty, entry_price, mark_price, liquidation_price, bankruptcy_price, " +
            "margin_ratio, liquidation_distance, max_add_position_qty, last_price, created_at, updated_at, version " +
            "FROM t_position_margin_detail WHERE user_id = #{userId} ORDER BY updated_at DESC")
    List<PositionMarginDetail> selectByUserId(@Param("userId") Long userId);
    
    /**
     * 根据用户ID和保证金模式查询
     */
    @Select("SELECT id, position_id, user_id, symbol, side, margin_mode, leverage, " +
            "isolated_margin, added_margin, reduced_margin, position_value, position_margin, " +
            "unrealized_pnl, maintenance_margin AS maintMargin, maintenance_margin_rate AS maintMarginRate, " +
            "quantity AS positionQty, entry_price, mark_price, liquidation_price, bankruptcy_price, " +
            "margin_ratio, liquidation_distance, max_add_position_qty, last_price, created_at, updated_at, version " +
            "FROM t_position_margin_detail WHERE user_id = #{userId} AND margin_mode = #{marginMode}")
    List<PositionMarginDetail> selectByUserIdAndMode(@Param("userId") Long userId, @Param("marginMode") String marginMode);
    
    /**
     * 根据用户ID和交易对查询
     */
    @Select("SELECT id, position_id, user_id, symbol, side, margin_mode, leverage, " +
            "isolated_margin, added_margin, reduced_margin, position_value, position_margin, " +
            "unrealized_pnl, maintenance_margin AS maintMargin, maintenance_margin_rate AS maintMarginRate, " +
            "quantity AS positionQty, entry_price, mark_price, liquidation_price, bankruptcy_price, " +
            "margin_ratio, liquidation_distance, max_add_position_qty, last_price, created_at, updated_at, version " +
            "FROM t_position_margin_detail WHERE user_id = #{userId} AND symbol = #{symbol}")
    List<PositionMarginDetail> selectByUserIdAndSymbol(@Param("userId") Long userId, @Param("symbol") String symbol);

    /**
     * 根据交易对查询所有活跃仓位（跨所有用户）
     */
    @Select("SELECT id, position_id, user_id, symbol, side, margin_mode, leverage, " +
            "isolated_margin, added_margin, reduced_margin, position_value, position_margin, " +
            "unrealized_pnl, maintenance_margin AS maintMargin, maintenance_margin_rate AS maintMarginRate, " +
            "quantity AS positionQty, entry_price, mark_price, liquidation_price, bankruptcy_price, " +
            "margin_ratio, liquidation_distance, max_add_position_qty, last_price, created_at, updated_at, version " +
            "FROM t_position_margin_detail WHERE symbol = #{symbol} ORDER BY user_id, position_id")
    List<PositionMarginDetail> selectBySymbol(@Param("symbol") String symbol);
    
    /**
     * 查询用户的全仓仓位
     */
    @Select("SELECT id, position_id, user_id, symbol, side, margin_mode, leverage, " +
            "isolated_margin, added_margin, reduced_margin, position_value, position_margin, " +
            "unrealized_pnl, maintenance_margin AS maintMargin, maintenance_margin_rate AS maintMarginRate, " +
            "quantity AS positionQty, entry_price, mark_price, liquidation_price, bankruptcy_price, " +
            "margin_ratio, liquidation_distance, max_add_position_qty, last_price, created_at, updated_at, version " +
            "FROM t_position_margin_detail WHERE user_id = #{userId} AND margin_mode = 'CROSS'")
    List<PositionMarginDetail> selectCrossPositions(@Param("userId") Long userId);
    
    /**
     * 查询用户的逐仓仓位
     */
    @Select("SELECT id, position_id, user_id, symbol, side, margin_mode, leverage, " +
            "isolated_margin, added_margin, reduced_margin, position_value, position_margin, " +
            "unrealized_pnl, maintenance_margin AS maintMargin, maintenance_margin_rate AS maintMarginRate, " +
            "quantity AS positionQty, entry_price, mark_price, liquidation_price, bankruptcy_price, " +
            "margin_ratio, liquidation_distance, max_add_position_qty, last_price, created_at, updated_at, version " +
            "FROM t_position_margin_detail WHERE user_id = #{userId} AND margin_mode = 'ISOLATED'")
    List<PositionMarginDetail> selectIsolatedPositions(@Param("userId") Long userId);
    
    /**
     * 查询高风险的逐仓仓位（保证金率低于阈值）
     */
    @Select("SELECT id, position_id, user_id, symbol, side, margin_mode, leverage, " +
            "isolated_margin, added_margin, reduced_margin, position_value, position_margin, " +
            "unrealized_pnl, maintenance_margin AS maintMargin, maintenance_margin_rate AS maintMarginRate, " +
            "quantity AS positionQty, entry_price, mark_price, liquidation_price, bankruptcy_price, " +
            "margin_ratio, liquidation_distance, max_add_position_qty, last_price, created_at, updated_at, version " +
            "FROM t_position_margin_detail WHERE margin_mode = 'ISOLATED' AND margin_ratio < #{threshold}")
    List<PositionMarginDetail> selectHighRiskIsolatedPositions(@Param("threshold") Long threshold);
    
    /**
     * 更新保证金模式（全仓/逐仓切换）
     */
    @Update("UPDATE t_position_margin_detail SET margin_mode = #{marginMode}, updated_at = #{updatedAt}, version = version + 1 " +
            "WHERE position_id = #{positionId} AND version = #{version}")
    int updateMarginMode(@Param("positionId") Long positionId, 
                         @Param("marginMode") String marginMode,
                         @Param("updatedAt") Long updatedAt,
                         @Param("version") Integer version);
    
    /**
     * 更新逐仓保证金（追加或减少）
     */
    @Update("UPDATE t_position_margin_detail SET isolated_margin = #{isolatedMargin}, added_margin = #{addedMargin}, " +
            "reduced_margin = #{reducedMargin}, position_margin = #{positionMargin}, liquidation_price = #{liquidationPrice}, " +
            "updated_at = #{updatedAt}, version = version + 1 " +
            "WHERE position_id = #{positionId} AND version = #{version}")
    int updateIsolatedMargin(@Param("positionId") Long positionId,
                             @Param("isolatedMargin") Long isolatedMargin,
                             @Param("addedMargin") Long addedMargin,
                             @Param("reducedMargin") Long reducedMargin,
                             @Param("positionMargin") Long positionMargin,
                             @Param("liquidationPrice") Long liquidationPrice,
                             @Param("updatedAt") Long updatedAt,
                             @Param("version") Integer version);
    
    /**
     * 更新仓位保证金和强平价
     */
    @Update("UPDATE t_position_margin_detail SET position_value = #{positionValue}, position_margin = #{positionMargin}, " +
            "unrealized_pnl = #{unrealizedPnl}, liquidation_price = #{liquidationPrice}, margin_ratio = #{marginRatio}, " +
            "mark_price = #{markPrice}, updated_at = #{updatedAt}, version = version + 1 " +
            "WHERE position_id = #{positionId} AND version = #{version}")
    int updatePositionMargin(@Param("positionId") Long positionId,
                             @Param("positionValue") Long positionValue,
                             @Param("positionMargin") Long positionMargin,
                             @Param("unrealizedPnl") Long unrealizedPnl,
                             @Param("liquidationPrice") Long liquidationPrice,
                             @Param("marginRatio") Long marginRatio,
                             @Param("markPrice") Long markPrice,
                             @Param("updatedAt") Long updatedAt,
                             @Param("version") Integer version);
    
    /**
     * 更新杠杆倍数
     */
    @Update("UPDATE t_position_margin_detail SET leverage = #{leverage}, position_margin = #{positionMargin}, " +
            "liquidation_price = #{liquidationPrice}, updated_at = #{updatedAt}, version = version + 1 " +
            "WHERE position_id = #{positionId} AND version = #{version}")
    int updateLeverage(@Param("positionId") Long positionId,
                       @Param("leverage") Integer leverage,
                       @Param("positionMargin") Long positionMargin,
                       @Param("liquidationPrice") Long liquidationPrice,
                       @Param("updatedAt") Long updatedAt,
                       @Param("version") Integer version);
    
    /**
     * 删除仓位保证金记录（平仓后）
     */
    int deleteByPositionId(@Param("positionId") Long positionId);
    
    /**
     * 批量插入
     */
    int batchInsert(@Param("list") List<PositionMarginDetail> list);
    
    /**
     * 统计用户的全仓仓位数量
     */
    @Select("SELECT COUNT(*) FROM t_position_margin_detail WHERE user_id = #{userId} AND margin_mode = 'CROSS'")
    Integer countCrossPositions(@Param("userId") Long userId);
    
    /**
     * 统计用户的逐仓仓位数量
     */
    @Select("SELECT COUNT(*) FROM t_position_margin_detail WHERE user_id = #{userId} AND margin_mode = 'ISOLATED'")
    Integer countIsolatedPositions(@Param("userId") Long userId);
}
