package com.exchange.position.mapper;

import com.exchange.position.dto.AccountUpnlSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 账户快照镜像查询/更新（跨库只写 unrealizedPnl/equity）。
 */
@Mapper
public interface AccountSnapshotMirrorMapper {

    @Select("SELECT user_id AS userId, available, frozen, position_margin AS positionMargin, " +
            "unrealized_pnl AS unrealizedPnl, equity, version " +
            "FROM exchange_snapshot.account_snapshot WHERE user_id = #{userId}")
    AccountUpnlSnapshot selectByUserId(@Param("userId") Long userId);

    @Update("UPDATE exchange_snapshot.account_snapshot SET " +
            "unrealized_pnl = #{unrealizedPnl}, " +
            "equity = #{equity}, " +
            "version = version + 1, " +
            "updated_at = #{updatedAt} " +
            "WHERE user_id = #{userId} AND version = #{version}")
    int updateUnrealizedPnlWithOptimisticLock(@Param("userId") Long userId,
                                              @Param("unrealizedPnl") BigDecimal unrealizedPnl,
                                              @Param("equity") BigDecimal equity,
                                              @Param("updatedAt") Long updatedAt,
                                              @Param("version") Integer version);
}

