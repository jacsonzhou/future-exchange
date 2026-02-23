package com.exchange.adl.service;

import com.exchange.adl.entity.InsuranceFund;
import com.exchange.adl.entity.InsuranceFundLog;

import java.math.BigDecimal;
import java.util.List;

/**
 * 保险基金服务接口
 */
public interface InsuranceFundService {

    /**
     * 获取保险基金信息
     */
    InsuranceFund getInsuranceFund(String symbol, String currency);

    /**
     * 初始化保险基金
     */
    void initInsuranceFund(String symbol, String currency, BigDecimal initialAmount);

    /**
     * 保险基金收入
     *
     * @param symbol 交易对
     * @param currency 币种
     * @param amount 金额
     * @param changeType 变动类型
     * @param refId 关联ID
     * @param description 说明
     * @return 是否成功
     */
    boolean income(String symbol, String currency, BigDecimal amount,
                   String changeType, String refId, String description);

    /**
     * 保险基金支出
     *
     * @param symbol 交易对
     * @param currency 币种
     * @param amount 金额
     * @param changeType 变动类型
     * @param refId 关联ID
     * @param description 说明
     * @return 是否成功
     */
    boolean expense(String symbol, String currency, BigDecimal amount,
                    String changeType, String refId, String description);

    /**
     * 冻结保险基金
     */
    boolean freeze(String symbol, String currency, BigDecimal amount);

    /**
     * 解冻保险基金
     */
    void unfreeze(String symbol, String currency, BigDecimal amount);

    /**
     * 确认从冻结中支出
     */
    void confirmExpenseFromFrozen(String symbol, String currency, BigDecimal amount,
                                  String changeType, String refId, String description);

    /**
     * 检查保险基金是否充足
     *
     * @param symbol 交易对
     * @param currency 币种
     * @param requiredAmount 需要的金额
     * @return 是否充足
     */
    boolean isSufficient(String symbol, String currency, BigDecimal requiredAmount);

    /**
     * 获取保险基金余额
     */
    BigDecimal getBalance(String symbol, String currency);

    /**
     * 获取可用余额
     */
    BigDecimal getAvailableBalance(String symbol, String currency);

    /**
     * 查询保险基金流水
     */
    List<InsuranceFundLog> getLog(String symbol, int limit);

    /**
     * 查询保险基金流水（时间范围）
     */
    List<InsuranceFundLog> getLogByTimeRange(String symbol, Long startTime, Long endTime);

    /**
     * 检查并更新保险基金状态
     */
    void checkAndUpdateStatus(String symbol, String currency);

    /**
     * 重置日统计
     */
    void resetDailyStats();
}
