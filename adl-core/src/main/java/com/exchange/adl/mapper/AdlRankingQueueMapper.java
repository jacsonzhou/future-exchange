package com.exchange.adl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.adl.entity.AdlRankingQueue;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * ADL Ranking Queue Mapper
 * 
 * 🔥 核心职责：
 * 1. 管理ADL排名队列的数据访问
 * 2. 提供按排名排序的查询
 * 3. 支持乐观锁更新
 * 
 * 🔥 关键查询：
 * - 按symbol和用户查询
 * - 按symbol和排名排序查询
 * - 查询ADL候选（盈利仓位）
 */
public interface AdlRankingQueueMapper extends BaseMapper<AdlRankingQueue> {
    
    /**
     * 根据用户ID和交易对查询ADL排名
     * 
     * @param userId 用户ID
     * @param symbol 交易对
     * @return ADL排名信息
     */
    @Select("SELECT * FROM adl_ranking_queue WHERE user_id = #{userId} AND symbol = #{symbol}")
    AdlRankingQueue selectByUserAndSymbol(@Param("userId") Long userId, @Param("symbol") String symbol);
    
    /**
     * 根据交易对查询所有ADL排名
     * 
     * @param symbol 交易对
     * @return ADL排名列表
     */
    @Select("SELECT * FROM adl_ranking_queue WHERE symbol = #{symbol}")
    List<AdlRankingQueue> selectBySymbol(@Param("symbol") String symbol);
    
    /**
     * 根据交易对查询ADL排名（按排名升序）
     * 
     * @param symbol 交易对
     * @param limit 限制数量
     * @return ADL排名列表
     */
    @Select("SELECT * FROM adl_ranking_queue WHERE symbol = #{symbol} ORDER BY adl_rank ASC LIMIT #{limit}")
    List<AdlRankingQueue> selectBySymbolOrderByRank(@Param("symbol") String symbol, @Param("limit") int limit);
    
    /**
     * 查询ADL候选（盈利的、指定方向的）
     * 
     * @param symbol 交易对
     * @param side 持仓方向
     * @param limit 限制数量
     * @return ADL候选列表（按ADL得分降序）
     */
    @Select("SELECT * FROM adl_ranking_queue " +
            "WHERE symbol = #{symbol} AND side = #{side} AND adl_score > 0 " +
            "ORDER BY adl_score DESC, position_created_at ASC " +
            "LIMIT #{limit}")
    List<AdlRankingQueue> selectCandidatesBySymbolAndSide(@Param("symbol") String symbol, 
                                                           @Param("side") String side, 
                                                           @Param("limit") int limit);
    
    /**
     * 使用乐观锁更新ADL排名
     * 
     * @param ranking ADL排名信息
     * @return 更新行数
     */
    @Update("UPDATE adl_ranking_queue SET " +
            "position_size = #{positionSize}, " +
            "entry_price = #{entryPrice}, " +
            "mark_price = #{markPrice}, " +
            "margin_balance = #{marginBalance}, " +
            "effective_leverage = #{effectiveLeverage}, " +
            "unrealized_pnl = #{unrealizedPnl}, " +
            "pnl_ratio = #{pnlRatio}, " +
            "adl_score = #{adlScore}, " +
            "adl_rank = #{adlRank}, " +
            "risk_level = #{riskLevel}, " +
            "rank_updated_at = #{rankUpdatedAt}, " +
            "updated_at = #{updatedAt} " +
            "WHERE id = #{id} AND version = #{version}")
    int updateWithOptimisticLock(AdlRankingQueue ranking);
    
    /**
     * 批量更新ADL排名（根据ADL得分重新排名）
     * 
     * @param symbol 交易对
     * @return 更新行数
     */
    @Update("SET @rank := 0; " +
            "UPDATE adl_ranking_queue " +
            "SET adl_rank = (@rank := @rank + 1) " +
            "WHERE symbol = #{symbol} AND adl_score > 0 " +
            "ORDER BY adl_score DESC, position_created_at ASC")
    int batchUpdateRankBySymbol(@Param("symbol") String symbol);
    
    /**
     * 删除无持仓的ADL排名记录
     * 
     * @param symbol 交易对
     * @return 删除行数
     */
    @Select("DELETE FROM adl_ranking_queue WHERE symbol = #{symbol} AND (position_size IS NULL OR position_size = 0)")
    int deleteEmptyPositionsBySymbol(@Param("symbol") String symbol);
    
    /**
     * 查询指定风险等级的ADL排名
     * 
     * @param symbol 交易对
     * @param riskLevel 风险等级（1-5）
     * @return ADL排名列表
     */
    @Select("SELECT * FROM adl_ranking_queue WHERE symbol = #{symbol} AND risk_level = #{riskLevel} ORDER BY adl_score DESC")
    List<AdlRankingQueue> selectBySymbolAndRiskLevel(@Param("symbol") String symbol, @Param("riskLevel") Integer riskLevel);
    
    /**
     * 统计指定symbol的ADL候选数量
     * 
     * @param symbol 交易对
     * @return 候选数量
     */
    @Select("SELECT COUNT(*) FROM adl_ranking_queue WHERE symbol = #{symbol} AND adl_score > 0")
    int countCandidatesBySymbol(@Param("symbol") String symbol);
}
