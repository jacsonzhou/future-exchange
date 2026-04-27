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

    /**
     * 🔥 从 Ledger 数据库直接 Replay（绕过 Kafka retention 限制）
     *
     * 用途：
     * 1. Kafka trade-entry 消息已过期时，直接从 ledger_entry 表读取重建
     * 2. 全量灾备恢复
     * 3. 指定 userId 做增量恢复
     *
     * @param symbol 交易对（或 SYSTEM）
     * @param userId 用户ID（可选，null 表示所有用户）
     * @param startBizSeq 起始业务序列号（可选，null 表示从最早开始）
     * @param endBizSeq 结束业务序列号（可选，null 表示到最新）
     * @return replayId
     */
    String replayFromLedgerDb(String symbol, Long userId, Long startBizSeq, Long endBizSeq);
}

