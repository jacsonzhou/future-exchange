package com.exchange.snapshot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.snapshot.entity.AccountSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Account Snapshot Mapper
 */
@Mapper
public interface AccountSnapshotMapper extends BaseMapper<AccountSnapshot> {
    
    /**
     * 乐观锁更新
     */
    @Update("UPDATE account_snapshot SET " +
            "available = #{available}, " +
            "frozen = #{frozen}, " +
            "position_margin = #{positionMargin}, " +
            "unrealized_pnl = #{unrealizedPnl}, " +
            "realized_pnl = #{realizedPnl}, " +
            "equity = #{equity}, " +
            "margin_ratio = #{marginRatio}, " +
            "last_biz_seq = #{lastBizSeq}, " +
            "last_entry_id = #{lastEntryId}, " +
            "last_trade_id = #{lastTradeId}, " +
            "version = version + 1, " +
            "updated_at = #{updatedAt} " +
            "WHERE user_id = #{userId} AND version = #{version}")
    int updateWithOptimisticLock(AccountSnapshot snapshot);
}

