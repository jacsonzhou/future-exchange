package com.exchange.adl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.adl.entity.InsuranceFund;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Insurance Fund Mapper
 * 
 * 🔥 核心职责：
 * 1. 管理保险基金的数据访问
 * 2. 提供乐观锁更新
 * 3. 支持按状态查询
 */
public interface InsuranceFundMapper extends BaseMapper<InsuranceFund> {
    
    /**
     * 根据交易对和币种查询保险基金
     * 
     * @param symbol 交易对
     * @param currency 币种
     * @return 保险基金
     */
    @Select("SELECT * FROM insurance_fund WHERE symbol = #{symbol} AND currency = #{currency}")
    InsuranceFund selectBySymbolAndCurrency(@Param("symbol") String symbol, @Param("currency") String currency);
    
    /**
     * 根据币种查询所有保险基金
     * 
     * @param currency 币种
     * @return 保险基金列表
     */
    @Select("SELECT * FROM insurance_fund WHERE currency = #{currency}")
    List<InsuranceFund> selectByCurrency(@Param("currency") String currency);
    
    /**
     * 根据状态查询保险基金
     * 
     * @param status 状态
     * @return 保险基金列表
     */
    @Select("SELECT * FROM insurance_fund WHERE status = #{status}")
    List<InsuranceFund> selectByStatus(@Param("status") String status);
    
    /**
     * 查询所有危险的保险基金
     * 
     * @return 保险基金列表
     */
    @Select("SELECT * FROM insurance_fund WHERE status = 'DANGER'")
    List<InsuranceFund> selectDangerFunds();
    
    /**
     * 使用乐观锁更新保险基金
     * 
     * @param fund 保险基金
     * @return 更新行数
     */
    @Update("UPDATE insurance_fund SET " +
            "balance = #{balance}, " +
            "available_balance = #{availableBalance}, " +
            "frozen_amount = #{frozenAmount}, " +
            "total_income = #{totalIncome}, " +
            "total_expense = #{totalExpense}, " +
            "today_income = #{todayIncome}, " +
            "today_expense = #{todayExpense}, " +
            "today_date = #{todayDate}, " +
            "max_balance = #{maxBalance}, " +
            "status = #{status}, " +
            "last_operation_type = #{lastOperationType}, " +
            "last_operation_amount = #{lastOperationAmount}, " +
            "last_operation_at = #{lastOperationAt}, " +
            "last_operation_ref_id = #{lastOperationRefId}, " +
            "updated_at = #{updatedAt}, " +
            "version = version + 1 " +
            "WHERE id = #{id} AND version = #{version}")
    int updateWithOptimisticLock(InsuranceFund fund);
    
    /**
     * 更新保险基金状态
     * 
     * @param symbol 交易对
     * @param currency 币种
     * @param status 状态
     * @return 更新行数
     */
    @Update("UPDATE insurance_fund SET status = #{status}, updated_at = #{currentTime} " +
            "WHERE symbol = #{symbol} AND currency = #{currency}")
    int updateStatus(@Param("symbol") String symbol, 
                     @Param("currency") String currency, 
                     @Param("status") String status,
                     @Param("currentTime") Long currentTime);
    
    /**
     * 重置所有日统计（每日凌晨调用）
     * 
     * @param todayDate 今日日期
     * @return 更新行数
     */
    @Update("UPDATE insurance_fund SET today_income = 0, today_expense = 0, today_date = #{todayDate}")
    int resetDailyStats(@Param("todayDate") Integer todayDate);
    
    /**
     * 统计所有保险基金总额
     * 
     * @param currency 币种
     * @return 总额
     */
    @Select("SELECT COALESCE(SUM(balance), 0) FROM insurance_fund WHERE currency = #{currency}")
    java.math.BigDecimal sumBalanceByCurrency(@Param("currency") String currency);
}
