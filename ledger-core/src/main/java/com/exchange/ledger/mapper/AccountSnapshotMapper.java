package com.exchange.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.ledger.entity.AccountSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * Account Snapshot Mapper（生产级）
 * 
 * 核心功能：
 * 1. 查询账户快照（风控高频读取）
 * 2. 更新余额（乐观锁）
 * 3. 对账查询
 */
@Mapper
public interface AccountSnapshotMapper extends BaseMapper<AccountSnapshot> {
    
    /**
     * 乐观锁更新余额（并发安全）
     * 
     * 🔥 核心：使用version字段防止并发更新
     */
    @Update("UPDATE account_snapshot SET " +
            "available = #{available}, " +
            "frozen = #{frozen}, " +
            "position_margin = #{positionMargin}, " +
            "unrealized_pnl = #{unrealizedPnl}, " +
            "equity = #{equity}, " +
            "last_ledger_seq = #{lastLedgerSeq}, " +
            "last_ledger_entry_id = #{lastLedgerEntryId}, " +
            "version = version + 1, " +
            "checksum = #{checksum}, " +
            "updated_at = #{updatedAt} " +
            "WHERE user_id = #{userId} AND version = #{version}")
    int updateWithOptimisticLock(AccountSnapshot snapshot);
    
    /**
     * 增加可用余额（原子操作）
     */
    @Update("UPDATE account_snapshot SET " +
            "available = available + #{amount}, " +
            "equity = equity + #{amount}, " +
            "version = version + 1, " +
            "updated_at = #{updatedAt} " +
            "WHERE user_id = #{userId}")
    int increaseAvailable(
        @Param("userId") Long userId,
        @Param("amount") BigDecimal amount,
        @Param("updatedAt") Long updatedAt
    );
    
    /**
     * 减少可用余额（原子操作）
     */
    @Update("UPDATE account_snapshot SET " +
            "available = available - #{amount}, " +
            "equity = equity - #{amount}, " +
            "version = version + 1, " +
            "updated_at = #{updatedAt} " +
            "WHERE user_id = #{userId} AND available >= #{amount}")
    int decreaseAvailable(
        @Param("userId") Long userId,
        @Param("amount") BigDecimal amount,
        @Param("updatedAt") Long updatedAt
    );
    
    /**
     * 冻结保证金（可用 → 冻结）
     */
    @Update("UPDATE account_snapshot SET " +
            "available = available - #{amount}, " +
            "frozen = frozen + #{amount}, " +
            "version = version + 1, " +
            "updated_at = #{updatedAt} " +
            "WHERE user_id = #{userId} AND available >= #{amount}")
    int freezeMargin(
        @Param("userId") Long userId,
        @Param("amount") BigDecimal amount,
        @Param("updatedAt") Long updatedAt
    );
    
    /**
     * 解冻保证金（冻结 → 可用）
     */
    @Update("UPDATE account_snapshot SET " +
            "available = available + #{amount}, " +
            "frozen = frozen - #{amount}, " +
            "version = version + 1, " +
            "updated_at = #{updatedAt} " +
            "WHERE user_id = #{userId} AND frozen >= #{amount}")
    int unfreezeMargin(
        @Param("userId") Long userId,
        @Param("amount") BigDecimal amount,
        @Param("updatedAt") Long updatedAt
    );
    
    /**
     * 查询同步延迟大的账户（监控用）
     */
    @Select("SELECT * FROM account_snapshot " +
            "WHERE #{currentMaxSeq} - last_ledger_seq > #{lagThreshold} " +
            "ORDER BY last_ledger_seq ASC " +
            "LIMIT #{limit}")
    List<AccountSnapshot> selectLaggedAccounts(
        @Param("currentMaxSeq") Long currentMaxSeq,
        @Param("lagThreshold") Long lagThreshold,
        @Param("limit") Integer limit
    );
    
    /**
     * 查询所有用户ID（对账用）
     */
    @Select("SELECT user_id FROM account_snapshot")
    List<Long> selectAllUserIds();
    
    /**
     * 批量插入或更新
     */
    int batchInsertOrUpdate(@Param("list") List<AccountSnapshot> snapshots);
}

