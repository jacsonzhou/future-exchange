package com.exchange.ledger.job;

import com.exchange.ledger.entity.AccountSnapshot;
import com.exchange.ledger.entity.LedgerReconciliationLog;
import com.exchange.ledger.mapper.AccountSnapshotMapper;
import com.exchange.ledger.mapper.LedgerEntryMapper;
import com.exchange.ledger.mapper.LedgerReconciliationLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * Ledger Reconciliation Job 对账任务（生产级）
 * 
 * 🔥 核心职责：
 * 1. 每日对账：LedgerEntry vs AccountSnapshot
 * 2. 发现差异 → 告警
 * 3. 记录对账结果
 * 
 * 对标：Binance / OKX / Bybit级别
 */
@Slf4j
@Component
public class LedgerReconciliationJob {
    
    @Autowired
    private LedgerEntryMapper ledgerEntryMapper;
    
    @Autowired
    private AccountSnapshotMapper accountSnapshotMapper;
    
    @Autowired
    private LedgerReconciliationLogMapper reconciliationLogMapper;
    
    private static final String CURRENCY = "USDT";
    
    /**
     * 每日对账任务
     * 
     * 执行时间：每天凌晨2点
     * Cron: 0 0 2 * * ?
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void dailyReconciliation() {
        log.info("[ReconciliationJob] ========== Daily reconciliation start ==========");
        
        long startTime = System.currentTimeMillis();
        int totalUsers = 0;
        int diffCount = 0;
        
        try {
            // 1. 获取所有用户ID
            List<Long> allUserIds = accountSnapshotMapper.selectAllUserIds();
            totalUsers = allUserIds.size();
            
            log.info("[ReconciliationJob] Total users: {}", totalUsers);
            
            // 2. 逐个对账
            String tableMonth = getCurrentTableMonth();
            
            for (Long userId : allUserIds) {
                try {
                    boolean hasDiff = reconcileUser(userId, tableMonth);
                    if (hasDiff) {
                        diffCount++;
                    }
                } catch (Exception e) {
                    log.error("[ReconciliationJob] ❌ Reconcile user error, userId={}", userId, e);
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            
            log.info("[ReconciliationJob] ========== Daily reconciliation completed ==========");
            log.info("[ReconciliationJob] Total users: {}, Diff count: {}, Duration: {}ms",
                totalUsers, diffCount, duration);
            
            // 3. 告警（如果有差异）
            if (diffCount > 0) {
                log.error("[ReconciliationJob] ⚠️ ⚠️ ⚠️ Found {} users with balance diff!", diffCount);
                // TODO: 发送告警（邮件/钉钉/Slack）
            }
            
        } catch (Exception e) {
            log.error("[ReconciliationJob] ❌ Daily reconciliation error", e);
        }
    }
    
    /**
     * 对账单个用户
     * 
     * @return true if has diff
     */
    private boolean reconcileUser(Long userId, String tableMonth) {
        // 1. 从LedgerEntry计算余额
        BigDecimal ledgerAvailable = ledgerEntryMapper.calculateBalance(
            tableMonth, userId, 1, CURRENCY);
        BigDecimal ledgerFrozen = ledgerEntryMapper.calculateBalance(
            tableMonth, userId, 2, CURRENCY);
        BigDecimal ledgerPosition = ledgerEntryMapper.calculateBalance(
            tableMonth, userId, 3, CURRENCY);
        
        BigDecimal ledgerTotal = (ledgerAvailable != null ? ledgerAvailable : BigDecimal.ZERO)
            .add(ledgerFrozen != null ? ledgerFrozen : BigDecimal.ZERO)
            .add(ledgerPosition != null ? ledgerPosition : BigDecimal.ZERO);
        
        // 2. 从AccountSnapshot读取余额
        AccountSnapshot snapshot = accountSnapshotMapper.selectById(userId);
        if (snapshot == null) {
            log.warn("[ReconciliationJob] Snapshot not found, userId={}", userId);
            return false;
        }
        
        BigDecimal snapshotTotal = snapshot.getAvailable()
            .add(snapshot.getFrozen())
            .add(snapshot.getPositionMargin());
        
        // 3. 计算差异
        BigDecimal diff = ledgerTotal.subtract(snapshotTotal);
        
        // 4. 记录对账结果
        LedgerReconciliationLog ledgerReconciliationLog = new LedgerReconciliationLog();
        ledgerReconciliationLog.setCheckDate(Date.valueOf(LocalDate.now()));
        ledgerReconciliationLog.setUserId(userId);
        ledgerReconciliationLog.setCurrency(CURRENCY);
        ledgerReconciliationLog.setLedgerBalance(ledgerTotal);
        ledgerReconciliationLog.setSnapshotBalance(snapshotTotal);
        ledgerReconciliationLog.setDiffAmount(diff);
        ledgerReconciliationLog.setStatus(diff.abs().compareTo(new BigDecimal("0.00000001")) < 0 ? 0 : 1);
        ledgerReconciliationLog.setCheckStartSeq(0L);
        ledgerReconciliationLog.setCheckEndSeq(0L);
        ledgerReconciliationLog.setCreatedAt(System.currentTimeMillis());
        
        reconciliationLogMapper.insert(ledgerReconciliationLog);
        
        // 5. 检查是否有差异
        if (ledgerReconciliationLog.getStatus() == 1) {
            log.error("[ReconciliationJob] ❌ Balance diff found! userId={}, ledger={}, snapshot={}, diff={}",
                userId, ledgerTotal, snapshotTotal, diff);
            
            // TODO: 冻结账户
            // TODO: 发送告警
            
            return true;
        }
        
        return false;
    }
    
    /**
     * 检查借贷平衡（每小时）
     * 
     * 🔥 核心校验：SUM(debit) - SUM(credit) 必须为0！
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void checkDebitCreditBalance() {
        log.info("[ReconciliationJob] Check debit-credit balance start");
        
        try {
            String tableMonth = getCurrentTableMonth();
            BigDecimal diff = ledgerEntryMapper.checkDebitCreditBalance(tableMonth);
            
            if (diff != null && diff.abs().compareTo(new BigDecimal("0.00000001")) > 0) {
                log.error("[ReconciliationJob] ❌ ❌ ❌ Debit-Credit imbalance! diff={}", diff);
                // TODO: 紧急告警！
            } else {
                log.info("[ReconciliationJob] ✅ Debit-Credit balanced");
            }
            
        } catch (Exception e) {
            log.error("[ReconciliationJob] ❌ Check debit-credit balance error", e);
        }
    }
    
    /**
     * 获取当前月份表名后缀
     */
    private String getCurrentTableMonth() {
        return LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
    }
}

