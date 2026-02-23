package com.exchange.ledger.service;

import com.exchange.ledger.dto.TradeDTO;
import com.exchange.ledger.dto.TradeEntryEvent;

/**
 * Ledger Service 核心服务（生产级）
 * 
 * 🔥 核心职责：
 * 1. 消费TradeEvent，生成双录分录
 * 2. 写入LedgerEntry（唯一资金真相）
 * 3. 发布TradeEntryEvent到Kafka
 * 4. 保证幂等性和事务一致性
 * 
 * ⚠️ 重要变化：
 * - 不再直接写AccountSnapshot（解耦！）
 * - 只负责写账和发布事件
 * - AccountSnapshot由独立服务消费事件更新
 * 
 * 对标：Binance / OKX / Bybit级别
 */
public interface LedgerService {
    
    /**
     * 应用成交到Ledger（核心方法）
     * 
     * 🔥 核心流程（重构后）：
     * 1. 校验幂等性（idempotent_key）
     * 2. 生成双录分录（买方+卖方，各2条）
     * 3. 写入ledger_entry表
     * 4. 发布TradeEntryEvent到Kafka ✅ 新增
     * 
     * ❌ 移除：不再直接更新account_snapshot
     * 
     * @param trade 成交事件
     */
    void applyTrade(TradeDTO trade);
    
    /**
     * Replay重放（灾备核心）
     * 
     * @param event 历史TradeEntryEvent
     */
    void replayTrade(TradeEntryEvent event);
    
    /**
     * 冻结保证金（下单时）
     * 
     * 流程：
     * 1. 写入ledger_entry（MARGIN_FREEZE）
     * 2. 发布事件到Kafka
     * 
     * @param userId 用户ID
     * @param currency 币种
     * @param amount 冻结金额
     * @param orderId 订单ID
     */
    void freezeMargin(Long userId, String currency, java.math.BigDecimal amount, Long orderId);
    
    /**
     * 解冻保证金（撤单时）
     * 
     * 流程：
     * 1. 写入ledger_entry（MARGIN_UNFREEZE）
     * 2. 发布事件到Kafka
     * 
     * @param userId 用户ID
     * @param currency 币种
     * @param amount 解冻金额
     * @param orderId 订单ID
     */
    void unfreezeMargin(Long userId, String currency, java.math.BigDecimal amount, Long orderId);
    
    /**
     * 获取下一个biz_seq（全局序列号）
     *
     * @return 全局递增序列号
     */
    Long getNextBizSeq();

    /**
     * 创建初始资金（用户注册时）
     *
     * 流程：
     * 1. 写入ledger_entry（INITIAL_FUNDING）
     * 2. 发布事件到Kafka
     *
     * 双录分录：
     * - 借：USER_AVAILABLE (用户可用余额) + amount
     * - 贷：SYSTEM_INITIAL_FUNDING (系统初始资金账户) - amount
     *
     * @param userId 用户ID
     * @param accountId 账户ID
     * @param currency 币种
     * @param amount 初始金额
     * @param reason 原因
     * @return 分录ID
     */
    String createInitialFunding(Long userId, Long accountId, String currency, java.math.BigDecimal amount, String reason);
    
    /**
     * 获取账户快照
     *
     * @param userId 用户ID
     * @return 账户快照
     */
    com.exchange.ledger.entity.AccountSnapshot getAccountSnapshot(Long userId);
}



