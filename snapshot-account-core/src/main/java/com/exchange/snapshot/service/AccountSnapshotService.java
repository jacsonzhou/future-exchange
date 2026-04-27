package com.exchange.snapshot.service;

import com.exchange.snapshot.dto.AdlClearingApplyResult;
import com.exchange.snapshot.dto.AdlClearingRequest;
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
     * 处理持仓估值更新（来自 position-change 事件）
     *
     * @param userId 用户ID
     * @param symbol 交易对
     * @param positionSide 持仓方向（1=LONG,2=SHORT）
     * @param positionQty 持仓数量
     * @param unrealizedPnl 持仓未实现盈亏
     * @param changeType 变更类型
     */
    void onPositionMarkUpdate(Long userId,
                              String symbol,
                              Integer positionSide,
                              java.math.BigDecimal positionQty,
                              java.math.BigDecimal unrealizedPnl,
                              String changeType,
                              String markPriceId);

    /**
     * 处理ADL内部记账（用于ADL执行阶段幂等确认）。
     */
    AdlClearingApplyResult applyAdlClearing(AdlClearingRequest request);

    /**
     * 查询ADL记账bizSeq是否已处理。
     */
    boolean hasProcessedAdlClearing(String bizSeq);
    
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

    /**
     * 🔥 从持仓镜像重建未实现盈亏（unrealizedPnl）
     *
     * 用途：
     * 1. Replay 完成后重建 unrealizedPnl（trade-entry 不包含估值数据）
     * 2. 修复 snapshot 与 position snapshot 之间的 upnl 不一致
     *
     * @param userId 用户ID
     */
    void rebuildUnrealizedPnl(Long userId);
}
