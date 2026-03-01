package com.exchange.snapshot.mapper;

import com.exchange.snapshot.dto.PositionUpnlView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 持仓镜像查询 Mapper（跨库只读，用于账户总 UPNL 回补）。
 */
@Mapper
public interface PositionSnapshotMirrorMapper {

    @Select("SELECT symbol, position_side AS positionSide, size AS quantity, unrealized_pnl AS unrealizedPnl " +
            "FROM exchange_position.position_snapshot " +
            "WHERE user_id = #{userId} AND size > 0")
    List<PositionUpnlView> selectOpenPositionUpnl(@Param("userId") Long userId);

    @Select("SELECT COALESCE(SUM(unrealized_pnl), 0) " +
            "FROM exchange_position.position_snapshot " +
            "WHERE user_id = #{userId} AND size > 0")
    BigDecimal sumOpenUnrealizedPnl(@Param("userId") Long userId);
}
