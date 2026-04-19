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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

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
    private com.exchange.ledger.client.PositionSnapshotClient positionSnapshotClient;

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
    
    /**
     * 金额精度系数：8位小数 = 10^8
     * 用于统一处理扩大后的金额格式
     */
    private static final BigDecimal SCALE = new BigDecimal("100000000");
    private static final BigDecimal SCALE_THRESHOLD = new BigDecimal("10000000"); // 1000万，用于判断金额格式
    
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

            // 脏消息防御：maker/taker 缺失会导致分录 user_id 为空并触发无限重试
            // 该类消息无法入账，直接跳过并提交消费位点，避免阻塞正常交易流水。
            if (trade.getMakerUserId() == null || trade.getTakerUserId() == null) {
                log.error("[LedgerService] Skip invalid trade event, tradeId={}, makerUserId={}, takerUserId={}",
                    trade.getTradeId(), trade.getMakerUserId(), trade.getTakerUserId());
                return;
            }

            validateCfdCounterparty(trade);
            
            // 1. 生成双录分录
            List<LedgerEntry> entries = generateTradeEntries(trade, price, quantity);
            ensureUniqueIdempotentKeys(entries);
            
            // 🔥 幂等性检查：过滤掉已存在的分录
            entries = filterExistingEntries(entries);
            if (entries.isEmpty()) {
                log.info("[LedgerService] Trade already processed, skip duplicate, tradeId={}", trade.getTradeId());
                return;
            }
            
            // 3. 设置成对分录ID
            setPairEntryIds(entries);
            fillRealBalances(entries);
            
            // 4. 写入ledger_entry（批量插入，性能优化）
            if (!entries.isEmpty()) {
                try {
                    ledgerEntryMapper.batchInsert(entries);
                } catch (DuplicateKeyException e) {
                    // 🔥 幂等性保证：如果发生冲突，说明这笔交易已经被处理过了
                    // 这种情况发生在并发消费或Kafka重试时
                    log.warn("[LedgerService] Duplicate key detected for tradeId={}, message={}, skip as idempotent success", 
                        trade.getTradeId(), e.getMessage());
                    return; // 视为处理成功
                }
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
            event.setExecutionMode(trade.getExecutionMode());
            event.setLiquiditySource(trade.getLiquiditySource());
            event.setDealerAccountId(trade.getDealerAccountId());
            event.setReferenceTopic(trade.getReferenceTopic());
            event.setReferenceOffset(trade.getReferenceOffset());
            event.setReferenceEventTime(trade.getReferenceEventTime());

            // 6. 发布事件到Kafka（异步，发完就不管）
            ledgerEventPublisher.publishTradeEntry(event);
            
            log.info("[LedgerService] ✅ Apply trade success, tradeId={}, entries={}, bizSeq={}", 
                trade.getTradeId(), entries.size(), event.getBizSeq());
            
        } catch (Exception e) {
            log.error("[LedgerService] ❌ Apply trade error, tradeId={}", trade.getTradeId(), e);
            throw new RuntimeException("Apply trade failed", e);
        }
    }

    private void validateCfdCounterparty(TradeDTO trade) {
        if (!"CFD_DEALER".equalsIgnoreCase(trade.getExecutionMode())) {
            return;
        }
        Long dealerAccountId = trade.getDealerAccountId();
        if (dealerAccountId == null) {
            throw new IllegalArgumentException("CFD trade missing dealerAccountId, tradeId=" + trade.getTradeId());
        }
        boolean dealerOnMaker = dealerAccountId.equals(trade.getMakerUserId());
        boolean dealerOnTaker = dealerAccountId.equals(trade.getTakerUserId());
        if (!dealerOnMaker && !dealerOnTaker) {
            throw new IllegalArgumentException(
                "CFD trade missing dealer counterparty user, tradeId=" + trade.getTradeId() +
                    ", dealerAccountId=" + dealerAccountId +
                    ", makerUserId=" + trade.getMakerUserId() +
                    ", takerUserId=" + trade.getTakerUserId()
            );
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
     * 
     * 🔥 金额格式处理：
     * - 如果 amount > 1000万，认为是扩大后的金额（×10^8），需要除以 10^8 转换为实际金额
     * - 否则，直接使用实际金额
     * 这样可以兼容 OMS 传入的两种格式
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void freezeMargin(Long userId, String currency, BigDecimal amount, Long orderId) {
        // 🔥 统一金额格式：将扩大后的金额转换为实际金额
        BigDecimal actualAmount = normalizeAmount(amount);
        
        log.info("[LedgerService] Freeze margin, userId={}, rawAmount={}, actualAmount={}, orderId={}", 
            userId, amount, actualAmount, orderId);
        
        // 参数校验
        if (userId == null || actualAmount == null || actualAmount.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("[LedgerService] ❌ Invalid parameters: userId={}, amount={}", userId, actualAmount);
            throw new IllegalArgumentException("Invalid freeze parameters: userId=" + userId + ", amount=" + actualAmount);
        }
        
        // 🔥 余额检查：优先基于 Ledger 真相源精确计算，snapshot 仅作为快速缓存参考
        BigDecimal ledgerAvailable = getAvailableBalanceFromLedger(userId);
        if (ledgerAvailable != null) {
            if (ledgerAvailable.compareTo(actualAmount) < 0) {
                log.error("[LedgerService] ❌ Insufficient balance (ledger truth): userId={}, available={}, required={}",
                    userId, ledgerAvailable, actualAmount);
                throw new RuntimeException("Insufficient balance: available=" + ledgerAvailable + ", required=" + actualAmount);
            }
            log.info("[LedgerService] Balance check passed (ledger truth): userId={}, available={}, required={}",
                userId, ledgerAvailable, actualAmount);
        } else {
            // Ledger 计算失败时，尝试用 snapshot 做快速兜底检查
            if (accountSnapshotMapper != null) {
                try {
                    AccountSnapshot snapshot = accountSnapshotMapper.selectOne(
                        new LambdaQueryWrapper<AccountSnapshot>()
                            .eq(AccountSnapshot::getUserId, userId)
                            .last("LIMIT 1")
                    );
                    if (snapshot != null && snapshot.getAvailable() != null
                            && snapshot.getAvailable().compareTo(actualAmount) < 0) {
                        log.error("[LedgerService] ❌ Insufficient balance (snapshot fallback): userId={}, available={}, required={}",
                            userId, snapshot.getAvailable(), actualAmount);
                        throw new RuntimeException("Insufficient balance: available=" + snapshot.getAvailable() + ", required=" + actualAmount);
                    }
                    log.warn("[LedgerService] ⚠️ Ledger balance calc failed, using snapshot fallback for userId={}", userId);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    log.warn("[LedgerService] ⚠️ Snapshot fallback also failed, proceed without balance check: {}", e.getMessage());
                }
            } else {
                log.warn("[LedgerService] ⚠️ Cannot verify balance (ledger failed & snapshot unavailable), proceed anyway");
            }
        }
        
        // 生成分录
        List<LedgerEntry> entries = new ArrayList<>();
        
        // 1. 可用余额减少
        entries.add(createEntry(
            userId,
            AccountType.USER_AVAILABLE,
            BigDecimal.ZERO,
            actualAmount,
            BusinessType.MARGIN_FREEZE,
            "FREEZE_" + orderId,
            orderId
        ));
        
        // 2. 冻结余额增加
        entries.add(createEntry(
            userId,
            AccountType.USER_FROZEN,
            actualAmount,
            BigDecimal.ZERO,
            BusinessType.MARGIN_FREEZE,
            "FREEZE_" + orderId,
            orderId
        ));
        
        setPairEntryIds(entries);
        fillRealBalances(entries);
        
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
        
        log.info("[LedgerService] ✅ Freeze margin success, userId={}, actualAmount={}", userId, actualAmount);
    }
    
    /**
     * 解冻保证金（重构版）
     * 
     * 🔥 金额格式处理：
     * - 如果 amount > 1000万，认为是扩大后的金额（×10^8），需要除以 10^8 转换为实际金额
     * - 否则，直接使用实际金额
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unfreezeMargin(Long userId, String currency, BigDecimal amount, Long orderId) {
        // 🔥 统一金额格式：将扩大后的金额转换为实际金额
        BigDecimal actualAmount = normalizeAmount(amount);
        
        log.info("[LedgerService] Unfreeze margin, userId={}, rawAmount={}, actualAmount={}, orderId={}", 
            userId, amount, actualAmount, orderId);
        
        // 生成分录
        List<LedgerEntry> entries = new ArrayList<>();
        
        // 1. 冻结余额减少
        entries.add(createEntry(
            userId,
            AccountType.USER_FROZEN,
            BigDecimal.ZERO,
            actualAmount,
            BusinessType.MARGIN_UNFREEZE,
            "UNFREEZE_" + orderId,
            orderId
        ));
        
        // 2. 可用余额增加
        entries.add(createEntry(
            userId,
            AccountType.USER_AVAILABLE,
            actualAmount,
            BigDecimal.ZERO,
            BusinessType.MARGIN_UNFREEZE,
            "UNFREEZE_" + orderId,
            orderId
        ));
        
        setPairEntryIds(entries);
        fillRealBalances(entries);
        
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
        
        log.info("[LedgerService] ✅ Unfreeze margin success, userId={}, actualAmount={}", userId, actualAmount);
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
     * 🔥 幂等性检查：过滤掉已存在的分录
     * 
     * 通过检查 idempotent_key 是否已存在，防止重复消费 Kafka 消息
     * 
     * @param entries 待插入的分录列表
     * @return 过滤后的分录列表（已存在的将被移除）
     */
    private List<LedgerEntry> filterExistingEntries(List<LedgerEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return entries;
        }
        
        List<LedgerEntry> filtered = new ArrayList<>();
        for (LedgerEntry entry : entries) {
            if (entry.getIdempotentKey() == null) {
                filtered.add(entry);
                continue;
            }
            
            // 检查是否已存在
            try {
                Long count = ledgerEntryMapper.selectCount(
                    new LambdaQueryWrapper<LedgerEntry>()
                        .eq(LedgerEntry::getIdempotentKey, entry.getIdempotentKey())
                );
                if (count == null || count == 0) {
                    filtered.add(entry);
                } else {
                    log.warn("[LedgerService] Entry already exists, skip, idempotentKey={}", 
                        entry.getIdempotentKey());
                }
            } catch (Exception e) {
                // 查询失败时，保守处理：允许插入（依赖数据库唯一约束）
                log.warn("[LedgerService] Idempotency check failed, will try insert, key={}", 
                    entry.getIdempotentKey());
                filtered.add(entry);
            }
        }
        
        return filtered;
    }
    
    /**
     * 生成成交分录
     *
     * Net Mode 下根据持仓拆分平仓/开仓分录。
     * - 平仓：position_margin → available
     * - 开仓：available → position_margin
     */
    private List<LedgerEntry> generateTradeEntries(TradeDTO trade, BigDecimal price, BigDecimal quantity) {
        List<LedgerEntry> entries = new ArrayList<>();

        // Maker 分录
        entries.addAll(generateUserTradeEntries(
            trade.getMakerUserId(), trade.getMakerOrderId(), trade.getTradeId(),
            trade.getIsMakerBuy(), price, quantity, trade.getMakerLeverageOrDefault(), trade.getSymbol()
        ));

        // Taker 分录
        entries.addAll(generateUserTradeEntries(
            trade.getTakerUserId(), trade.getTakerOrderId(), trade.getTradeId(),
            !trade.getIsMakerBuy(), price, quantity, trade.getTakerLeverageOrDefault(), trade.getSymbol()
        ));

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
                trade.getMakerOrderId()
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
                trade.getTakerOrderId()
            ));
        }
        
        return entries;
    }

    /**
     * 兼容历史 idempotent key 规则，并只在同一批分录发生冲突时做最小化去重。
     * 这样不会影响已落库历史数据的重放幂等行为。
     */
    private void ensureUniqueIdempotentKeys(List<LedgerEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        java.util.Map<String, Integer> keyCounter = new java.util.HashMap<>();
        for (LedgerEntry entry : entries) {
            if (entry.getIdempotentKey() == null) {
                continue;
            }
            keyCounter.put(entry.getIdempotentKey(), keyCounter.getOrDefault(entry.getIdempotentKey(), 0) + 1);
        }
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            LedgerEntry entry = entries.get(i);
            String key = entry.getIdempotentKey();
            if (key == null) {
                continue;
            }
            Integer total = keyCounter.getOrDefault(key, 0);
            if (total <= 1) {
                continue;
            }
            int index = seen.getOrDefault(key, 0);
            seen.put(key, index + 1);
            String orderPart = (entry.getRefOrderId() == null) ? "NA" : String.valueOf(entry.getRefOrderId());
            entry.setIdempotentKey(key + ":" + orderPart + ":" + index);
        }
    }

    private BigDecimal calculateMarginByLeverage(BigDecimal price, BigDecimal quantity, int leverage) {
        if (price == null || quantity == null) {
            return BigDecimal.ZERO;
        }
        if (leverage <= 0) {
            leverage = 10;
        }
        BigDecimal notional = price.multiply(quantity);
        return notional.divide(BigDecimal.valueOf(leverage), 8, java.math.RoundingMode.HALF_UP);
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
        String userPart = (userId == null) ? "null" : String.valueOf(userId);
        String idempotentKey = String.format("%s:%s:%s:%d",
            businessType.getCode(), refTradeId, userPart, accountType.getCode());
        entry.setIdempotentKey(idempotentKey);
        
        entry.setCreatedAt(System.currentTimeMillis());
        
        return entry;
    }
    
    /**
     * 生成单个用户的成交分录（Net Mode 下拆分平仓/开仓）
     */
    private List<LedgerEntry> generateUserTradeEntries(Long userId, Long orderId, String tradeId,
                                                         boolean isBuy, BigDecimal price, BigDecimal quantity,
                                                         int leverage, String symbol) {
        List<LedgerEntry> entries = new ArrayList<>();

        // 查询净持仓
        BigDecimal netPosition = queryNetPosition(userId, symbol);

        BigDecimal closeQty = BigDecimal.ZERO;
        BigDecimal openQty = quantity;

        if (isBuy) {
            // BUY：先平空仓，剩余开多仓
            if (netPosition != null && netPosition.compareTo(BigDecimal.ZERO) < 0) {
                BigDecimal shortSize = netPosition.abs();
                closeQty = shortSize.min(quantity);
                openQty = quantity.subtract(closeQty);
            }
        } else {
            // SELL：先平多仓，剩余开空仓
            if (netPosition != null && netPosition.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal longSize = netPosition;
                closeQty = longSize.min(quantity);
                openQty = quantity.subtract(closeQty);
            }
        }

        // 平仓分录：position_margin → available
        if (closeQty.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal closeMargin = calculateMarginByLeverage(price, closeQty, leverage);
            entries.add(createEntry(
                userId, AccountType.USER_POSITION_MARGIN,
                BigDecimal.ZERO, closeMargin,
                BusinessType.TRADE_SETTLE, tradeId, orderId
            ));
            entries.add(createEntry(
                userId, AccountType.USER_AVAILABLE,
                closeMargin, BigDecimal.ZERO,
                BusinessType.TRADE_SETTLE, tradeId, orderId
            ));
        }

        // 开仓分录：available → position_margin
        if (openQty.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal openMargin = calculateMarginByLeverage(price, openQty, leverage);
            entries.add(createEntry(
                userId, AccountType.USER_AVAILABLE,
                BigDecimal.ZERO, openMargin,
                BusinessType.TRADE_SETTLE, tradeId, orderId
            ));
            entries.add(createEntry(
                userId, AccountType.USER_POSITION_MARGIN,
                openMargin, BigDecimal.ZERO,
                BusinessType.TRADE_SETTLE, tradeId, orderId
            ));
        }

        return entries;
    }

    /**
     * 查询用户净持仓（Net Mode）
     */
    private BigDecimal queryNetPosition(Long userId, String symbol) {
        if (userId == null || symbol == null || positionSnapshotClient == null) {
            return null;
        }
        try {
            return positionSnapshotClient.getNetPosition(userId, symbol);
        } catch (Exception e) {
            log.warn("[LedgerService] Failed to query net position, userId={}, symbol={}, fallback to all-open",
                userId, symbol);
            return null;
        }
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
    
    /**
     * 🔥 从 Ledger 真相源精确计算账户可用余额
     * 
     * 替代 snapshot 查询，避免依赖可能延迟的派生数据。
     * 公式：SUM(debit) - SUM(credit) WHERE account_type = USER_AVAILABLE
     * 
     * @param userId 用户ID
     * @return 可用余额；计算失败时返回 null（调用方应优雅降级）
     */
    private BigDecimal getAvailableBalanceFromLedger(Long userId) {
        try {
            String tableMonth = getCurrentTableMonth();
            BigDecimal balance = ledgerEntryMapper.calculateBalance(
                tableMonth, userId, AccountType.USER_AVAILABLE.getCode(), CURRENCY);
            return balance != null ? balance : BigDecimal.ZERO;
        } catch (Exception e) {
            log.warn("[LedgerService] Failed to calculate available balance from ledger, userId={}, err={}",
                userId, e.getMessage());
            return null;
        }
    }
    
    /**
     * 🔥 为分录列表填充真实的 balance_before / balance_after
     * 
     * 修复：原实现固定写 BigDecimal.ZERO，导致余额快照字段失去审计价值。
     * 本方法基于 ledger_entry 实时计算每笔分录前的真实余额，并顺序累加。
     * 
     * @param entries 待填充的分录列表（必须在 batchInsert 前调用）
     */
    private void fillRealBalances(List<LedgerEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        
        String tableMonth = getCurrentTableMonth();
        Map<String, BigDecimal> currentBalanceMap = new HashMap<>();
        Map<String, BigDecimal> runningBalanceMap = new HashMap<>();
        
        // 第一步：为每个 (userId, accountType) 组合查询当前真实余额
        for (LedgerEntry entry : entries) {
            String key = entry.getUserId() + ":" + entry.getAccountType();
            if (!currentBalanceMap.containsKey(key)) {
                try {
                    BigDecimal balance = ledgerEntryMapper.calculateBalance(
                        tableMonth, entry.getUserId(), entry.getAccountType(), CURRENCY);
                    currentBalanceMap.put(key, balance != null ? balance : BigDecimal.ZERO);
                } catch (Exception e) {
                    log.warn("[LedgerService] fillRealBalances: cannot get balance for key={}, err={}",
                        key, e.getMessage());
                    currentBalanceMap.put(key, BigDecimal.ZERO);
                }
            }
        }
        
        // 第二步：顺序遍历，balance_before 取当前累计值（含本批前面同账户分录）
        for (LedgerEntry entry : entries) {
            String key = entry.getUserId() + ":" + entry.getAccountType();
            BigDecimal before = runningBalanceMap.getOrDefault(key, currentBalanceMap.getOrDefault(key, BigDecimal.ZERO));
            BigDecimal after = before.add(entry.getDebit()).subtract(entry.getCredit());
            
            entry.setBalanceBefore(before);
            entry.setBalanceAfter(after);
            
            runningBalanceMap.put(key, after);
        }
    }
    
    // ==================== 初始资金相关 ====================
    
    /**
     * 创建初始资金（用户注册时）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createInitialFunding(Long userId, Long accountId, String currency, BigDecimal amount, String reason) {
        log.info("[LedgerService] Create initial funding, userId={}, amount={}", userId, amount);

        String refTradeId = "INIT_" + userId;
        String userFundingIdempotentKey = String.format("%s:%s:%d:%d",
            BusinessType.INITIAL_FUNDING.getCode(), refTradeId, userId, AccountType.USER_AVAILABLE.getCode());

        // 幂等保护：如果该用户初始入金已存在，直接返回成功
        LedgerEntry existingEntry = ledgerEntryMapper.selectOne(
            new LambdaQueryWrapper<LedgerEntry>()
                .eq(LedgerEntry::getIdempotentKey, userFundingIdempotentKey)
        );
        if (existingEntry != null) {
            log.warn("[LedgerService] Initial funding already exists, userId={}, entryId={}, idempotentKey={}",
                userId, existingEntry.getEntryId(), userFundingIdempotentKey);
            return String.valueOf(existingEntry.getEntryId());
        }
        
        // 生成分录
        List<LedgerEntry> entries = new ArrayList<>();
        
        // 1. 用户可用余额增加（借）
        entries.add(createEntry(
            userId,
            AccountType.USER_AVAILABLE,
            amount,
            BigDecimal.ZERO,
            BusinessType.INITIAL_FUNDING,
            refTradeId,
            accountId
        ));
        
        // 2. 系统初始资金账户减少（贷）
        entries.add(createEntry(
            SYSTEM_USER_ID, // 系统账户
            AccountType.SYSTEM_INITIAL_FUNDING,
            BigDecimal.ZERO,
            amount,
            BusinessType.INITIAL_FUNDING,
            refTradeId,
            accountId
        ));
        
        setPairEntryIds(entries);
        fillRealBalances(entries);
        
        // 写入LedgerEntry（批量插入，性能优化）
        if (!entries.isEmpty()) {
            try {
                ledgerEntryMapper.batchInsert(entries);
            } catch (DuplicateKeyException e) {
                // 并发重复写入时，按幂等成功处理
                LedgerEntry duplicated = ledgerEntryMapper.selectOne(
                    new LambdaQueryWrapper<LedgerEntry>()
                        .eq(LedgerEntry::getIdempotentKey, userFundingIdempotentKey)
                );
                if (duplicated != null) {
                    log.warn("[LedgerService] Duplicate initial funding detected, treat as success, userId={}, entryId={}, message={}",
                        userId, duplicated.getEntryId(), e.getMessage());
                    return String.valueOf(duplicated.getEntryId());
                }
                throw e;
            }
        }
        
        // 发布事件
        TradeEntryEvent event = new TradeEntryEvent();
        event.setTradeId(refTradeId);
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
     * 
     * 修复：原实现硬编码返回全0，现优先从 account_snapshot 查询，
     * 若 snapshot 不存在或 Mapper 未就绪，则从 ledger_entry 实时计算余额。
     */
    @Override
    public com.exchange.ledger.entity.AccountSnapshot getAccountSnapshot(Long userId) {
        com.exchange.ledger.entity.AccountSnapshot result = new com.exchange.ledger.entity.AccountSnapshot();
        result.setUserId(userId);
        result.setCurrency(CURRENCY);
        
        // 优先从 snapshot 表读取（快速路径）
        if (accountSnapshotMapper != null) {
            try {
                AccountSnapshot snapshot = accountSnapshotMapper.selectOne(
                    new LambdaQueryWrapper<AccountSnapshot>()
                        .eq(AccountSnapshot::getUserId, userId)
                        .last("LIMIT 1")
                );
                if (snapshot != null) {
                    result.setAvailable(snapshot.getAvailable());
                    result.setFrozen(snapshot.getFrozen());
                    result.setPositionMargin(snapshot.getPositionMargin());
                    result.setUnrealizedPnl(snapshot.getUnrealizedPnl());
                    result.setRealizedPnl(snapshot.getRealizedPnl());
                    result.setEquity(snapshot.getEquity());
                    return result;
                }
            } catch (Exception e) {
                log.warn("[LedgerService] getAccountSnapshot: snapshot query failed, fallback to ledger, userId={}, err={}",
                    userId, e.getMessage());
            }
        }
        
        // Fallback：从 ledger_entry 实时计算（真相源）
        String tableMonth = getCurrentTableMonth();
        try {
            BigDecimal available = ledgerEntryMapper.calculateBalance(
                tableMonth, userId, AccountType.USER_AVAILABLE.getCode(), CURRENCY);
            BigDecimal frozen = ledgerEntryMapper.calculateBalance(
                tableMonth, userId, AccountType.USER_FROZEN.getCode(), CURRENCY);
            BigDecimal positionMargin = ledgerEntryMapper.calculateBalance(
                tableMonth, userId, AccountType.USER_POSITION_MARGIN.getCode(), CURRENCY);
            
            result.setAvailable(available != null ? available : BigDecimal.ZERO);
            result.setFrozen(frozen != null ? frozen : BigDecimal.ZERO);
            result.setPositionMargin(positionMargin != null ? positionMargin : BigDecimal.ZERO);
            result.setEquity(result.getAvailable().add(result.getFrozen()).add(result.getPositionMargin()));
        } catch (Exception e) {
            log.error("[LedgerService] getAccountSnapshot: ledger calc also failed, return zeros, userId={}, err={}",
                userId, e.getMessage());
            result.setAvailable(BigDecimal.ZERO);
            result.setFrozen(BigDecimal.ZERO);
            result.setPositionMargin(BigDecimal.ZERO);
            result.setEquity(BigDecimal.ZERO);
        }
        
        result.setUnrealizedPnl(BigDecimal.ZERO);
        result.setRealizedPnl(BigDecimal.ZERO);
        return result;
    }
    
    /**
     * 🔥 分页查询 Ledger Entry（用于 snapshot-account-core 直接 Replay）
     *
     * 支持跨月表查询，按 biz_seq 升序返回。
     * 遍历各月 ledger_entry_YYYYMM 表，合并后按 biz_seq 排序取前 limit 条。
     *
     * @param userId 用户ID（可选，null 表示所有用户）
     * @param startBizSeq 起始 biz_seq（包含），默认 0
     * @param limit 最大返回条数，默认 5000
     * @return LedgerEntry 列表（已按 biz_seq 排序）
     */
    @Override
    public List<LedgerEntry> queryLedgerEntries(Long userId, Long startBizSeq, Integer limit) {
        List<LedgerEntry> result = new ArrayList<>();
        int pageLimit = (limit != null && limit > 0) ? limit : 5000;
        Long startSeq = (startBizSeq != null) ? startBizSeq : 0L;
        
        // 🔥 优化：先确定最早有数据的月份，避免遍历大量空表
        LocalDate currentMonth = LocalDate.now();
        LocalDate earliestDataMonth = findEarliestDataMonth(currentMonth, startSeq);
        
        if (earliestDataMonth == null) {
            log.info("[LedgerService] No data found across all tables for startSeq={}", startSeq);
            return result;
        }
        
        log.debug("[LedgerService] Query range: {} ~ {}, startSeq={}, limit={}",
            earliestDataMonth, currentMonth, startSeq, pageLimit);
        
        // 从最早有数据的月份正向遍历到当前月份
        LocalDate iterateMonth = earliestDataMonth;
        while (!iterateMonth.isAfter(currentMonth)) {
            String tableMonth = iterateMonth.format(DateTimeFormatter.ofPattern("yyyyMM"));
            try {
                // 检查该表是否有符合条件的数据
                Long maxSeq = ledgerEntryMapper.selectMaxBizSeq(tableMonth);
                if (maxSeq == null || maxSeq < startSeq) {
                    iterateMonth = iterateMonth.plusMonths(1);
                    continue;
                }
                
                // 查询该表符合条件的 entries
                List<LedgerEntry> batch = ledgerEntryMapper.selectByBizSeqRange(
                    tableMonth, startSeq, Long.MAX_VALUE, pageLimit
                );
                
                if (batch != null && !batch.isEmpty()) {
                    // 如果指定了 userId，过滤
                    if (userId != null) {
                        batch = batch.stream()
                            .filter(e -> userId.equals(e.getUserId()))
                            .collect(Collectors.toList());
                    }
                    result.addAll(batch);
                }
            } catch (Exception e) {
                log.debug("[LedgerService] Table query skipped: ledger_entry_{}, err={}",
                    tableMonth, e.getMessage());
            }
            iterateMonth = iterateMonth.plusMonths(1);
        }
        
        // 按 biz_seq 升序排序，取前 limit 条
        result.sort(Comparator.comparing(LedgerEntry::getBizSeq));
        if (result.size() > pageLimit) {
            return result.subList(0, pageLimit);
        }
        return result;
    }
    
    /**
     * 🔥 查找最早有数据的月份（优化遍历范围）
     * 
     * 从当前月份往前遍历，使用 selectMinBizSeq 找到第一个有数据的表。
     * 最多遍历 36 个月。
     * 
     * @param currentMonth 当前月份
     * @param startBizSeq 起始 biz_seq
     * @return 最早有数据的月份；如果没有数据返回 null
     */
    private LocalDate findEarliestDataMonth(LocalDate currentMonth, Long startBizSeq) {
        LocalDate earliestCheck = currentMonth.minusMonths(36);
        LocalDate checkMonth = currentMonth;
        LocalDate foundMonth = null;
        
        while (!checkMonth.isBefore(earliestCheck)) {
            String tableMonth = checkMonth.format(DateTimeFormatter.ofPattern("yyyyMM"));
            try {
                Long maxSeq = ledgerEntryMapper.selectMaxBizSeq(tableMonth);
                if (maxSeq != null && maxSeq >= startBizSeq) {
                    foundMonth = checkMonth;
                }
            } catch (Exception e) {
                log.debug("[LedgerService] Table not found: ledger_entry_{}", tableMonth);
            }
            checkMonth = checkMonth.minusMonths(1);
        }
        
        return foundMonth;
    }
    
    /**
     * 🔥 统一金额格式转换
     * 
     * 问题背景：OMS 可能传入两种格式的金额：
     * 1. 实际金额：如 900（表示 900 USDT）
     * 2. 扩大后的金额：如 90000000000（表示 900 USDT × 10^8）
     * 
     * 转换规则：
     * - 如果 amount > 1000万（SCALE_THRESHOLD），认为是扩大后的金额，除以 10^8 转换为实际金额
     * - 否则，直接使用实际金额
     * 
     * 注意：
     * - 1000万 = 10000000，即 0.1 USDT × 10^8
     * - 正常用户的可用余额不会小于 0.1 USDT（手续费都不够）
     * - 所以金额 > 1000万基本可以确定是扩大后的格式
     * 
     * @param amount 传入的金额（可能是实际金额或扩大后的金额）
     * @return 实际金额
     */
    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        
        // 如果金额大于阈值，认为是扩大后的金额，需要转换
        if (amount.compareTo(SCALE_THRESHOLD) > 0) {
            BigDecimal actualAmount = amount.divide(SCALE, 8, java.math.RoundingMode.HALF_UP);
            log.debug("[LedgerService] Amount normalized: scaled={} -> actual={}", amount, actualAmount);
            return actualAmount;
        }
        
        // 金额小于阈值，直接使用（已经是实际金额）
        return amount;
    }
}
