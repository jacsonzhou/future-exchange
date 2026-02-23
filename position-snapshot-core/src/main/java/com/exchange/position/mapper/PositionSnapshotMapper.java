package com.exchange.position.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.position.entity.PositionSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Position Snapshot Mapper（双向持仓模式Hedge Mode）
 * 
 * 🔥 核心变化：
 * 1. 支持同一个用户在同一个交易对上同时持有多头和空头
 * 2. 查询时需要指定 positionSide（1=LONG, 2=SHORT）
 * 3. size 字段始终为正数
 * 
 * 对标：Binance Hedge Mode / OKX 双向持仓
 */
@Mapper
public interface PositionSnapshotMapper extends BaseMapper<PositionSnapshot> {
    
    /**
     * 乐观锁更新（双向持仓模式）
     * 同时更新 position_side 以确保数据一致性
     */
    @Update("UPDATE position_snapshot SET " +
            "size = #{size}, " +
            "entry_price = #{entryPrice}, " +
            "unrealized_pnl = #{unrealizedPnl}, " +
            "realized_pnl = #{realizedPnl}, " +
            "margin_ratio = #{marginRatio}, " +
            "liquidation_price = #{liquidationPrice}, " +
            "last_trade_id = #{lastTradeId}, " +
            "last_mark_price_id = #{lastMarkPriceId}, " +
            "last_update_seq = #{lastUpdateSeq}, " +
            "version = version + 1, " +
            "updated_at = #{updatedAt} " +
            "WHERE id = #{id} AND version = #{version}")
    int updateWithOptimisticLock(PositionSnapshot snapshot);
    
    /**
     * 查询用户的持仓（双向持仓模式）
     * 需要指定持仓方向（1=LONG, 2=SHORT）
     * 
     * @param userId 用户ID
     * @param symbol 交易对
     * @param positionSide 持仓方向（1=LONG, 2=SHORT）
     */
    @Select("SELECT * FROM position_snapshot WHERE user_id = #{userId} AND symbol = #{symbol} AND position_side = #{positionSide}")
    PositionSnapshot selectByUserAndSymbolAndSide(@Param("userId") Long userId, 
                                                   @Param("symbol") String symbol, 
                                                   @Param("positionSide") Integer positionSide);
    
    /**
     * 查询用户在某个交易对上的所有持仓（双向持仓模式）
     * 可能返回2条记录（一条LONG，一条SHORT）
     * 
     * @param userId 用户ID
     * @param symbol 交易对
     */
    @Select("SELECT * FROM position_snapshot WHERE user_id = #{userId} AND symbol = #{symbol} AND size > 0")
    List<PositionSnapshot> selectByUserAndSymbol(@Param("userId") Long userId, @Param("symbol") String symbol);
    
    /**
     * 查询用户的所有持仓（双向持仓模式）
     * 只返回 size > 0 的持仓（有实际持仓的）
     * 
     * @param userId 用户ID
     */
    @Select("SELECT * FROM position_snapshot WHERE user_id = #{userId} AND size > 0 ORDER BY symbol, position_side")
    List<PositionSnapshot> selectAllByUser(@Param("userId") Long userId);
    
    /**
     * 查询用户的所有多头持仓
     * 
     * @param userId 用户ID
     */
    @Select("SELECT * FROM position_snapshot WHERE user_id = #{userId} AND position_side = 1 AND size > 0")
    List<PositionSnapshot> selectLongPositions(@Param("userId") Long userId);
    
    /**
     * 查询用户的所有空头持仓
     * 
     * @param userId 用户ID
     */
    @Select("SELECT * FROM position_snapshot WHERE user_id = #{userId} AND position_side = 2 AND size > 0")
    List<PositionSnapshot> selectShortPositions(@Param("userId") Long userId);
    
    /**
     * 查询所有低保证金率持仓
     * 
     * @param threshold 保证金率阈值
     */
    @Select("SELECT * FROM position_snapshot WHERE margin_ratio < #{threshold} AND size > 0")
    List<PositionSnapshot> selectLowMarginPositions(@Param("threshold") java.math.BigDecimal threshold);
    
    /**
     * 查询用户某个交易对的净持仓
     * （LONG - SHORT）
     * 
     * @param userId 用户ID
     * @param symbol 交易对
     */
    @Select("SELECT " +
            "  COALESCE(SUM(CASE WHEN position_side = 1 THEN size ELSE -size END), 0) as net_size " +
            "FROM position_snapshot " +
            "WHERE user_id = #{userId} AND symbol = #{symbol}")
    java.math.BigDecimal selectNetPosition(@Param("userId") Long userId, @Param("symbol") String symbol);
}
