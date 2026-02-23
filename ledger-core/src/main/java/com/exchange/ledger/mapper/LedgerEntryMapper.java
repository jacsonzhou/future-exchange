package com.exchange.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.ledger.entity.LedgerEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ledger Entry Mapper（生产级）
 * 
 * 核心功能：
 * 1. 写入双录分录
 * 2. Replay查询（按biz_seq排序）
 * 3. 余额计算
 * 4. 对账查询
 */
@Mapper
public interface LedgerEntryMapper extends BaseMapper<LedgerEntry> {
    
    /**
     * 按biz_seq范围查询（Replay核心）
     * 
     * 🔥 用途：
     * 1. Replay重建AccountSnapshot
     * 2. 增量同步
     * 3. 灾备恢复
     */
    @Select("SELECT * FROM ledger_entry_${tableMonth} " +
            "WHERE biz_seq >= #{startSeq} AND biz_seq <= #{endSeq} " +
            "ORDER BY biz_seq ASC " +
            "LIMIT #{limit}")
    List<LedgerEntry> selectByBizSeqRange(
        @Param("tableMonth") String tableMonth,
        @Param("startSeq") Long startSeq,
        @Param("endSeq") Long endSeq,
        @Param("limit") Integer limit
    );
    
    /**
     * 查询用户某个时间范围的分录
     */
    @Select("SELECT * FROM ledger_entry_${tableMonth} " +
            "WHERE user_id = #{userId} " +
            "AND created_at >= #{startTime} AND created_at <= #{endTime} " +
            "ORDER BY biz_seq ASC")
    List<LedgerEntry> selectByUserAndTimeRange(
        @Param("tableMonth") String tableMonth,
        @Param("userId") Long userId,
        @Param("startTime") Long startTime,
        @Param("endTime") Long endTime
    );
    
    /**
     * 计算用户余额（对账用）
     * 
     * 🔥 核心公式：
     * balance = SUM(debit) - SUM(credit)
     */
    @Select("SELECT " +
            "COALESCE(SUM(debit), 0) - COALESCE(SUM(credit), 0) AS balance " +
            "FROM ledger_entry_${tableMonth} " +
            "WHERE user_id = #{userId} " +
            "AND account_type = #{accountType} " +
            "AND currency = #{currency}")
    BigDecimal calculateBalance(
        @Param("tableMonth") String tableMonth,
        @Param("userId") Long userId,
        @Param("accountType") Integer accountType,
        @Param("currency") String currency
    );
    
    /**
     * 查询最大biz_seq（当前位点）
     */
    @Select("SELECT MAX(biz_seq) FROM ledger_entry_${tableMonth}")
    Long selectMaxBizSeq(@Param("tableMonth") String tableMonth);
    
    /**
     * 检查借贷平衡（对账核心）
     * 
     * 🔥 必须为0！
     */
    @Select("SELECT " +
            "COALESCE(SUM(debit), 0) - COALESCE(SUM(credit), 0) AS diff " +
            "FROM ledger_entry_${tableMonth}")
    BigDecimal checkDebitCreditBalance(@Param("tableMonth") String tableMonth);
    
    /**
     * 根据ref_trade_id查询分录
     */
    @Select("SELECT * FROM ledger_entry_${tableMonth} " +
            "WHERE ref_trade_id = #{refTradeId} " +
            "ORDER BY entry_id")
    List<LedgerEntry> selectByRefTradeId(
        @Param("tableMonth") String tableMonth,
        @Param("refTradeId") String refTradeId
    );
    
    /**
     * 查询成对分录
     */
    @Select("SELECT * FROM ledger_entry_${tableMonth} " +
            "WHERE entry_id = #{entryId} OR pair_entry_id = #{entryId}")
    List<LedgerEntry> selectPairEntries(
        @Param("tableMonth") String tableMonth,
        @Param("entryId") Long entryId
    );
    
    /**
     * 批量插入（性能优化）
     */
    int batchInsert(@Param("list") List<LedgerEntry> entries);
}

