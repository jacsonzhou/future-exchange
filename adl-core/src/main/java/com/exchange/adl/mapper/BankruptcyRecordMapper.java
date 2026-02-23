package com.exchange.adl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.adl.entity.BankruptcyRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 穿仓记录Mapper
 */
@Mapper
public interface BankruptcyRecordMapper extends BaseMapper<BankruptcyRecord> {

    /**
     * 根据强平ID查询穿仓记录
     */
    BankruptcyRecord selectByLiquidationId(@Param("liquidationId") String liquidationId);

    /**
     * 查询待处理的穿仓记录
     */
    List<BankruptcyRecord> selectPendingRecords(@Param("limit") int limit);

    /**
     * 查询处理中的穿仓记录
     */
    List<BankruptcyRecord> selectProcessingRecords();

    /**
     * 根据交易对查询穿仓记录
     */
    List<BankruptcyRecord> selectBySymbol(@Param("symbol") String symbol, @Param("limit") int limit);

    /**
     * 查询用户穿仓记录
     */
    List<BankruptcyRecord> selectByUserId(@Param("userId") Long userId, @Param("limit") int limit);
}
