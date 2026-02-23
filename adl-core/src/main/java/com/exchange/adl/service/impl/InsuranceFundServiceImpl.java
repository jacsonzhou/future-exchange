package com.exchange.adl.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exchange.adl.entity.InsuranceFund;
import com.exchange.adl.entity.InsuranceFundLog;
import com.exchange.adl.mapper.InsuranceFundLogMapper;
import com.exchange.adl.mapper.InsuranceFundMapper;
import com.exchange.adl.service.InsuranceFundService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 保险基金服务实现
 */
@Slf4j
@Service
public class InsuranceFundServiceImpl implements InsuranceFundService {

    @Autowired
    private InsuranceFundMapper insuranceFundMapper;

    @Autowired
    private InsuranceFundLogMapper insuranceFundLogMapper;

    @Override
    public InsuranceFund getInsuranceFund(String symbol, String currency) {
        LambdaQueryWrapper<InsuranceFund> query = new LambdaQueryWrapper<>();
        query.eq(InsuranceFund::getSymbol, symbol)
             .eq(InsuranceFund::getCurrency, currency);
        return insuranceFundMapper.selectOne(query);
    }

    @Override
    @Transactional
    public void initInsuranceFund(String symbol, String currency, BigDecimal initialAmount) {
        InsuranceFund fund = getInsuranceFund(symbol, currency);
        if (fund != null) {
            log.warn("Insurance fund already exists: symbol={}, currency={}", symbol, currency);
            return;
        }

        fund = new InsuranceFund();
        fund.setSymbol(symbol);
        fund.setCurrency(currency);
        fund.setBalance(initialAmount);
        fund.setAvailableBalance(initialAmount);
        fund.setFrozenAmount(BigDecimal.ZERO);
        fund.setTotalIncome(initialAmount);
        fund.setTotalExpense(BigDecimal.ZERO);
        fund.setTotalLiquidationFeeIncome(BigDecimal.ZERO);
        fund.setTotalLiquidationProfitInjection(BigDecimal.ZERO);
        fund.setTotalDebtLossExpense(BigDecimal.ZERO);
        fund.setTotalAdlCompensation(BigDecimal.ZERO);
        fund.setTodayIncome(BigDecimal.ZERO);
        fund.setTodayExpense(BigDecimal.ZERO);
        fund.setTodayDate(getCurrentDate());
        fund.setMaxBalance(initialAmount);
        fund.setMinBalance(initialAmount);

        // 设置阈值（默认值）
        fund.setSafeThreshold(new BigDecimal("1000000")); // 100万
        fund.setWarningThreshold(new BigDecimal("500000")); // 50万
        fund.setDangerThreshold(new BigDecimal("100000")); // 10万

        fund.setStatus("SAFE");
        fund.setVersion(0);

        Long now = System.currentTimeMillis();
        fund.setCreatedAt(now);
        fund.setUpdatedAt(now);

        insuranceFundMapper.insert(fund);

        // 记录初始化流水
        createLog(symbol, currency, initialAmount, fund.getBalance(), fund.getBalance(),
                  "PLATFORM_SUBSIDY", "INIT", null, "初始化保险基金");

        log.info("Insurance fund initialized: symbol={}, currency={}, amount={}",
                symbol, currency, initialAmount);
    }

    @Override
    @Transactional
    public boolean income(String symbol, String currency, BigDecimal amount,
                          String changeType, String refId, String description) {
        InsuranceFund fund = getInsuranceFundForUpdate(symbol, currency);
        if (fund == null) {
            log.error("Insurance fund not found: symbol={}, currency={}", symbol, currency);
            return false;
        }

        BigDecimal balanceBefore = fund.getBalance();

        // 检查并重置日统计
        fund.checkAndResetDailyStats(getCurrentDate());

        // 收入
        fund.income(amount, changeType, refId);
        fund.setUpdatedAt(System.currentTimeMillis());

        insuranceFundMapper.updateById(fund);

        // 记录流水
        createLog(symbol, currency, amount, balanceBefore, fund.getBalance(),
                  changeType, refId, null, description);

        log.info("Insurance fund income: symbol={}, amount={}, type={}, balance={}",
                symbol, amount, changeType, fund.getBalance());

        return true;
    }

    @Override
    @Transactional
    public boolean expense(String symbol, String currency, BigDecimal amount,
                           String changeType, String refId, String description) {
        InsuranceFund fund = getInsuranceFundForUpdate(symbol, currency);
        if (fund == null) {
            log.error("Insurance fund not found: symbol={}, currency={}", symbol, currency);
            return false;
        }

        BigDecimal balanceBefore = fund.getBalance();

        // 检查并重置日统计
        fund.checkAndResetDailyStats(getCurrentDate());

        // 支出
        boolean success = fund.expense(amount, changeType, refId);
        if (!success) {
            log.warn("Insufficient insurance fund: symbol={}, required={}, available={}",
                    symbol, amount, fund.getAvailableBalance());
            return false;
        }

        fund.setUpdatedAt(System.currentTimeMillis());
        insuranceFundMapper.updateById(fund);

        // 记录流水
        createLog(symbol, currency, amount.negate(), balanceBefore, fund.getBalance(),
                  changeType, refId, null, description);

        log.info("Insurance fund expense: symbol={}, amount={}, type={}, balance={}",
                symbol, amount, changeType, fund.getBalance());

        return true;
    }

    @Override
    @Transactional
    public boolean freeze(String symbol, String currency, BigDecimal amount) {
        InsuranceFund fund = getInsuranceFundForUpdate(symbol, currency);
        if (fund == null) {
            return false;
        }

        boolean success = fund.freeze(amount);
        if (success) {
            fund.setUpdatedAt(System.currentTimeMillis());
            insuranceFundMapper.updateById(fund);
            log.info("Insurance fund frozen: symbol={}, amount={}", symbol, amount);
        }

        return success;
    }

    @Override
    @Transactional
    public void unfreeze(String symbol, String currency, BigDecimal amount) {
        InsuranceFund fund = getInsuranceFundForUpdate(symbol, currency);
        if (fund == null) {
            return;
        }

        fund.unfreeze(amount);
        fund.setUpdatedAt(System.currentTimeMillis());
        insuranceFundMapper.updateById(fund);

        log.info("Insurance fund unfrozen: symbol={}, amount={}", symbol, amount);
    }

    @Override
    @Transactional
    public void confirmExpenseFromFrozen(String symbol, String currency, BigDecimal amount,
                                         String changeType, String refId, String description) {
        InsuranceFund fund = getInsuranceFundForUpdate(symbol, currency);
        if (fund == null) {
            return;
        }

        BigDecimal balanceBefore = fund.getBalance();

        fund.confirmExpenseFromFrozen(amount);
        fund.setUpdatedAt(System.currentTimeMillis());
        insuranceFundMapper.updateById(fund);

        // 记录流水
        createLog(symbol, currency, amount.negate(), balanceBefore, fund.getBalance(),
                  changeType, refId, null, description);

        log.info("Insurance fund confirmed expense from frozen: symbol={}, amount={}", symbol, amount);
    }

    @Override
    public boolean isSufficient(String symbol, String currency, BigDecimal requiredAmount) {
        InsuranceFund fund = getInsuranceFund(symbol, currency);
        if (fund == null) {
            return false;
        }
        return fund.getAvailableBalance().compareTo(requiredAmount) >= 0;
    }

    @Override
    public BigDecimal getBalance(String symbol, String currency) {
        InsuranceFund fund = getInsuranceFund(symbol, currency);
        return fund != null ? fund.getBalance() : BigDecimal.ZERO;
    }

    @Override
    public BigDecimal getAvailableBalance(String symbol, String currency) {
        InsuranceFund fund = getInsuranceFund(symbol, currency);
        return fund != null ? fund.getAvailableBalance() : BigDecimal.ZERO;
    }

    @Override
    public List<InsuranceFundLog> getLog(String symbol, int limit) {
        return insuranceFundLogMapper.selectBySymbol(symbol, limit);
    }

    @Override
    public List<InsuranceFundLog> getLogByTimeRange(String symbol, Long startTime, Long endTime) {
        return insuranceFundLogMapper.selectByTimeRange(symbol, startTime, endTime);
    }

    @Override
    @Transactional
    public void checkAndUpdateStatus(String symbol, String currency) {
        InsuranceFund fund = getInsuranceFundForUpdate(symbol, currency);
        if (fund == null) {
            return;
        }

        String oldStatus = fund.getStatus();
        fund.setUpdatedAt(System.currentTimeMillis());
        insuranceFundMapper.updateById(fund);

        String newStatus = fund.getStatus();
        if (!oldStatus.equals(newStatus)) {
            log.warn("Insurance fund status changed: symbol={}, {} -> {}, balance={}",
                    symbol, oldStatus, newStatus, fund.getBalance());
        }
    }

    @Override
    @Transactional
    public void resetDailyStats() {
        Integer currentDate = getCurrentDate();
        List<InsuranceFund> funds = insuranceFundMapper.selectList(null);

        for (InsuranceFund fund : funds) {
            if (!currentDate.equals(fund.getTodayDate())) {
                fund.checkAndResetDailyStats(currentDate);
                fund.setUpdatedAt(System.currentTimeMillis());
                insuranceFundMapper.updateById(fund);

                log.info("Reset daily stats for insurance fund: symbol={}, date={}",
                        fund.getSymbol(), currentDate);
            }
        }
    }

    /**
     * 获取保险基金并加锁
     */
    private InsuranceFund getInsuranceFundForUpdate(String symbol, String currency) {
        LambdaQueryWrapper<InsuranceFund> query = new LambdaQueryWrapper<>();
        query.eq(InsuranceFund::getSymbol, symbol)
             .eq(InsuranceFund::getCurrency, currency);
        return insuranceFundMapper.selectOne(query);
    }

    /**
     * 创建流水记录
     */
    private void createLog(String symbol, String currency, BigDecimal amount,
                          BigDecimal balanceBefore, BigDecimal balanceAfter,
                          String changeType, String refId, Long refUserId,
                          String description) {
        InsuranceFundLog log = new InsuranceFundLog();
        log.setSymbol(symbol);
        log.setCurrency(currency);
        log.setChangeType(changeType);
        log.setAmount(amount);
        log.setBalanceBefore(balanceBefore);
        log.setBalanceAfter(balanceAfter);
        log.setRefId(refId);
        log.setRefType(changeType);
        log.setRefUserId(refUserId);
        log.setDescription(description);
        log.setCreatedAt(System.currentTimeMillis());

        insuranceFundLogMapper.insert(log);
    }

    /**
     * 获取当前日期（yyyyMMdd）
     */
    private Integer getCurrentDate() {
        LocalDate now = LocalDate.now();
        return Integer.parseInt(now.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
    }
}
