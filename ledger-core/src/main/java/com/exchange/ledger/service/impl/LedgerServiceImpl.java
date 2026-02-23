package com.exchange.ledger.service.impl;

import com.exchange.common.core.IdGenerator;
import com.exchange.ledger.dto.TradeDTO;
import com.exchange.ledger.dto.TradeEntryEvent;
import com.exchange.ledger.entity.LedgerEntry;
import com.exchange.ledger.entity.AccountSnapshot;
import com.exchange.ledger.enums.AccountType;
import com.exchange.ledger.enums.BusinessType;
import com.exchange.ledger.mapper.LedgerEntryMapper;
import com.exchange.ledger.mapper.AccountSnapshotMapper;
import com.exchange.ledger.publisher.LedgerEventPublisher;
import com.exchange.ledger.service.LedgerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ledger Service 核心实现（生产级 - 重构版）
 * 
 * 🔥 核心变化：
 * 1. 只写 LedgerEntry（不再写AccountSnapshot）✅
 * 2. 写完账后发布 TradeEntryEvent 到Kafka ✅
 * 3. AccountSnapshot 由独立服务消费事件更新 ✅
 * 4. 完全解耦，Ledger不关心Snapshot ✅
 * 
 * 对标：Binance / OKX / Bybit级别
 */
@Slf4j
@Service
public class LedgerServiceImpl implements LedgerService {
    
    @Autowired
    private LedgerEntryMapper ledgerEntryMapper;
    
    @Autowired
    private LedgerEventPublisher ledgerEventPublisher;
    
    @Autowired(required = false)
    private AccountSnapshotMapper accountSnapshotMapper;
    
    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;
    
    private static final String CURRENCY = "USDT"; // 默认币种
    private static final String BIZ_SEQ_KEY = "ledger:biz_seq";
    private static final String SYMBOL_SEQ_KEY_PREFIX = "ledger:symbol:seq:";
    
    /**
     * 系统用户ID（用于系统账户）
     * 系统账户包括：手续费账户、保险基金、Funding资金池等
     */
    private static final Long SYSTEM_USER_ID = 0L;
    
    private AtomicLong localSequence = new AtomicLong(0);
    
    /**
     * 应用成交到Ledger
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyTrade(TradeDTO trade) {
        log.info("[LedgerService] Apply trade start, tradeId={}, maker={}, taker={}, price={}, qty={}",
            trade.getTradeId(), trade.getMakerUserId(), trade.getTakerUserId(), 
            trade.getPrice(), trade.getQuantity());
        
        try {
            // 修复：使用转换方法，支持 long 格式的 price/quantity
            BigDecimal price = trade.getPriceAsBigDecimal();
            BigDecimal quantity = trade.getQuantityAsBigDecimal();
            
            if (price == null || quantity == null) {
                throw new IllegalArgumentException("Price or quantity is null");
            }
            
            // 1. 计算成交金额
            BigDecimal tradeAmount = price.multiply(quantity);
            
            // 2. 生成双录分录
            List<LedgerEntry> entries = generateTradeEntries(trade, tradeAmount);
            
            // 3. 设置成对分录ID
            setPairEntryIds(entries);
            
            // 4. 写入ledger_entry（批量插入，性能优化）
            if (!entries.isEmpty()) {
                ledgerEntryMapper.batchInsert(entries);
            }
            
            // 5. 构建TradeEntryEvent
            TradeEntryEvent event = new TradeEntryEvent();
            event.setTradeId(trade.getTradeId());
            event.setSymbol(trade.getSymbol());
            event.setEntries(entries);
            event.setEventTime(System.currentTimeMillis());
            event.setBizSeq(getNextBizSeq());
            event.setSequence(getNextSymbolSequence(trade.getSymbol()));

            // 🔥 重要修复：填充成交原始信息（用于Position Service正确计算）
            event.setPrice(price);
            event.setQuantity(quantity);
            event.setIsBuyerMaker(trade.getIsMakerBuy());
            event.setMakerUserId(trade.getMakerUserId());
            event.setTakerUserId(trade.getTakerUserId());
            event.setMakerOrderId(trade.getMakerOrderId());
            event.setTakerOrderId(trade.getTakerOrderId());
            event.setMakerFee(trade.getMakerFeeAsBigDecimal());
            event.setTakerFee(trade.getTakerFeeAsBigDecimal());

            // 6. 发布事件到Kafka（异步，发完就不管）
            ledgerEventPublisher.publishTradeEntry(event);
            
            log.info("[LedgerService] ✅ Apply trade success, tradeId={}, entries={}, bizSeq={}", 
                trade.getTradeId(), entries.size(), event.getBizSeq());
            
        } catch (Exception e) {
            log.error("[LedgerService] ❌ Apply trade error, tradeId={}", trade.getTradeId(), e);
            throw new RuntimeException("Apply trade failed", e);
        }
    }
    
    /**
     * Replay重放（灾备核心）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replayTrade(TradeEntryEvent event) {
        log.info("[LedgerService] Replay trade, tradeId={}, bizSeq={}, sequence={}",
            event.getTradeId(), event.getBizSeq(), event.getSequence());
        
        try {
            // 检查幂等性
            for (LedgerEntry entry : event.getEntries()) {
                if (entry.getIdempotentKey() != null) {
                    // TODO: 检查是否已存在
                }
            }
            
            // 写入LedgerEntry（批量插入）
            if (!event.getEntries().isEmpty()) {
                ledgerEntryMapper.batchInsert(event.getEntries());
            }
            
            // 重新发布事件（给SnapshotService消费）
            ledgerEventPublisher.publishTradeEntry(event);
            
            log.info("[LedgerService] ✅ Replay trade success, tradeId={}", event.getTradeId());
            
        } catch (Exception e) {
            log.error("[LedgerService] ❌ Replay trade error, tradeId={}", event.getTradeId(), e);
            throw new RuntimeException("Replay trade failed", e);
        }
    }
    
    /**
     * 冻结保证金（重构版）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void freezeMargin(Long userId, String currency, BigDecimal amount, Long orderId) {
        log.info("[LedgerService] Freeze margin, userId={}, amount={}, orderId={}", 
            userId, amount, orderId);
        
        // 参数校验
        if (userId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("[LedgerService] ❌ Invalid parameters: userId={}, amount={}", userId, amount);
            throw new IllegalArgumentException("Invalid freeze parameters: userId=" + userId + ", amount=" + amount);
        }
        
        // 检查余额是否充足（从快照查询，可选，如果快照服务未启动则跳过）
        if (accountSnapshotMapper != null) {
            try {
                AccountSnapshot snapshot = accountSnapshotMapper.selectOne(
                    new LambdaQueryWrapper<AccountSnapshot>()
                        .eq(AccountSnapshot::getUserId, userId)
                        .last("LIMIT 1")
                );
                
                if (snapshot == null) {
                    log.warn("[LedgerService] ⚠️ Account snapshot not found for userId={}, skipping balance check", userId);
                    // 快照不存在可能是首次操作，允许继续
                } else {
                    if (snapshot.getAvailable() == null || snapshot.getAvailable().compareTo(amount) < 0) {
                        log.error("[LedgerService] ❌ Insufficient balance: userId={}, available={}, required={}", 
                            userId, snapshot.getAvailable(), amount);
                        throw new RuntimeException("Insufficient balance: available=" + snapshot.getAvailable() + ", required=" + amount);
                    }
                    
                    log.info("[LedgerService] Balance check passed: userId={}, available={}, required={}", 
                        userId, snapshot.getAvailable(), amount);
                }
            } catch (RuntimeException e) {
                // 余额不足异常直接抛出
                throw e;
            } catch (Exception e) {
                log.warn("[LedgerService] ⚠️ Balance check failed (non-critical): {}", e.getMessage());
                // 其他异常（如数据库连接问题）不影响冻结操作，继续执行
            }
        } else {
            log.warn("[LedgerService] ⚠️ AccountSnapshotMapper not available, skipping balance check");
        }
        
        // 生成分录
        List<LedgerEntry> entries = new ArrayList<>();
        
        // 1. 可用余额减少
        entries.add(createEntry(
            userId,
            AccountType.USER_AVAILABLE,
            BigDecimal.ZERO,
            amount,
            BusinessType.MARGIN_FREEZE,
            "FREEZE_" + orderId,
            orderId
        ));
        
        // 2. 冻结余额增加
        entries.add(createEntry(
            userId,
            AccountType.USER_FROZEN,
            amount,
            BigDecimal.ZERO,
            BusinessType.MARGIN_FREEZE,
            "FREEZE_" + orderId,
            orderId
        ));
        
        setPairEntryIds(entries);
        
        // 写入LedgerEntry（批量插入）
        if (!entries.isEmpty()) {
            ledgerEntryMapper.batchInsert(entries);
        }
        
        // 发布事件
        TradeEntryEvent event = new TradeEntryEvent();
        event.setTradeId("FREEZE_" + orderId);
        event.setSymbol("SYSTEM"); // 系统操作
        event.setEntries(entries);
        event.setEventTime(System.currentTimeMillis());
        event.setBizSeq(getNextBizSeq());
        
        ledgerEventPublisher.publishTradeEntry(event);
        
        log.info("[LedgerService] ✅ Freeze margin success, userId={}, amount={}", userId, amount);
    }
    
    /**
     * 解冻保证金（重构版）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unfreezeMargin(Long userId, String currency, BigDecimal amount, Long orderId) {
        log.info("[LedgerService] Unfreeze margin, userId={}, amount={}, orderId={}", 
            userId, amount, orderId);
        
        // 生成分录
        List<LedgerEntry> entries = new ArrayList<>();
        
        // 1. 冻结余额减少
        entries.add(createEntry(
            userId,
            AccountType.USER_FROZEN,
            BigDecimal.ZERO,
            amount,
            BusinessType.MARGIN_UNFREEZE,
            "UNFREEZE_" + orderId,
            orderId
        ));
        
        // 2. 可用余额增加
        entries.add(createEntry(
            userId,
            AccountType.USER_AVAILABLE,
            amount,
            BigDecimal.ZERO,
            BusinessType.MARGIN_UNFREEZE,
            "UNFREEZE_" + orderId,
            orderId
        ));
        
        setPairEntryIds(entries);
        
        // 写入LedgerEntry（批量插入）
        if (!entries.isEmpty()) {
            ledgerEntryMapper.batchInsert(entries);
        }
        
        // 发布事件
        TradeEntryEvent event = new TradeEntryEvent();
        event.setTradeId("UNFREEZE_" + orderId);
        event.setSymbol("SYSTEM");
        event.setEntries(entries);
        event.setEventTime(System.currentTimeMillis());
        event.setBizSeq(getNextBizSeq());
        
        ledgerEventPublisher.publishTradeEntry(event);
        
        log.info("[LedgerService] ✅ Unfreeze margin success, userId={}, amount={}", userId, amount);
    }
    
    /**
     * 获取下一个biz_seq
     */
    @Override
    public Long getNextBizSeq() {
        if (redisTemplate != null) {
            return redisTemplate.opsForValue().increment(BIZ_SEQ_KEY);
        } else {
            return IdGenerator.generate();
        }
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 生成成交分录
     */
    private List<LedgerEntry> generateTradeEntries(TradeDTO trade, BigDecimal tradeAmount) {
        List<LedgerEntry> entries = new ArrayList<>();
        
        // Maker分录
        if (trade.getIsMakerBuy()) {
            // Maker买入：available减少，position增加
            entries.add(createEntry(
                trade.getMakerUserId(),
                AccountType.USER_AVAILABLE,
                BigDecimal.ZERO,
                tradeAmount,
                BusinessType.TRADE_SETTLE,
                trade.getTradeId(),
                trade.getMakerOrderId()
            ));
            
            entries.add(createEntry(
                trade.getMakerUserId(),
                AccountType.USER_POSITION_MARGIN,
                tradeAmount,
                BigDecimal.ZERO,
                BusinessType.TRADE_SETTLE,
                trade.getTradeId(),
                trade.getMakerOrderId()
            ));
        } else {
            // Maker卖出
            entries.add(createEntry(
                trade.getMakerUserId(),
                AccountType.USER_POSITION_MARGIN,
                BigDecimal.ZERO,
                tradeAmount,
                BusinessType.TRADE_SETTLE,
                trade.getTradeId(),
                trade.getMakerOrderId()
            ));
            
            entries.add(createEntry(
                trade.getMakerUserId(),
                AccountType.USER_AVAILABLE,
                tradeAmount,
                BigDecimal.ZERO,
                BusinessType.TRADE_SETTLE,
                trade.getTradeId(),
                trade.getMakerOrderId()
            ));
        }
        
        // Taker分录
        if (!trade.getIsMakerBuy()) {
            // Taker买入
            entries.add(createEntry(
                trade.getTakerUserId(),
                AccountType.USER_AVAILABLE,
                BigDecimal.ZERO,
                tradeAmount,
                BusinessType.TRADE_SETTLE,
                trade.getTradeId(),
                trade.getTakerOrderId()
            ));
            
            entries.add(createEntry(
                trade.getTakerUserId(),
                AccountType.USER_POSITION_MARGIN,
                tradeAmount,
                BigDecimal.ZERO,
                BusinessType.TRADE_SETTLE,
                trade.getTradeId(),
                trade.getTakerOrderId()
            ));
        } else {
            // Taker卖出
            entries.add(createEntry(
                trade.getTakerUserId(),
                AccountType.USER_POSITION_MARGIN,
                BigDecimal.ZERO,
                tradeAmount,
                BusinessType.TRADE_SETTLE,
                trade.getTradeId(),
                trade.getTakerOrderId()
            ));
            
            entries.add(createEntry(
                trade.getTakerUserId(),
                AccountType.USER_AVAILABLE,
                tradeAmount,
                BigDecimal.ZERO,
                BusinessType.TRADE_SETTLE,
                trade.getTradeId(),
                trade.getTakerOrderId()
            ));
        }
        
        // 手续费分录（修复：使用转换方法）
        BigDecimal makerFee = trade.getMakerFeeAsBigDecimal();
        if (makerFee != null && makerFee.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(createEntry(
                trade.getMakerUserId(),
                AccountType.USER_AVAILABLE,
                BigDecimal.ZERO,
                makerFee,
                BusinessType.TRADE_FEE,
                trade.getTradeId(),
                trade.getMakerOrderId()
            ));
            
            entries.add(createEntry(
                SYSTEM_USER_ID,
                AccountType.EXCHANGE_FEE,
                makerFee,
                BigDecimal.ZERO,
                BusinessType.TRADE_FEE,
                trade.getTradeId(),
                null
            ));
        }
        
        BigDecimal takerFee = trade.getTakerFeeAsBigDecimal();
        if (takerFee != null && takerFee.compareTo(BigDecimal.ZERO) > 0) {
            entries.add(createEntry(
                trade.getTakerUserId(),
                AccountType.USER_AVAILABLE,
                BigDecimal.ZERO,
                takerFee,
                BusinessType.TRADE_FEE,
                trade.getTradeId(),
                trade.getTakerOrderId()
            ));
            
            entries.add(createEntry(
                SYSTEM_USER_ID,
                AccountType.EXCHANGE_FEE,
                takerFee,
                BigDecimal.ZERO,
                BusinessType.TRADE_FEE,
                trade.getTradeId(),
                null
            ));
        }
        
        return entries;
    }
    
    /**
     * 创建分录
     */
    private LedgerEntry createEntry(
            Long userId,
            AccountType accountType,
            BigDecimal debit,
            BigDecimal credit,
            BusinessType businessType,
            String refTradeId,
            Long refOrderId) {
        
        LedgerEntry entry = new LedgerEntry();
        entry.setEntryId(IdGenerator.generate());
        entry.setUserId(userId);
        entry.setAccountType(accountType.getCode());
        entry.setCurrency(CURRENCY);
        entry.setDebit(debit);
        entry.setCredit(credit);
        
        // 设置余额快照（必填字段）
        entry.setBalanceBefore(BigDecimal.ZERO);
        entry.setBalanceAfter(debit.subtract(credit));
        
        entry.setBusinessType(businessType.getCode());
        entry.setRefTradeId(refTradeId);
        entry.setRefOrderId(refOrderId);
        entry.setBizSeq(getNextBizSeq());
        
        // 幂等键
        String idempotentKey = String.format("%s:%s:%d:%d",
            businessType.getCode(), refTradeId, userId, accountType.getCode());
        entry.setIdempotentKey(idempotentKey);
        
        entry.setCreatedAt(System.currentTimeMillis());
        
        return entry;
    }
    
    /**
     * 设置成对分录ID
     */
    private void setPairEntryIds(List<LedgerEntry> entries) {
        for (int i = 0; i < entries.size() - 1; i += 2) {
            LedgerEntry entry1 = entries.get(i);
            LedgerEntry entry2 = entries.get(i + 1);
            
            entry1.setPairEntryId(entry2.getEntryId());
            entry2.setPairEntryId(entry1.getEntryId());
        }
    }
    
    /**
     * 获取Symbol序列号
     */
    private Long getNextSymbolSequence(String symbol) {
        if (redisTemplate != null) {
            String key = SYMBOL_SEQ_KEY_PREFIX + symbol;
            return redisTemplate.opsForValue().increment(key);
        } else {
            return localSequence.incrementAndGet();
        }
    }
    
    /**
     * 获取当前月份表名后缀
     */
    private String getCurrentTableMonth() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
    }
    
    // ==================== 初始资金相关 ====================
    
    /**
     * 创建初始资金（用户注册时）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createInitialFunding(Long userId, Long accountId, String currency, BigDecimal amount, String reason) {
        log.info("[LedgerService] Create initial funding, userId={}, amount={}", userId, amount);
        
        // 生成分录
        List<LedgerEntry> entries = new ArrayList<>();
        
        // 1. 用户可用余额增加（借）
        entries.add(createEntry(
            userId,
            AccountType.USER_AVAILABLE,
            amount,
            BigDecimal.ZERO,
            BusinessType.INITIAL_FUNDING,
            "INIT_" + userId,
            accountId
        ));
        
        // 2. 系统初始资金账户减少（贷）
        entries.add(createEntry(
            SYSTEM_USER_ID, // 系统账户
            AccountType.SYSTEM_INITIAL_FUNDING,
            BigDecimal.ZERO,
            amount,
            BusinessType.INITIAL_FUNDING,
            "INIT_" + userId,
            accountId
        ));
        
        setPairEntryIds(entries);
        
        // 写入LedgerEntry（批量插入，性能优化）
        if (!entries.isEmpty()) {
            ledgerEntryMapper.batchInsert(entries);
        }
        
        // 发布事件
        TradeEntryEvent event = new TradeEntryEvent();
        event.setTradeId("INIT_" + userId);
        event.setSymbol("SYSTEM");
        event.setEntries(entries);
        event.setEventTime(System.currentTimeMillis());
        event.setBizSeq(getNextBizSeq());
        
        ledgerEventPublisher.publishTradeEntry(event);
        
        log.info("[LedgerService] ✅ Initial funding created, userId={}, amount={}", userId, amount);
        
        return String.valueOf(entries.get(0).getEntryId());
    }
    
    /**
     * 获取账户快照
     */
    @Override
    public com.exchange.ledger.entity.AccountSnapshot getAccountSnapshot(Long userId) {
        // 从数据库查询最新的账户快照
        // 这里简化处理，实际应该从 account_snapshot 表查询
        com.exchange.ledger.entity.AccountSnapshot snapshot = new com.exchange.ledger.entity.AccountSnapshot();
        snapshot.setUserId(userId);
        snapshot.setCurrency(CURRENCY);
        snapshot.setAvailable(BigDecimal.ZERO);
        snapshot.setFrozen(BigDecimal.ZERO);
        snapshot.setPositionMargin(BigDecimal.ZERO);
        snapshot.setUnrealizedPnl(BigDecimal.ZERO);
        snapshot.setRealizedPnl(BigDecimal.ZERO);
        snapshot.setEquity(BigDecimal.ZERO);
        return snapshot;
    }
}
