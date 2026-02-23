package com.exchange.snapshot.service;

/**
 * Replay Service（灾备核心）
 * 
 * 🔥 核心职责：
 * 1. 按Symbol重放历史TradeEntryEvent
 * 2. 按时间区间重放
 * 3. 重建AccountSnapshot
 * 
 * 对标：Binance / OKX / Bybit级别
 */
public interface ReplayService {
    
    /**
     * 按Symbol重放历史事件
     * 
     * 流程：
     * 1. 从Kafka读取历史消息（从头开始）
     * 2. 顺序应用到AccountSnapshotService
     * 3. 重建所有Snapshot
     * 
     * @param symbol 交易对
     * @return replayId
     */
    String replaySymbol(String symbol);
    
    /**
     * 按时间区间重放
     * 
     * @param symbol 交易对
     * @param startTs 开始时间
     * @param endTs 结束时间
     * @return replayId
     */
    String replayRange(String symbol, Long startTs, Long endTs);
    
    /**
     * 查询Replay进度
     * 
     * @param replayId Replay任务ID
     * @return 进度百分比
     */
    Integer getReplayProgress(String replayId);
}

