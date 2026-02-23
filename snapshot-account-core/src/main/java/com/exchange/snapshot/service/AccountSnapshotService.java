package com.exchange.snapshot.service;

import com.exchange.snapshot.dto.TradeEntryEvent;
import com.exchange.snapshot.entity.AccountSnapshot;

/**
 * Account Snapshot Service（生产级）
 * 
 * 🔥 核心职责：
 * 1. 消费Ledger-Core的TradeEntryEvent
 * 2. 更新MySQL的account_snapshot表
 * 3. 更新Redis缓存（热数据）
 * 4. 供风控和API查询
 * 
 * 对标：Binance / OKX / Bybit级别
 */
public interface AccountSnapshotService {
    
    /**
     * 消费TradeEntryEvent（核心方法）
     * 
     * 流程：
     * 1. 解析事件
     * 2. 幂等性校验
     * 3. 应用分录到Snapshot
     * 4. 更新MySQL
     * 5. 更新Redis
     * 
     * @param event TradeEntryEvent
     */
    void onTradeEntryEvent(TradeEntryEvent event);
    
    /**
     * 查询账户快照（风控读取）
     * 
     * 查询顺序：
     * 1. 先查Redis（热数据）
     * 2. Redis Miss则查MySQL
     * 3. 回写Redis
     * 
     * @param userId 用户ID
     * @return AccountSnapshot
     */
    AccountSnapshot queryAccount(Long userId);
    
    /**
     * 更新Redis快照
     * 
     * @param snapshot AccountSnapshot
     */
    void updateRedisSnapshot(AccountSnapshot snapshot);
    
    /**
     * 从Redis获取快照
     * 
     * @param userId 用户ID
     * @return AccountSnapshot or null
     */
    AccountSnapshot getFromRedis(Long userId);
}
